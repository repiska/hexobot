(ns chatbot.adapters.telegram.poller
  "Long-polling adapter for receiving Telegram updates.
   Implements the ReceivingService port."
  (:require [chatbot.adapters.telegram.api :as api]
            [chatbot.adapters.telegram.normalizer :as normalizer]
            [chatbot.ports.messaging :as ports]
            [clojure.tools.logging :as log])
  (:import [java.util.concurrent.atomic AtomicBoolean]))

(defn create-poller
  "Create a new poller instance"
  [telegram-client]
  {:client telegram-client
   :running (AtomicBoolean. false)
   :offset (atom nil)})

(defn- process-updates
  "Process a batch of updates, calling handler for each"
  [updates handler-fn]
  (doseq [update updates]
    (when-let [message (normalizer/normalize-update update)]
      (try
        (handler-fn message)
        (catch Exception e
          (log/error e "Error processing message:" (:message-id message)))))))

(defn- poll-loop
  "The main polling loop"
  [{:keys [client running offset]} handler-fn]
  (log/info "Starting Telegram polling loop...")
  (while (.get running)
    (try
      (let [params (cond-> {:timeout 25}
                     @offset (assoc :offset @offset))
            response (api/get-updates client params)
            ;; Circuit breaker may wrap result, extract from :result if present
            api-result (or (:result response) response)]
        (if (:ok api-result)
          (let [updates (:result api-result)]
            (when (seq updates)
              (log/debug "Received" (count updates) "updates")
              (process-updates updates handler-fn)
              ;; Update offset to last update_id + 1
              (when-let [last-update (last updates)]
                (reset! offset (inc (:update_id last-update))))))
          (do
            (log/warn "Polling error:" response)
            (Thread/sleep 5000))))
      (catch Exception e
        (log/error e "Polling loop error")
        (Thread/sleep 5000)))))

(defn start-polling
  "Start the polling loop in a background thread"
  [poller handler-fn]
  (when (.compareAndSet (:running poller) false true)
    (log/info "Starting Telegram poller...")
    (future
      (poll-loop poller handler-fn)
      (log/info "Polling loop stopped"))
    true))

(defn stop-polling
  "Stop the polling loop gracefully"
  [poller]
  (when (.compareAndSet (:running poller) true false)
    (log/info "Stopping Telegram poller...")
    true))

(defn polling?
  "Check if poller is currently running"
  [poller]
  (.get (:running poller)))

;; ============================================
;; SendingService Implementation
;; ============================================

(defrecord TelegramAdapter [client]
  ports/SendingService
  
  (send-text [this user-id text opts]
    (let [{:keys [platform original-id]} (chatbot.domain.entities/parse-internal-id user-id)]
      (api/send-message client original-id text opts)))
  
  (send-photo [this user-id photo-url caption opts]
    (let [{:keys [platform original-id]} (chatbot.domain.entities/parse-internal-id user-id)]
      (api/send-photo client original-id photo-url (assoc opts :caption caption))))
  
  (answer-callback [this callback-id text opts]
    (api/answer-callback-query client callback-id {:text text :show-alert (:show-alert opts)}))
  
  (get-file-url [this file-id]
    (let [result (api/get-file client file-id)]
      (when (:ok result)
        (api/get-file-url client (get-in result [:result :file_path])))))
  
  (check-subscription [this user-id channel-id]
    (let [{:keys [platform original-id]} (chatbot.domain.entities/parse-internal-id user-id)]
      (api/check-subscription client channel-id original-id))))

(defn create-adapter
  "Create a TelegramAdapter that implements SendingService"
  [token]
  (let [client (api/make-client token)]
    {:adapter (->TelegramAdapter client)
     :poller (create-poller client)
     :client client}))
