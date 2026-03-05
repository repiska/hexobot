(ns chatbot.ports.repository
  "Port interfaces for data persistence.
   These protocols abstract the database layer.")

(defprotocol UserRepository
  "Protocol for user data operations"
  
  (find-user [this internal-id]
    "Find a user by internal ID (e.g., 'tg_12345').
     Returns user map or nil if not found.")
  
  (find-user-by-original [this platform original-id]
    "Find a user by platform and original platform ID.
     Returns user map or nil if not found.")
  
  (create-user! [this user-data]
    "Create a new user. 
     user-data should contain :id, :platform, :original-id.
     Returns the created user.")
  
  (update-user! [this internal-id updates]
    "Update user fields.
     updates is a map of fields to update.
     Returns updated user.")
  
  (update-state! [this internal-id new-state state-data]
    "Update user's FSM state and state data.
     Returns updated user."))

(defprotocol TicketRepository
  "Protocol for support ticket operations"
  
  (find-ticket [this ticket-id]
    "Find a ticket by UUID")
  
  (find-active-ticket [this user-id]
    "Find an active ticket for a user.
     Returns ticket or nil.")
  
  (create-ticket! [this user-id]
    "Create a new support ticket for a user.
     Returns the created ticket.")
  
  (update-ticket! [this ticket-id updates]
    "Update ticket fields.
     Returns updated ticket.")
  
  (close-ticket! [this ticket-id]
    "Close a ticket (set status to 'closed').
     Returns updated ticket.")

  (find-ticket-by-conv-id [this chatwoot-conv-id]
    "Find a ticket by Chatwoot conversation ID.
     Returns ticket or nil."))

(defprotocol ReviewRepository
  "Protocol for review operations"
  
  (find-review [this review-id]
    "Find a review by UUID")
  
  (find-pending-review [this user-id]
    "Find a review in moderation for a user.
     Returns review or nil.")
  
  (find-all-pending-reviews [this]
    "Find all reviews awaiting moderation.
     Returns list of reviews.")
  
  (create-review! [this user-id content-text photo-urls author-name author-link]
    "Create a new review with author name and profile link.
     Returns the created review.")
  
  (update-review! [this review-id updates]
    "Update review fields.
     Returns updated review.")
  
  (publish-review! [this review-id published-message-id]
    "Mark review as published.
     Returns updated review.")
  
  (reject-review! [this review-id]
    "Mark review as rejected.
     Returns updated review."))

(defprotocol PromoRepository
  "Protocol for promo campaign operations.
   After migration 004 the pool type is removed — every campaign has a single static promo_code."

  ;; ---- Campaign methods ----

  (find-active-campaign [this]
    "Find the first eligible active campaign (not expired, not maxed out).
     Returns campaign map or nil.")

  (find-active-campaign-for-user [this user-id]
    "Find the first eligible active campaign that the user has NOT yet received.
     Excludes campaigns where the user already has a promo_log entry.
     Returns campaign map or nil.")

  (find-active-campaign-for-user-by-type [this user-id campaign-type]
    "Same as find-active-campaign-for-user scoped to campaign_type.
     Use 'welcome' for subscription promos, 'email' for email promos.
     Returns campaign map or nil.")

  (find-issued-promo-for-campaign [this user-id campaign-id]
    "Find if user already received a promo in this specific campaign.
     Returns promo-log entry or nil.")

  (issue-promo-for-campaign! [this user-id campaign]
    "Issue the campaign's promo_code to the user.
     Inserts promo_log entry, increments usage_count.
     Returns {:success? true :code str} or {:success? false :reason kw}.")

  (list-campaigns [this]
    "List all campaigns ordered by created_at desc.
     Returns list of campaign maps.")

  (create-campaign! [this data]
    "Create a new campaign.
     data: {:name :promo-code :max-uses :expires-at :description}
     Returns created campaign.")

  (update-campaign! [this campaign-id updates]
    "Update campaign fields (is_active, max_uses, expires_at, etc.).
     Returns updated campaign.")

  (ensure-welcome-campaign! [this welcome-code]
    "Idempotent: create a default welcome campaign from env if none active exists.
     No-op if welcome-code is nil or an active campaign already exists.")

  (ensure-email-campaign! [this email-code]
    "Idempotent: create a default email campaign from env if none active email campaign exists.
     No-op if email-code is nil or an active email campaign already exists.")

  ;; ---- User promo history ----

  (get-user-promos [this user-id]
    "Get all promos received by user, newest first.
     Returns [{:code-issued :campaign-name :expires-at :issued-at}]")

  (cleanup-expired-promos! [this user-id grace-days]
    "Delete promo_log entries for user where expires_at < NOW() - grace-days days.
     Returns count of deleted rows.")

  ;; ---- Campaign deletion ----

  (delete-campaign! [this campaign-id]
    "Delete a campaign by ID. Returns true on success.")

  ;; ---- Broadcast methods ----

  (get-broadcast-targets [this campaign-id]
    "Get user-ids from promo_log who have NOT yet been notified for this campaign.
     Returns list of user-id strings.")

  (mark-broadcast-sent! [this campaign-id user-id]
    "Record that a broadcast notification was sent to user for campaign."))

(defprotocol StatsRepository
  "Protocol for statistics queries"
  
  (count-users [this]
    "Count total users")
  
  (count-pending-reviews [this]
    "Count reviews awaiting moderation"))
