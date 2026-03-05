(ns chatbot.adapters.max.poller
  "Long-polling adapter for receiving MAX updates.
   Implements the SendingService port."
  (:require [chatbot.adapters.max.api :as api]
            [chatbot.adapters.max.normalizer :as normalizer]
            [chatbot.ports.messaging :as ports]
            [chatbot.domain.entities :as entities]
            [clojure.tools.logging :as log])
  (:import [java.util.concurrent.atomic AtomicBoolean]))

(defn create-poller
  "Create a new MAX poller instance"
  [max-client]
  {:client max-client
   :running (AtomicBoolean. false)
   :marker (atom nil)})  ;; MAX uses 'marker' instead of 'offset'

(defn- process-updates
  "Process a batch of updates, calling handler for each"
  [updates handler-fn]
  (doseq [update updates]
    (when-let [message (normalizer/normalize-update update)]
      (try
        (handler-fn message)
        (catch Exception e
          (log/error e "Error processing MAX message:" (:message-id message)))))))

(defn- poll-loop
  "The main polling loop for MAX"
  [{:keys [client running marker]} handler-fn]
  (log/info "Starting MAX polling loop...")
  (while (.get running)
    (try
      (let [params (cond-> {:timeout 25}
                     @marker (assoc :marker @marker))
            response (api/get-updates client params)
            ;; Circuit breaker wraps result, extract from :result if present
            api-result (or (:result response) response)
            updates (:updates api-result)]
        (if updates
          (do
            (when (seq updates)
              (log/debug "Received" (count updates) "MAX updates")
              (process-updates updates handler-fn))
            ;; Update marker for next request
            (when-let [new-marker (:marker api-result)]
              (reset! marker new-marker)))
          (do
            (log/warn "MAX polling error:" response)
            (Thread/sleep 5000))))
      (catch Exception e
        (log/error e "MAX polling loop error")
        (Thread/sleep 5000)))))

(defn start-polling
  "Start the MAX polling loop in a background thread"
  [poller handler-fn]
  (when (.compareAndSet (:running poller) false true)
    (log/info "Starting MAX poller...")
    (future
      (poll-loop poller handler-fn)
      (log/info "MAX polling loop stopped"))
    true))

(defn stop-polling
  "Stop the MAX polling loop gracefully"
  [poller]
  (when (.compareAndSet (:running poller) true false)
    (log/info "Stopping MAX poller...")
    true))

(defn polling?
  "Check if MAX poller is currently running"
  [poller]
  (.get (:running poller)))

;; ============================================
;; SendingService Implementation
;; ============================================

(defrecord MaxAdapter [client]
  ports/SendingService

  (send-text [this user-id text opts]
    (let [{:keys [original-id]} (entities/parse-internal-id user-id)]
      ;; For MAX, we need chat_id, not user_id for sending messages
      ;; The original-id from MAX is the user_id, we need to find/use chat_id
      ;; In dialogs, chat_id is usually different from user_id
      ;; For now, we'll use the chat-id stored in opts if available
      (let [chat-id (or (:chat-id opts) original-id)]
        (api/send-message client chat-id text opts))))

  (send-photo [this user-id photo-url caption opts]
    (let [{:keys [original-id]} (entities/parse-internal-id user-id)
          chat-id (or (:chat-id opts) original-id)]
      (api/send-photo client chat-id photo-url (assoc opts :caption caption))))

  (answer-callback [this callback-id text opts]
    (api/answer-callback client callback-id {:text text}))

  (get-file-url [this file-id]
    ;; MAX files are typically direct URLs
    (api/get-file-url client file-id))

  (check-subscription [this user-id channel-id]
    (let [{:keys [original-id]} (entities/parse-internal-id user-id)]
      ;; Try to check subscription, return {:ok true :subscribed true/false}
      (try
        (let [is-subscribed (api/check-subscription client channel-id original-id)]
          {:ok true :subscribed is-subscribed})
        (catch Exception e
          (log/warn "Could not check MAX subscription:" (.getMessage e))
          {:ok false :error (.getMessage e)})))))

(defn create-adapter
  "Create a MaxAdapter that implements SendingService"
  [token]
  (let [client (api/make-client token)]
    {:adapter (->MaxAdapter client)
     :poller (create-poller client)
     :client client}))
