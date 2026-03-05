(ns chatbot.adapters.telegram.normalizer
  "Transforms Telegram updates into UnifiedMessage format.
   This adapter ensures the domain core never sees Telegram-specific structures."
  (:require [chatbot.domain.entities :as entities]
            [clojure.tools.logging :as log]))

(defn- extract-command
  "Extract command from message text (e.g., '/start' -> '/start')"
  [text]
  (when (and text (clojure.string/starts-with? text "/"))
    (first (clojure.string/split text #"\s+"))))

(defn- detect-message-type
  "Detect the type of Telegram message"
  [message]
  (cond
    (contains? message :photo)    :photo
    (contains? message :document) :document
    (contains? message :sticker)  :sticker
    (some? (extract-command (:text message))) :command
    (contains? message :text)     :text
    :else :unknown))

(defn- extract-photo-file-id
  "Extract the best quality photo file_id from photos array"
  [photos]
  (when (seq photos)
    ;; Telegram sends multiple sizes, last one is highest resolution
    (:file_id (last photos))))

(defn normalize-message
  "Transform a Telegram message update into UnifiedMessage"
  [message]
  (let [chat-id (get-in message [:chat :id])
        user-id (str chat-id)
        internal-id (entities/make-internal-id :telegram user-id)
        text (or (:text message) (:caption message))
        command (extract-command text)
        msg-type (detect-message-type message)]
    
    {:message-id     (str (:message_id message))
     :platform       :telegram
     :user-id        internal-id
     :original-user-id user-id
     :type           msg-type
     :timestamp      (java.util.Date. (* 1000 (or (:date message) 
                                                   (quot (System/currentTimeMillis) 1000))))
     :content        {:text (when-not command text)
                      :command command
                      :callback-data nil
                      :photo-file-id (extract-photo-file-id (:photo message))
                      :document-file-id (get-in message [:document :file_id])}
     :raw-data       message
     ;; Extra Telegram-specific fields for user creation
     :user-info      {:username   (get-in message [:from :username])
                      :first-name (get-in message [:from :first_name])
                      :last-name  (get-in message [:from :last_name])}}))

(defn normalize-callback
  "Transform a Telegram callback_query into UnifiedMessage"
  [callback]
  (let [from (:from callback)
        user-id (str (:id from))
        internal-id (entities/make-internal-id :telegram user-id)
        message (:message callback)]

    {:message-id     (str (:id callback))
     :platform       :telegram
     :user-id        internal-id
     :original-user-id user-id
     :type           :callback
     :timestamp      (java.util.Date.)
     :content        {:text nil
                      :command nil
                      :callback-data (:data callback)
                      :photo-file-id nil
                      :document-file-id nil}
     :raw-data       callback
     :callback-id    {:platform :telegram :callback-id-value (:id callback)}
     :user-info      {:username   (:username from)
                      :first-name (:first_name from)
                      :last-name  (:last_name from)}}))

(defn normalize-channel-post
  "Transform a Telegram channel_post into UnifiedMessage.
   Used for channel post synchronization between platforms.

   Channel posts differ from regular messages:
   - No :from field (sender is the channel itself)
   - :sender_chat contains channel info
   - :chat is the channel where post was published"
  [post]
  (let [chat (:chat post)
        channel-id (str (:id chat))
        channel-username (:username chat)
        ;; Use channel ID as user-id for routing
        internal-id (str "telegram_channel_" channel-id)
        text (or (:text post) (:caption post))
        msg-type (detect-message-type post)
        ;; Check if post is from bot (for anti-loop)
        via-bot (:via_bot post)
        is-forwarded (some? (:forward_date post))]

    {:message-id      (str (:message_id post))
     :platform        :telegram
     :user-id         internal-id
     :original-user-id channel-id
     :type            :channel_post
     :timestamp       (java.util.Date. (* 1000 (or (:date post)
                                                    (quot (System/currentTimeMillis) 1000))))
     :content         {:text text
                       :command nil
                       :callback-data nil
                       :photo-file-id (extract-photo-file-id (:photo post))
                       :photo-file-ids (when (:photo post)
                                         (mapv :file_id (:photo post)))
                       :document-file-id (get-in post [:document :file_id])
                       :media-group-id (:media_group_id post)}
     :raw-data        post
     :channel-info    {:channel-id channel-id
                       :channel-username channel-username
                       :channel-title (:title chat)}
     :sync-info       {:via-bot (some? via-bot)
                       :via-bot-username (when via-bot (:username via-bot))
                       :is-forwarded is-forwarded}}))

(defn normalize-update
  "Normalize any Telegram update into UnifiedMessage.
   Returns nil for unsupported update types."
  [update]
  (cond
    (:message update)
    (normalize-message (:message update))

    (:callback_query update)
    (normalize-callback (:callback_query update))

    (:channel_post update)
    (normalize-channel-post (:channel_post update))

    (:edited_message update)
    nil  ; Ignore edited messages for now

    (:edited_channel_post update)
    nil  ; Ignore edited channel posts for now

    :else
    (do
      (log/debug "Skipping unsupported update type:" (if (map? update) (vec (keys update)) update))
      nil)))
