(ns chatbot.domain.admin
  "Admin panel functionality - campaign management and review moderation"
  (:require [chatbot.domain.messages :as msg]
            [clojure.tools.logging :as log]
            [clojure.string :as str]))

;; ============================================
;; Admin Check
;; ============================================

(defn admin?
  "Check if user is an admin. Supports both Telegram and MAX platforms.

   Args:
     config  - application config with :platforms section
     user-id - internal user ID (e.g., 'telegram_123' or 'max_456_789')

   Returns:
     true if user is admin, false otherwise"
  [config user-id]
  (when (string? user-id)
    (cond
      ;; Telegram user (format: telegram_userId)
      (str/starts-with? user-id "telegram_")
      (let [original-id (subs user-id 9)
            admin-id    (get-in config [:platforms :telegram :admin-id])
            admin-ids   (when admin-id (set (map str/trim (str/split (str admin-id) #","))))]
        (contains? admin-ids (str original-id)))

      ;; MAX user (format: max_userId_chatId)
      (str/starts-with? user-id "max_")
      (let [parts       (str/split user-id #"_")
            original-id (second parts)
            admin-id    (get-in config [:platforms :max :admin-id])
            admin-ids   (when admin-id (set (map str/trim (str/split (str admin-id) #","))))]
        (contains? admin-ids (str original-id)))

      ;; Unknown platform
      :else false)))

;; ============================================
;; Admin Menu
;; ============================================

(defn admin-menu-keyboard
  "Build admin menu keyboard"
  []
  (msg/admin-menu-keyboard))

(defn admin-menu-response
  "Create admin menu response"
  [user-id]
  {:text (msg/t :admin-menu-title)
   :keyboard (admin-menu-keyboard)})

;; ============================================
;; Review Moderation
;; ============================================

(defn format-review-for-admin
  "Format a review for admin viewing with full content"
  [review idx total]
  (let [{:keys [id user-id author-name content-text photo-urls]} review
        photos-count (count (or photo-urls []))
        author-line  (if author-name
                       (str "👤 Автор: *" author-name "* (`" user-id "`)")
                       (str "👤 От: `" user-id "`"))]
    (str "📝 *Отзыв " (inc idx) "/" total "*\n\n"
         author-line "\n"
         "📷 Фото: " photos-count " шт.\n\n"
         "💬 *Текст отзыва:*\n"
         (if (str/blank? content-text)
           "_Текст отсутствует_"
           content-text)
         "\n\n"
         "🔗 ID: `" id "`")))

(defn review-moderation-keyboard
  "Build keyboard for review moderation"
  [review-id has-photos]
  {:type :inline
   :buttons (cond-> []
              has-photos (conj [{:text (msg/b :show-photos) :callback (str "show_photos_" review-id)}])
              true (conj [{:text (msg/b :approve) :callback (str "approve_" review-id)}
                          {:text (msg/b :reject) :callback (str "reject_" review-id)}])
              true (conj [{:text (msg/b :skip-review) :callback (str "skip_review_" review-id)}])
              true (conj [{:text (msg/b :admin-menu) :callback "admin_menu"}]))})

(defn no-reviews-response
  "Response when no reviews pending"
  []
  {:text (msg/t :admin-reviews-empty)
   :keyboard (msg/admin-back-keyboard)})

;; ============================================
;; Campaign Management
;; ============================================

(defn- format-expires-at
  "Format expires-at timestamp to dd.MM.yyyy string. Returns nil if no expiry."
  [expires-at]
  (when expires-at
    (try
      (cond
        (instance? java.sql.Timestamp expires-at)
        (.format (.toLocalDateTime ^java.sql.Timestamp expires-at)
                 (java.time.format.DateTimeFormatter/ofPattern "dd.MM.yyyy"))

        (instance? java.time.OffsetDateTime expires-at)
        (.format (.toLocalDate ^java.time.OffsetDateTime expires-at)
                 (java.time.format.DateTimeFormatter/ofPattern "dd.MM.yyyy"))

        :else (str expires-at))
      (catch Exception _ nil))))

(defn format-campaign-item
  "Format a single campaign as a numbered line for the list view.
   Note: arg order is [idx campaign] to match map-indexed."
  [idx campaign]
  (let [{:keys [name is-active max-uses usage-count expires-at promo-code description]} campaign
        status-emoji (if is-active "🟢" "🔴")
        code-str     (if promo-code (str "`" promo-code "`") "—")
        usage-str    (if max-uses
                       (str (or usage-count 0) "/" max-uses)
                       (str (or usage-count 0) "/∞"))
        expiry-str   (if-let [d (format-expires-at expires-at)]
                       (str "до " d)
                       "без срока")]
    (str (inc idx) ". " status-emoji " *" name "*"
         "\n   " code-str " · " usage-str " · " expiry-str
         (when (not (str/blank? description))
           (str "\n   📝 " description)))))

(defn format-campaigns-text
  "Format all campaigns as admin message text."
  [campaigns]
  (if (seq campaigns)
    (str "📣 *Кампании* (" (count campaigns) ")\n\n"
         (str/join "\n\n" (map-indexed format-campaign-item campaigns))
         "\n\n_Нажмите кнопку кампании для переключения активности._")
    "📣 *Кампании*\n\nНет кампаний. Создайте первую!"))

(defn format-campaign-wizard-summary
  "Format wizard state data as confirmation text before campaign creation."
  [wizard]
  (let [{:keys [name code description expires-at]} wizard]
    (str "📋 *Подтвердите создание кампании:*\n\n"
         "📌 Название: *" name "*\n"
         "🎁 Промокод: `" code "`\n"
         (when description (str "📝 Описание: " description "\n"))
         "📅 Срок: " (or expires-at "Без ограничений"))))

(defn format-campaign-edit-summary
  "Format edit wizard state data as confirmation text before saving."
  [wizard]
  (let [{:keys [name code expires-at]} wizard]
    (str "📋 *Подтвердите изменения кампании:*\n\n"
         "📌 Название: *" name "*\n"
         "🎁 Промокод: `" code "`\n"
         "📅 Срок: " (or expires-at "Без ограничений"))))

;; ============================================
;; Stats
;; ============================================

(defn format-stats
  "Format statistics for admin"
  [{:keys [total-users active-campaigns total-campaigns pending-reviews]}]
  (str "📊 *Статистика*\n\n"
       "👥 Пользователей: " (or total-users 0) "\n"
       "📣 Кампаний: " (or active-campaigns 0) " активных / " (or total-campaigns 0) " всего\n"
       "📝 Отзывов на модерации: " (or pending-reviews 0)))
