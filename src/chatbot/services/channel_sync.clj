(ns chatbot.services.channel-sync
  "Channel post synchronization between Telegram and MAX.
   When a post is published in one channel, it's automatically
   mirrored to the other platform's channel."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [chatbot.adapters.telegram.api :as tg-api]
            [chatbot.adapters.max.api :as max-api]))

;; ============================================================
;; Anti-loop Protection
;; ============================================================

;; Store recently synced message hashes to prevent loops
(defonce synced-posts (atom #{}))
(def max-synced-cache-size 1000)
(def sync-marker "[synced]")

(defn- post-hash
  "Create a hash for a post to detect duplicates"
  [text photo-count]
  (hash [text photo-count]))

(defn- mark-as-synced!
  "Mark a post as synced to prevent re-sync"
  [hash]
  (swap! synced-posts
         (fn [posts]
           (let [posts (conj posts hash)]
             ;; Limit cache size
             (if (> (count posts) max-synced-cache-size)
               (set (take (/ max-synced-cache-size 2) posts))
               posts)))))

(defn- already-synced?
  "Check if post was already synced"
  [hash]
  (contains? @synced-posts hash))

(defn- should-sync?
  "Check if post should be synced (not from bot, not already synced)"
  [message bot-username]
  (let [{:keys [sync-info content]} message
        {:keys [via-bot via-bot-username is-forwarded]} sync-info
        text (:text content)]
    (cond
      ;; Skip posts sent via our bot
      (and via-bot (= via-bot-username bot-username))
      (do (log/debug "Skipping: post via our bot") false)

      ;; Skip forwarded posts (likely already synced)
      is-forwarded
      (do (log/debug "Skipping: forwarded post") false)

      ;; Skip posts with sync marker
      (and text (str/includes? text sync-marker))
      (do (log/debug "Skipping: has sync marker") false)

      ;; Skip already synced
      (already-synced? (post-hash text (count (:photo-file-ids content))))
      (do (log/debug "Skipping: already synced") false)

      :else true)))

;; ============================================================
;; Telegram -> MAX Sync
;; ============================================================

(defn sync-telegram-to-max!
  "Sync a Telegram channel post to MAX channel.
   Returns {:ok true/false :error msg}"
  [{:keys [tg-client max-client max-channel-id bot-username]} message]
  (when (and max-channel-id (should-sync? message bot-username))
    (let [{:keys [content]} message
          {:keys [text photo-file-id photo-file-ids]} content
          hash (post-hash text (count photo-file-ids))]
      (try
        (log/info "Syncing Telegram post to MAX channel:" max-channel-id)

        (let [result
              (cond
                ;; Photo post
                photo-file-id
                (let [photo-url (tg-api/resolve-file-to-url tg-client photo-file-id)]
                  (if photo-url
                    (max-api/send-photo max-client max-channel-id photo-url
                                        {:caption text})
                    {:ok false :error "Could not resolve photo URL"}))

                ;; Text only
                text
                (max-api/send-message max-client max-channel-id text {})

                :else
                {:ok false :error "No content to sync"})]

          (when (:ok result)
            (mark-as-synced! hash)
            (log/info "Successfully synced to MAX"))

          result)

        (catch Exception e
          (log/error e "Failed to sync Telegram post to MAX")
          {:ok false :error (.getMessage e)})))))

;; ============================================================
;; MAX -> Telegram Sync
;; ============================================================

(defn sync-max-to-telegram!
  "Sync a MAX channel post to Telegram channel.
   Returns {:ok true/false :error msg}"
  [{:keys [tg-client tg-channel-id bot-username]} message]
  (when (and tg-channel-id (should-sync? message bot-username))
    (let [{:keys [content]} message
          {:keys [text photo-file-id]} content
          hash (post-hash text (if photo-file-id 1 0))]
      (try
        (log/info "Syncing MAX post to Telegram channel:" tg-channel-id)

        (let [result
              (cond
                ;; Photo post (MAX already has URL)
                photo-file-id
                (tg-api/send-photo tg-client tg-channel-id photo-file-id
                                   {:caption text})

                ;; Text only
                text
                (tg-api/send-message tg-client tg-channel-id text {})

                :else
                {:ok false :error "No content to sync"})]

          (when (:ok result)
            (mark-as-synced! hash)
            (log/info "Successfully synced to Telegram"))

          result)

        (catch Exception e
          (log/error e "Failed to sync MAX post to Telegram")
          {:ok false :error (.getMessage e)})))))

;; ============================================================
;; Main Handler
;; ============================================================

(defn handle-channel-post!
  "Handle incoming channel post and sync to other platform.
   ctx should contain:
   - :tg-client - Telegram API client
   - :max-client - MAX API client
   - :tg-channel-id - Telegram channel ID (e.g. @channel)
   - :max-channel-id - MAX channel ID
   - :bot-username - Bot username for anti-loop"
  [ctx message]
  (let [platform (:platform message)
        msg-type (:type message)]
    (when (= msg-type :channel_post)
      (case platform
        :telegram (sync-telegram-to-max! ctx message)
        :max (sync-max-to-telegram! ctx message)
        (log/warn "Unknown platform for channel sync:" platform)))))

;; ============================================================
;; Utility
;; ============================================================

(defn clear-sync-cache!
  "Clear the synced posts cache"
  []
  (reset! synced-posts #{})
  (log/info "Sync cache cleared"))

(defn get-sync-stats
  "Get synchronization statistics"
  []
  {:cached-posts (count @synced-posts)
   :max-cache-size max-synced-cache-size})
