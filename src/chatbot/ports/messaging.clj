(ns chatbot.ports.messaging
  "Port interface for messaging services.
   This protocol abstracts the communication channel (Telegram, Max, etc.)")

(defprotocol SendingService
  "Protocol for sending messages to users"
  
  (send-text [this user-id text opts]
    "Send a text message to a user.
     opts may include :keyboard, :parse-mode, :disable-notification")
  
  (send-photo [this user-id photo-url caption opts]
    "Send a photo to a user.
     photo-url can be a file_id, URL, or local path.
     opts may include :keyboard, :parse-mode")
  
  (answer-callback [this callback-id text opts]
    "Answer a callback query (acknowledge button press).
     opts may include :show-alert")
  
  (get-file-url [this file-id]
    "Get a downloadable URL for a file by its ID")
  
  (check-subscription [this user-id channel-id]
    "Check if user is subscribed to a channel.
     Returns true if subscribed, false otherwise."))

(defprotocol ReceivingService
  "Protocol for receiving messages (polling or webhooks)"
  
  (start-polling [this handler-fn]
    "Start long-polling for updates.
     handler-fn will be called with each normalized message.")
  
  (stop-polling [this]
    "Stop the polling loop gracefully"))
