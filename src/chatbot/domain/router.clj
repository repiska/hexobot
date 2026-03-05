(ns chatbot.domain.router
  "Message router - dispatches incoming messages to appropriate handlers.
   This is the main entry point for processing user messages."
  (:require [chatbot.domain.entities :as entities]
            [chatbot.domain.messages :as msg]
            [clojure.tools.logging :as log]))

;; ============================================
;; Message Type Detection
;; ============================================

(defn classify-message
  "Classify the type of message for routing purposes"
  [message]
  (let [{:keys [type content]} message
        {:keys [command callback-data]} content]
    (cond
      (some? command)       :command
      (some? callback-data) :callback
      (= type :photo)       :photo
      (= type :document)    :document
      (= type :text)        :text
      :else                 :unknown)))

;; ============================================
;; Response Builders
;; ============================================

(defn- ->response
  "Create a response action map"
  [action user-id data]
  {:action  action
   :user-id user-id
   :data    data})

(defn text-response
  "Create a text message response"
  [user-id text & {:keys [keyboard] :or {keyboard nil}}]
  (->response :send-text user-id {:text text :keyboard keyboard}))

(defn photo-response
  "Create a photo message response"
  [user-id photo-url & {:keys [caption keyboard] :or {caption nil keyboard nil}}]
  (->response :send-photo user-id {:photo photo-url :caption caption :keyboard keyboard}))

(defn photos-response
  "Create multiple photos response (media group)"
  [user-id photo-urls & {:keys [caption] :or {caption nil}}]
  (->response :send-photos user-id {:photos photo-urls :caption caption}))

(defn menu-response
  "Create the main menu response"
  [user-id]
  (text-response user-id
    (msg/t :welcome)
    :keyboard (msg/main-menu-keyboard)))

(defn echo-response
  "Create an echo response (for testing)"
  [user-id text]
  (text-response user-id (str "Вы написали: " text)))

;; ============================================
;; Command Handlers
;; ============================================

(defmulti handle-command
  "Handle bot commands"
  (fn [message user] 
    (get-in message [:content :command])))

(defmethod handle-command "/start"
  [message user]
  (log/info "Handling /start for user:" (:id user))
  {:responses [(menu-response (:user-id message))]
   :state-update {:state :idle :state-data {}}})

(defmethod handle-command "/cancel"
  [message user]
  (log/info "Handling /cancel for user:" (:id user))
  {:responses [(text-response (:user-id message) (msg/t :action-cancelled))
               (menu-response (:user-id message))]
   :state-update {:state :idle :state-data {}}})

(defmethod handle-command :default
  [message user]
  (log/warn "Unknown command:" (get-in message [:content :command]))
  {:responses [(text-response (:user-id message) (msg/t :unknown-command))]
   :state-update nil})

;; ============================================
;; Callback Handlers  
;; ============================================

(defmulti handle-callback
  "Handle callback button presses"
  (fn [message user]
    (get-in message [:content :callback-data])))

(defmethod handle-callback "menu_support"
  [message user]
  (log/info "User requested support:" (:id user))
  {:responses [(text-response (:user-id message)
                 (msg/t :support-start)
                 :keyboard (msg/cancel-keyboard))]
   :state-update {:state :waiting_support :state-data {}}})

(defmethod handle-callback "menu_review"
  [message user]
  (log/info "User wants to leave review:" (:id user))
  {:responses [(text-response (:user-id message)
                 (msg/t :review-start)
                 :keyboard (msg/review-keyboard))]
   :state-update {:state :waiting_review :state-data {:photos [] :text nil}}})

(defmethod handle-callback "cancel"
  [message user]
  {:responses [(text-response (:user-id message) (msg/t :cancelled))
               (menu-response (:user-id message))]
   :state-update {:state :idle :state-data {}}})

(defmethod handle-callback "end_support"
  [message user]
  (log/info "User ending support session:" (:id user))
  {:responses [(text-response (:user-id message) (msg/t :action-cancelled))
               (menu-response (:user-id message))]
   :state-update {:state :idle :state-data {}}})

(defmethod handle-callback "edit_review"
  [message user]
  (log/info "User editing review (draft panel):" (:id user))
  (let [state-data (or (:state-data user) {:photos [] :text nil})]
    {:responses [(text-response (:user-id message)
                   (msg/format-draft-text state-data)
                   :keyboard (msg/draft-panel-keyboard state-data))]
     :state-update {:state :review_editing :state-data state-data}}))

(defmethod handle-callback "menu_email"
  [message user]
  (log/info "User wants email promo:" (:id user))
  {:responses [(text-response (:user-id message)
                 (msg/t :email-prompt)
                 :keyboard (msg/email-ask-keyboard))]
   :state-update {:state :waiting_email :state-data {}}})

(defmethod handle-callback :default
  [message user]
  (log/warn "Unknown callback:" (get-in message [:content :callback-data]))
  {:responses [(text-response (:user-id message) (msg/t :unknown-action))]
   :state-update nil})

;; ============================================
;; State-based Message Handlers
;; ============================================

(defn handle-idle-message
  "Handle text/photo when user is in idle state"
  [message user]
  {:responses [(text-response (:user-id message) (msg/t :select-action))
               (menu-response (:user-id message))]
   :state-update nil})

(defn handle-support-input
  "Handle user's problem description for support"
  [message user]
  (let [text (get-in message [:content :text])]
    (log/info "Support request from" (:id user) ":" text)
    {:responses [(text-response (:user-id message)
                   (msg/t :support-received)
                   :keyboard (msg/support-chat-keyboard))]
     :state-update {:state :chat_mode
                    :state-data {:initial-message text
                                 :pending-support-init true}}}))

(defn handle-chat-message
  "Handle message in active chat with support.
   Forwarding to Chatwoot is done in core.clj (async)."
  [message user]
  (let [text (get-in message [:content :text])]
    (log/info "Chat message from" (:id user) ":" text)
    {:responses [(text-response (:user-id message)
                   (msg/t :support-forwarded)
                   :keyboard (msg/support-chat-keyboard))]
     :state-update nil}))

(defn handle-review-content
  "Handle photo/text for review submission"
  [message user]
  (let [{:keys [type content]} message
        state-data (or (:state-data user) {:photos [] :text nil})
        photo-id (get-in content [:photo-file-id])
        text (get-in content [:text])]
    ;; Debug logging
    (log/debug "handle-review-content called:"
               {:type type :photo-id photo-id :text text :content content})
    (cond
      ;; Photo received
      photo-id
      (let [new-data (cond-> (update state-data :photos conj photo-id)
                       text (assoc :text text))
            caption-text (if text (msg/t :review-text-with-caption) "")]
        (log/info "Review photo added for" (:id user) "Has caption:" (some? text))
        {:responses [(text-response (:user-id message)
                       (msg/t :review-photo-added (count (:photos new-data)) caption-text)
                       :keyboard (msg/review-keyboard))]
         :state-update {:state :waiting_review :state-data new-data}})

      ;; Text received
      text
      (let [replaced?  (some? (:text state-data))
            new-data   (assoc state-data :text text)]
        (log/info "Review text" (if replaced? "replaced" "added") "for" (:id user))
        {:responses [(text-response (:user-id message)
                       (if replaced?
                         (msg/t :review-text-replaced)
                         (msg/t :review-text-added))
                       :keyboard (msg/review-keyboard))]
         :state-update {:state :waiting_review :state-data new-data}})

      :else
      {:responses [(text-response (:user-id message) (msg/t :review-send-content))]
       :state-update nil})))

(defn handle-review-editing
  "Handle photo/text input while user is in :review_editing state.
   Updates the draft and refreshes the draft panel."
  [message user]
  (let [{:keys [type content]} message
        state-data (or (:state-data user) {:photos [] :text nil})
        photo-id   (get-in content [:photo-file-id])
        text       (get-in content [:text])]
    (cond
      ;; Photo received — add to draft
      photo-id
      (let [new-data (cond-> (update state-data :photos conj photo-id)
                       text (assoc :text text))]
        (log/info "Draft photo added for" (:id user))
        {:responses [(text-response (:user-id message)
                       (msg/format-draft-text new-data)
                       :keyboard (msg/draft-panel-keyboard new-data))]
         :state-update {:state :review_editing :state-data new-data}})

      ;; Text received — replace text in draft
      text
      (let [new-data (assoc state-data :text text)]
        (log/info "Draft text updated for" (:id user))
        {:responses [(text-response (:user-id message)
                       (msg/format-draft-text new-data)
                       :keyboard (msg/draft-panel-keyboard new-data))]
         :state-update {:state :review_editing :state-data new-data}})

      :else
      {:responses [(text-response (:user-id message)
                     (msg/format-draft-text state-data)
                     :keyboard (msg/draft-panel-keyboard state-data))]
       :state-update nil})))

;; ============================================
;; Reply Keyboard Normalization
;; ============================================

(defn normalize-reply-text
  "If the message text matches a reply keyboard button label, convert it to
   the equivalent callback so existing callback handlers process it normally."
  [message]
  (let [text     (get-in message [:content :text])
        callback (get msg/reply-button-callbacks text)]
    (if (and text callback (nil? (get-in message [:content :callback-data])))
      (-> message
          (assoc-in [:content :callback-data] callback)
          (assoc-in [:content :text] nil))
      message)))

;; ============================================
;; Main Router
;; ============================================

(defn route-message
  "Main routing function. Takes a message and user, returns processing result.
   Result format: {:responses [...] :state-update {:state :new-state :state-data {...}}}"
  [message user]
  (let [message    (normalize-reply-text message)
        msg-type   (classify-message message)
        user-state (keyword (or (:state user) :idle))]
    
    (log/debug "Routing message:" {:type msg-type :user-state user-state :user-id (:user-id message)})
    
    (case msg-type
      :command  (handle-command message user)
      :callback (handle-callback message user)
      
      ;; Content handling depends on current state
      (:text :photo :document)
      (case user-state
        :idle            (handle-idle-message message user)
        :waiting_support (handle-support-input message user)
        :chat_mode       (handle-chat-message message user)
        :waiting_review  (handle-review-content message user)
        :review_confirm  {:responses [(text-response (:user-id message)
                                        (msg/t :review-confirm-prompt)
                                        :keyboard (msg/review-confirm-keyboard))]
                          :state-update nil}
        :review_editing  (handle-review-editing message user)
        ;; Default
        (handle-idle-message message user))
      
      ;; Unknown message type
      {:responses [(echo-response (:user-id message) 
                     (str "Получено сообщение типа: " (name msg-type)))]
       :state-update nil})))
