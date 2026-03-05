(ns chatbot.ports.crm
  "Port interface for CRM integration (Chatwoot, etc.).
   This protocol abstracts the CRM system for support ticket management.")

(defprotocol CRMService
  "Protocol for CRM operations"

  (ensure-contact! [this user-data]
    "Create or find a contact in the CRM.
     user-data should contain :user-id, :first-name, :last-name, :platform.
     Returns {:ok true :contact-id id} or {:ok false :error msg}")

  (create-conversation! [this contact-id inbox-id initial-message]
    "Create a new conversation in the CRM.
     Returns {:ok true :conversation-id id} or {:ok false :error msg}")

  (send-message! [this conversation-id message-text opts]
    "Send a message to a CRM conversation (as incoming from client).
     opts may include :attachments for photo URLs.
     Returns {:ok true} or {:ok false :error msg}")

  (resolve-conversation! [this conversation-id]
    "Resolve (close) a conversation in the CRM.
     Returns {:ok true} or {:ok false :error msg}"))
