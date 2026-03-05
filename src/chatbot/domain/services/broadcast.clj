(ns chatbot.domain.services.broadcast
  "Broadcast service - sends campaign notifications to eligible users.
   Audience: users who previously received any promo (have promo_log entries)
   but have NOT yet been notified about the given campaign."
  (:require [clojure.tools.logging :as log]))

(defn send-campaign-broadcast!
  "Send campaign notification to all eligible users.
   Runs synchronously (caller should wrap in future for async).

   Args:
     campaign  - campaign map with :id, :name, :promo-code
     targets   - seq of user-id strings to notify
     send-fn   - (fn [user-id text keyboard]) sends a message, returns truthy on success
     log-fn    - (fn [campaign-id user-id]) marks broadcast as sent

   Returns:
     {:sent n :failed n :skipped n}"
  [campaign targets send-fn log-fn]
  (let [campaign-id (:id campaign)
        total       (count targets)]
    (log/info "Starting broadcast for campaign:" (:name campaign)
              "id:" campaign-id "targets:" total)
    (loop [remaining targets
           sent    0
           failed  0
           skipped 0]
      (if-not (seq remaining)
        (do
          (log/info "Broadcast finished for campaign:" campaign-id
                    "sent:" sent "failed:" failed "skipped:" skipped)
          {:sent sent :failed failed :skipped skipped})
        (let [user-id (first remaining)
              result  (try
                        (send-fn user-id)
                        (catch Exception e
                          (let [msg (str e)]
                            (cond
                              ;; Telegram/MAX common "user blocked bot" errors - skip silently
                              (or (re-find #"(?i)bot was blocked" msg)
                                  (re-find #"(?i)user is deactivated" msg)
                                  (re-find #"(?i)chat not found" msg)
                                  (re-find #"(?i)forbidden" msg))
                              (do
                                (log/debug "Broadcast skipped (blocked/deactivated):" user-id)
                                :skipped)

                              :else
                              (do
                                (log/warn "Broadcast send failed for:" user-id "-" msg)
                                :failed)))))]
          ;; Log successful delivery
          (when (and result (not= result :skipped) (not= result :failed))
            (try
              (log-fn campaign-id user-id)
              (catch Exception e
                (log/warn "Failed to log broadcast for:" user-id "-" e))))

          ;; Rate limit: ~35ms between sends (~28 msg/sec)
          (Thread/sleep 35)

          (recur (rest remaining)
                 (if (and result (not= result :skipped) (not= result :failed))
                   (inc sent) sent)
                 (if (= result :failed) (inc failed) failed)
                 (if (= result :skipped) (inc skipped) skipped)))))))
