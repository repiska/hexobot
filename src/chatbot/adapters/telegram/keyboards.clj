(ns chatbot.adapters.telegram.keyboards
  "Клавиатуры Telegram. Делегируют к chatbot.domain.messages."
  (:require [chatbot.domain.messages :as msg]))

;; ============================================
;; ВСПОМОГАТЕЛЬНЫЕ ХЕЛПЕРЫ (Telegram-specific)
;; ============================================

(defn inline-button
  "Создать inline-кнопку"
  ([text callback-data]
   {:text text :callback callback-data})
  ([text callback-data url]
   {:text text :callback callback-data :url url}))

(defn url-button
  "Создать кнопку с URL"
  [text url]
  {:text text :url url})

;; ============================================
;; КЛАВИАТУРЫ — делегаты к domain/messages
;; ============================================

(def main-menu-keyboard    msg/main-menu-keyboard)
(def cancel-keyboard       msg/cancel-keyboard)
(def review-keyboard       msg/review-keyboard)
(def support-chat-keyboard msg/support-chat-keyboard)
(def back-to-menu-keyboard msg/back-to-menu-keyboard)
(def subscribe-keyboard    msg/promo-subscribe-keyboard)
(def moderation-keyboard   msg/moderation-keyboard)
