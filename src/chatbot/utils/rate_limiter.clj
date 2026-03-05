(ns chatbot.utils.rate-limiter
  "Token bucket rate limiter implementation.
   Prevents overwhelming external APIs with too many requests.

   Example usage:
     (def limiter (create-limiter 30 1000))  ; 30 requests per second
     (when (acquire! limiter)
       (api-call))
     ;; or
     (execute limiter #(api-call))  ; blocks until token available"
  (:require [clojure.tools.logging :as log]))

;; ============================================================
;; Core Rate Limiter
;; ============================================================

(defn create-limiter
  "Create a token bucket rate limiter.

   Parameters:
     max-tokens - Maximum tokens (requests) in bucket
     refill-ms  - Time window in milliseconds to fully refill

   Returns:
     Atom containing limiter state

   Example:
     ;; 30 requests per second
     (create-limiter 30 1000)
     ;; 100 requests per minute
     (create-limiter 100 60000)"
  [max-tokens refill-ms]
  (atom {:tokens (double max-tokens)
         :max-tokens max-tokens
         :refill-ms refill-ms
         :refill-rate (/ max-tokens refill-ms)  ; tokens per ms
         :last-refill (System/currentTimeMillis)
         :total-acquired 0
         :total-rejected 0
         :name nil}))

(defn create-named-limiter
  "Create a rate limiter with a name for logging."
  [name max-tokens refill-ms]
  (let [limiter (create-limiter max-tokens refill-ms)]
    (swap! limiter assoc :name name)
    limiter))

(defn- refill-tokens
  "Refill tokens based on elapsed time (internal)"
  [state]
  (let [now (System/currentTimeMillis)
        elapsed (- now (:last-refill state))
        tokens-to-add (* (:refill-rate state) elapsed)
        new-tokens (min (:max-tokens state)
                        (+ (:tokens state) tokens-to-add))]
    (assoc state
           :tokens new-tokens
           :last-refill now)))

(defn acquire!
  "Try to acquire a token from the limiter.

   Returns:
     true if token acquired, false if rate limited

   Example:
     (when (acquire! limiter)
       (make-api-call))"
  [limiter]
  (let [[old-state new-state]
        (swap-vals! limiter
                    (fn [state]
                      (let [refilled (refill-tokens state)]
                        (if (>= (:tokens refilled) 1.0)
                          (-> refilled
                              (update :tokens - 1.0)
                              (update :total-acquired inc))
                          (update refilled :total-rejected inc)))))]
    (let [acquired? (not= (:total-acquired old-state)
                          (:total-acquired new-state))]
      (when-not acquired?
        (when-let [name (:name @limiter)]
          (log/debug "Rate limited:" name)))
      acquired?)))

(defn acquire-blocking!
  "Acquire a token, blocking until one is available.
   Use with caution - can block indefinitely under high load.

   Parameters:
     limiter - Rate limiter atom
     max-wait-ms - Maximum time to wait (default: 5000ms)

   Returns:
     true if acquired within timeout, false otherwise"
  ([limiter] (acquire-blocking! limiter 5000))
  ([limiter max-wait-ms]
   (let [start (System/currentTimeMillis)
         deadline (+ start max-wait-ms)]
     (loop []
       (cond
         (acquire! limiter) true
         (> (System/currentTimeMillis) deadline) false
         :else
         (do
           ;; Wait for approximately one token refill time
           (let [wait-time (max 10 (long (/ (:refill-ms @limiter)
                                            (:max-tokens @limiter))))]
             (Thread/sleep wait-time))
           (recur)))))))

(defn execute
  "Execute function with rate limiting.
   Blocks until token is available or timeout.

   Returns:
     {:ok true :result value} on success
     {:ok false :error \"Rate limited\"} on timeout"
  ([limiter f] (execute limiter f 5000))
  ([limiter f timeout-ms]
   (if (acquire-blocking! limiter timeout-ms)
     (try
       {:ok true :result (f)}
       (catch Exception e
         {:ok false :error (ex-message e) :exception e}))
     {:ok false :error "Rate limited - timeout waiting for token"})))

(defn get-stats
  "Get current limiter statistics."
  [limiter]
  (let [state @limiter
        refilled (refill-tokens state)]
    {:name (:name state)
     :available-tokens (int (:tokens refilled))
     :max-tokens (:max-tokens state)
     :refill-ms (:refill-ms state)
     :total-acquired (:total-acquired state)
     :total-rejected (:total-rejected state)
     :rejection-rate (if (> (:total-acquired state) 0)
                       (/ (:total-rejected state)
                          (+ (:total-acquired state)
                             (:total-rejected state)))
                       0.0)}))

;; ============================================================
;; Per-User Rate Limiting
;; ============================================================

(defonce user-limiters (atom {}))

(defn get-user-limiter
  "Get or create a rate limiter for a specific user.
   Useful for per-user rate limiting to prevent abuse.

   Parameters:
     user-id - User identifier
     max-tokens - Max requests
     refill-ms - Refill window"
  [user-id max-tokens refill-ms]
  (if-let [limiter (get @user-limiters user-id)]
    limiter
    (let [limiter (create-named-limiter (str "user-" user-id) max-tokens refill-ms)]
      (swap! user-limiters assoc user-id limiter)
      limiter)))

(defn cleanup-user-limiters!
  "Remove limiters for users who haven't made requests recently.
   Call periodically to prevent memory leaks."
  [max-age-ms]
  (let [now (System/currentTimeMillis)
        cutoff (- now max-age-ms)]
    (swap! user-limiters
           (fn [limiters]
             (into {}
                   (filter (fn [[_ limiter]]
                             (> (:last-refill @limiter) cutoff))
                           limiters))))))

;; ============================================================
;; Global Limiters Registry
;; ============================================================

(defonce global-limiters (atom {}))

(defn register-limiter!
  "Register a global rate limiter by name."
  [key limiter]
  (swap! global-limiters assoc key limiter)
  limiter)

(defn get-limiter
  "Get a registered global limiter."
  [key]
  (get @global-limiters key))

(defn get-all-stats
  "Get statistics for all registered limiters."
  []
  (into {}
        (map (fn [[k limiter]]
               [k (get-stats limiter)])
             @global-limiters)))
