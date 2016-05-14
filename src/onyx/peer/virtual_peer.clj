(ns ^:no-doc onyx.peer.virtual-peer
  (:require [clojure.core.async :refer [chan >!! <!! thread alts!! close! dropping-buffer]]
            [com.stuartsierra.component :as component]
            [onyx.messaging.messenger :as m]
            [onyx.extensions :as extensions]
            [taoensso.timbre :as timbre :refer [info]]
            [onyx.peer.operation :as operation]
            [onyx.log.entry :refer [create-log-entry]]
            [onyx.static.default-vals :refer [defaults arg-or-default]]))

(defn send-to-outbox! [{:keys [outbox-ch] :as state} reactions]
  (doseq [reaction reactions]
    (clojure.core.async/>!! outbox-ch reaction))
  state)

(def logssss (atom {}))

(defn annotate-reaction [{:keys [message-id]} id entry]
  (let [peer-annotated (assoc entry :peer-parent id)]
    ;; Not all messages are derived from other messages.
    ;; For instance, :prepare-join-cluster is a "root"
    ;; message.
    (if message-id
      (assoc peer-annotated :entry-parent message-id)
      peer-annotated)))

(defn processing-loop [id log peer-group origin inbox-ch outbox-ch restart-ch kill-ch opts monitoring task-component-fn]
  (let [replica-atom (atom origin)]
    (try
      (loop [state {:id id
                    :task-component-fn task-component-fn
                    :replica replica-atom
                    :log log
                    :buffered-outbox []
                    :peer-group peer-group
                    :monitoring monitoring
                    :outbox-ch outbox-ch
                    :opts opts
                    :kill-ch kill-ch
                    :restart-ch restart-ch}]
        (let [replica @replica-atom
              [entry ch] (alts!! [kill-ch inbox-ch] :priority true)]
          (swap! logssss assoc (:message-id entry) entry)
          (cond 
            (instance? java.lang.Throwable entry) 
            (close! restart-ch)
            (nil? entry) 
            (when (:lifecycle state)
              (component/stop @(:lifecycle state)))
            :else
            (let [new-replica (assoc (extensions/apply-log-entry entry replica)
                                     :version (:message-id entry))
                  diff (extensions/replica-diff entry replica new-replica)
                  reactions (extensions/reactions entry replica new-replica diff state)
                  new-state (extensions/fire-side-effects! entry replica new-replica diff state)
                  annotated-reactions (mapv (partial annotate-reaction entry id) reactions)]
              (reset! replica-atom new-replica)
              (recur (send-to-outbox! new-state annotated-reactions))))))
      (catch Throwable e
        (taoensso.timbre/error e (format "Peer %s: error in processing loop. Restarting." id))
        (close! restart-ch))
      (finally
        ;; call peer replica view side effects shutdown stuff here
        (taoensso.timbre/info (format "Peer %s: finished of processing loop" id))))))

(defn outbox-loop [id log outbox-ch restart-ch]
  (try
    (loop []
      (when-let [entry (<!! outbox-ch)]
        (extensions/write-log-entry log entry)
        (recur)))
    (catch Throwable e
      (taoensso.timbre/warn e (format "Peer %s: error writing log entry. Restarting." id))
      (close! restart-ch))
    (finally
      (taoensso.timbre/info (format "Peer %s: finished outbox loop" id)))))

(defrecord VirtualPeer [opts peer-group task-component-fn]
  component/Lifecycle

  (start [{:keys [log monitoring] :as component}]
    (let [id (java.util.UUID/randomUUID)]
      (taoensso.timbre/info (format "Starting Virtual Peer %s" id))
      (try
        ;; Race to write the job scheduler and messaging to durable storage so that
        ;; non-peers subscribers can discover which messaging to use.
        ;; Only one peer will succeed, and only one needs to.
        (extensions/write-chunk log :job-scheduler {:job-scheduler (:onyx.peer/job-scheduler opts)} nil)
        (extensions/write-chunk log :messaging {:messaging (select-keys opts [:onyx.messaging/impl])} nil)

        (let [inbox-ch (chan (arg-or-default :onyx.peer/inbox-capacity opts))
              outbox-ch (chan (arg-or-default :onyx.peer/outbox-capacity opts))
              kill-ch (chan (dropping-buffer 1))
              restart-ch (chan 1)
              peer-site (m/peer-site (:messaging-group peer-group) id)
              entry (create-log-entry :prepare-join-cluster {:joiner id :peer-site peer-site
                                                             :tags (or (:onyx.peer/tags opts) [])})
              origin (extensions/subscribe-to-log log inbox-ch)]
          (extensions/register-pulse log id)
          (>!! outbox-ch entry)

          (let [outbox-loop-ch (thread (outbox-loop id log outbox-ch restart-ch))
                processing-loop-ch (thread (processing-loop id log peer-group origin inbox-ch outbox-ch restart-ch kill-ch opts monitoring task-component-fn))]
            (assoc component
                   :outbox-loop-ch outbox-loop-ch
                   :processing-loop-ch processing-loop-ch
                   :id id :inbox-ch inbox-ch
                   :outbox-ch outbox-ch :kill-ch kill-ch
                   :restart-ch restart-ch)))
        (catch Throwable e
          (taoensso.timbre/fatal e (format "Error starting Virtual Peer %s" id))
          (throw e)))))

  (stop [component]
    (taoensso.timbre/info (format "Stopping Virtual Peer %s" (:id component)))

    (close! (:inbox-ch component))
    (close! (:outbox-ch component))
    (close! (:kill-ch component))
    (close! (:restart-ch component))
    (<!! (:outbox-loop-ch component))
    (<!! (:processing-loop-ch component))

    (assoc component :inbox-ch nil :outbox-loop-ch nil 
           :kill-ch nil :restart-ch nil
           :outbox-loop-ch nil :processing-loop-ch nil)))

(defn virtual-peer [opts peer-group task-component-fn]
  (map->VirtualPeer {:opts opts :peer-group peer-group :task-component-fn task-component-fn}))
