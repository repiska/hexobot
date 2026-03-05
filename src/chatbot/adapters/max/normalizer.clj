(ns chatbot.adapters.max.normalizer
  "Transforms MAX updates into UnifiedMessage format.
   This adapter ensures the domain core never sees MAX-specific structures.

   MAX update types:
   - message_created: new message (text, photo, etc.)
   - message_callback: callback button pressed
   - bot_started: user started interaction with bot (equivalent to /start)"
  (:require [chatbot.domain.entities :as entities]
            [clojure.tools.logging :as log]))

(defn- extract-command
  "Extract command from message text (e.g., '/start' -> '/start')"
  [text]
  (when (and text (clojure.string/starts-with? text "/"))
    (first (clojure.string/split text #"\s+"))))

(defn- group-chat?
  "Check if chat_id indicates a group chat or channel.
   In MAX, group chats and channels have negative chat_id.
   Bot should only respond to direct messages (positive chat_id)."
  [chat-id]
  (when chat-id
    (let [chat-id-num (if (string? chat-id)
                        (try (Long/parseLong chat-id) (catch Exception _ nil))
                        chat-id)]
      (and chat-id-num (neg? chat-id-num)))))

(defn- detect-message-type
  "Detect the type of MAX message"
  [message]
  (let [text (get-in message [:body :text])
        attachments (get-in message [:body :attachments])]
    ;; Debug logging to understand attachment structure
    (when (seq attachments)
      (log/debug "MAX attachments received:" (pr-str attachments))
      (log/debug "Attachment types:" (mapv :type attachments)))
    (cond
      ;; Check for photo attachments (MAX may use "image" or "photo")
      (some #(contains? #{"image" "photo"} (:type %)) attachments) :photo
      ;; Check for file attachments
      (some #(= (:type %) "file") attachments) :document
      ;; Check for command
      (some? (extract-command text)) :command
      ;; Plain text
      (some? text) :text
      :else :unknown)))

(defn- extract-photo-url
  "Extract photo URL from MAX attachments"
  [attachments]
  (when-let [photo (first (filter #(contains? #{"image" "photo"} (:type %)) attachments))]
    (get-in photo [:payload :url])))

(defn- extract-file-info
  "Extract file info from MAX attachments"
  [attachments]
  (when-let [file (first (filter #(= (:type %) "file") attachments))]
    {:url (get-in file [:payload :url])
     :name (get-in file [:payload :filename])}))

(defn normalize-message
  "Transform a MAX message_created update into UnifiedMessage.
   Returns nil for messages from group chats/channels (negative chat_id)."
  [update]
  (let [message (:message update)
        sender (:sender message)
        user-id (str (:user_id sender))
        ;; chat-id is for sending responses, NOT for user identification
        ;; Internal ID uses only user_id to ensure consistent user tracking
        chat-id (get-in message [:recipient :chat_id])]
    ;; Ignore messages from group chats/channels - bot only handles direct messages
    (if (group-chat? chat-id)
      (do
        (log/debug "Ignoring message from group chat/channel:" chat-id)
        nil)
      (let [internal-id (entities/make-internal-id :max user-id)
            text (get-in message [:body :text])
            attachments (get-in message [:body :attachments])
            command (extract-command text)
            msg-type (detect-message-type message)
            timestamp (:timestamp update)]
        ;; Log chat info for debugging
        (log/debug "MAX message from user:" user-id "chat_id:" chat-id)
        {:message-id     (get-in message [:body :mid])
         :platform       :max
         :user-id        internal-id
         :original-user-id user-id
         :chat-id        chat-id
         :type           msg-type
         :timestamp      (java.util.Date. timestamp)
         :content        {:text (when-not command text)
                          :command command
                          :callback-data nil
                          :photo-file-id (extract-photo-url attachments)
                          :document-file-id (:url (extract-file-info attachments))}
         :raw-data       update
         :user-info      {:username   nil  ;; MAX doesn't have usernames like Telegram
                          :first-name (:first_name sender)
                          :last-name  (:last_name sender)
                          :name       (:name sender)}}))))

(defn normalize-callback
  "Transform a MAX message_callback update into UnifiedMessage"
  [update]
  (let [callback (:callback update)
        user (:user callback)
        user-id (str (:user_id user))
        ;; chat-id is for sending responses, NOT for user identification
        ;; Internal ID uses only user_id to ensure consistent user tracking
        chat-id (get-in callback [:message :recipient :chat_id])
        internal-id (entities/make-internal-id :max user-id)
        callback-id (:callback_id callback)
        payload (:payload callback)
        timestamp (:timestamp update)]

    {:message-id     callback-id
     :platform       :max
     :user-id        internal-id
     :original-user-id user-id
     :chat-id        chat-id
     :type           :callback
     :timestamp      (java.util.Date. timestamp)
     :content        {:text nil
                      :command nil
                      :callback-data payload
                      :photo-file-id nil
                      :document-file-id nil}
     :raw-data       update
     :callback-id    {:platform :max :callback-id-value callback-id}
     :user-info      {:username   nil
                      :first-name (:first_name user)
                      :last-name  (:last_name user)
                      :name       (:name user)}}))

(defn normalize-bot-started
  "Transform a MAX bot_started update into UnifiedMessage.
   This is equivalent to Telegram's /start command."
  [update]
  (let [user (:user update)
        user-id (str (:user_id user))
        ;; chat-id is for sending responses, NOT for user identification
        ;; Internal ID uses only user_id to ensure consistent user tracking
        chat-id (:chat_id update)
        internal-id (entities/make-internal-id :max user-id)
        timestamp (:timestamp update)]

    {:message-id     (str "bot_started_" timestamp)
     :platform       :max
     :user-id        internal-id
     :original-user-id user-id
     :chat-id        chat-id
     :type           :command
     :timestamp      (java.util.Date. timestamp)
     :content        {:text nil
                      :command "/start"  ;; Treat as /start command
                      :callback-data nil
                      :photo-file-id nil
                      :document-file-id nil}
     :raw-data       update
     :user-info      {:username   nil
                      :first-name (:first_name user)
                      :last_name  (:last_name user)
                      :name       (:name user)}}))

(defn normalize-update
  "Normalize any MAX update into UnifiedMessage.
   Returns nil for unsupported update types."
  [update]
  (let [update-type (:update_type update)]
    (case update-type
      "message_created"
      (normalize-message update)

      "message_callback"
      (normalize-callback update)

      "bot_started"
      (normalize-bot-started update)

      ;; Other known types we explicitly ignore
      ("message_edited" "message_removed" "chat_title_changed")
      (do
        (log/debug "Ignoring MAX update type:" update-type)
        nil)

      ;; Unknown types
      (do
        (log/debug "Skipping unsupported MAX update type:" update-type)
        nil))))

(defn get-update-marker
  "Extract the marker from MAX updates response for pagination"
  [response]
  (:marker response))
