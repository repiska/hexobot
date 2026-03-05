(ns chatbot.domain.services.promo
  "Promo code service - handles subscription check and code issuance via campaigns"
  (:require [chatbot.domain.messages :as msg]
            [clojure.tools.logging :as log]))

;; ============================================
;; FOMO Helpers
;; ============================================

(defn- ms-until
  "Milliseconds remaining until expires-at. Returns nil if no expiry."
  [expires-at]
  (when expires-at
    (let [epoch-ms (cond
                     (instance? java.sql.Timestamp expires-at)
                     (.getTime ^java.sql.Timestamp expires-at)

                     (instance? java.time.OffsetDateTime expires-at)
                     (.toEpochMilli (.toInstant ^java.time.OffsetDateTime expires-at))

                     (instance? java.time.Instant expires-at)
                     (.toEpochMilli ^java.time.Instant expires-at)

                     :else nil)]
      (when epoch-ms
        (- epoch-ms (System/currentTimeMillis))))))

(defn- format-time-left
  "Human-readable time remaining: '3 дн.', '5 ч.', 'меньше часа'."
  [expires-at]
  (when-let [ms (ms-until expires-at)]
    (when (pos? ms)
      (let [hours (quot ms (* 60 60 1000))
            days  (quot hours 24)]
        (cond
          (> days 1) (str days " дн.")
          (= days 1) "1 день"
          (> hours 0) (str hours " ч.")
          :else "меньше часа")))))

(defn format-fomo-hint
  "Build a FOMO line for a campaign. Returns nil if campaign is unlimited and evergreen."
  [campaign]
  (when campaign
    (let [max-uses    (:max-uses campaign)
          usage-count (or (:usage-count campaign) 0)
          expires-at  (:expires-at campaign)
          remaining   (when max-uses (max 0 (- max-uses usage-count)))
          time-str    (format-time-left expires-at)]
      (cond
        (and remaining time-str)
        (str "⚡️ Осталось " remaining " из " max-uses " · ⏳ " time-str)

        remaining
        (str "⚡️ Осталось " remaining " из " max-uses " промокодов")

        time-str
        (str "⏳ До конца акции: " time-str)

        :else nil))))

(defn- format-share-hint
  "Nudge after code issuance: remaining count encourages sharing."
  [campaign]
  (when campaign
    (let [max-uses    (:max-uses campaign)
          ;; usage_count was incremented during issue, so remaining is already reduced
          usage-count (or (:usage-count campaign) 0)
          remaining   (when max-uses (max 0 (- max-uses (inc usage-count))))]
      (when (and remaining (pos? max-uses))
        (if (zero? remaining)
          "🔥 Вы получили последний промокод!"
          (str "🎯 Поделитесь с друзьями — ещё " remaining " из " max-uses " кодов доступно!"))))))

;; ============================================
;; Core Service
;; ============================================

(defn check-and-issue-promo
  "Check subscription and issue promo code via active campaign.
   Returns {:success? bool :message str :code str :reason kw :campaign map}

   Dependencies:
   - check-subscription-fn:   (fn [user-id channel-id]) -> bool
   - find-active-campaign-fn: (fn [user-id]) -> campaign map or nil (excludes already-received)
   - issue-for-campaign-fn:   (fn [user-id campaign]) -> {:success? bool :code str}"
  [dependencies user-id channel-id]
  (let [{:keys [check-subscription-fn
                find-active-campaign-fn
                issue-for-campaign-fn]} dependencies]

    (log/info "Processing promo request for user:" user-id "channel:" channel-id)

    (cond
      ;; No channel configured
      (nil? channel-id)
      {:success? false
       :message  (msg/t :promo-unavailable)
       :reason   :no-channel}

      :else
      (let [campaign (find-active-campaign-fn user-id)]
        (if (nil? campaign)
          {:success? false
           :message  (msg/t :promo-no-active-campaign)
           :reason   :no-campaign}

          ;; Check channel subscription
          (if (= false (check-subscription-fn user-id channel-id))
            {:success?              false
             :message               (msg/t :promo-need-subscribe)
             :reason                :not-subscribed
             :show-subscribe-button true
             :campaign              campaign}

            ;; Issue from campaign
            (let [result (issue-for-campaign-fn user-id campaign)]
              (cond
                (:success? result)
                {:success? true
                 :message  (msg/t :promo-issued (:code result))
                 :code     (:code result)
                 :reason   :issued
                 :campaign campaign}

                (= :already-issued (:reason result))
                {:success? false
                 :message  (msg/t :promo-already-has (:code result))
                 :reason   :already-issued
                 :code     (:code result)
                 :campaign campaign}

                :else
                {:success? false
                 :message  (msg/t :promo-all-out)
                 :reason   (:reason result :no-codes)
                 :campaign campaign}))))))))

(defn build-promo-response
  "Build response based on promo check result. Injects FOMO hints where appropriate."
  [user-id result channel-link]
  (let [{:keys [message show-subscribe-button campaign reason]} result

        ;; Append FOMO hint to subscribe prompt (most impactful moment)
        final-message
        (case reason
          :not-subscribed
          (if-let [hint (format-fomo-hint campaign)]
            (str message "\n\n" hint)
            message)

          :issued
          (if-let [share (format-share-hint campaign)]
            (str message "\n\n" share)
            message)

          ;; all other cases: no change
          message)]

    (if show-subscribe-button
      {:text     final-message
       :keyboard (if channel-link
                   (msg/promo-subscribe-keyboard channel-link)
                   (msg/promo-check-keyboard))}
      {:text     final-message
       :keyboard (msg/back-to-menu-keyboard)})))


;; ============================================
;; Email Promo Service
;; ============================================

(defn check-and-issue-email-promo
  "Issue promo code for email submission. No subscription check needed.

   Dependencies:
   - find-active-email-campaign-fn: (fn [user-id]) -> campaign map or nil
   - issue-for-campaign-fn:         (fn [user-id campaign]) -> {:success? bool :code str}"
  [dependencies user-id]
  (let [{:keys [find-active-email-campaign-fn
                issue-for-campaign-fn]} dependencies
        campaign (find-active-email-campaign-fn user-id)]
    (if (nil? campaign)
      {:success? false
       :reason   :no-campaign
       :message  (msg/t :promo-no-active-campaign)}
      (let [result (issue-for-campaign-fn user-id campaign)]
        (cond
          (:success? result)
          {:success? true
           :code     (:code result)
           :reason   :issued
           :campaign campaign
           :message  (let [base (msg/t :promo-issued (:code result))
                           share (format-share-hint campaign)]
                       (if share (str base "\n\n" share) base))}

          (= :already-issued (:reason result))
          {:success? false
           :reason   :already-issued
           :code     (:code result)
           :message  (msg/t :email-already-have (:code result))}

          :else
          {:success? false
           :reason   (:reason result :no-codes)
           :message  (msg/t :promo-all-out)})))))

(defn build-email-promo-response
  "Build response map for email promo result."
  [result]
  {:text     (:message result)
   :keyboard (msg/back-to-menu-keyboard)})
