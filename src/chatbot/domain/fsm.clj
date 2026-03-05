(ns chatbot.domain.fsm
  "Finite State Machine reference — pure state transition functions.

   NOTE: This namespace is NOT used in the hot path. Actual state transitions
   are managed directly via :state-update maps returned from handlers in
   core.clj and router.clj.

   The transitions table here is a reference/documentation artifact.
   It does not cover admin wizard states — those are managed inline in core.clj."
  (:require [chatbot.domain.entities :as entities]
            [clojure.tools.logging :as log]))

;; ============================================
;; State Transition Table
;; ============================================

(def transitions
  "Defines valid state transitions.
   Format: {current-state {trigger -> next-state}}"
  {:idle           {:support       :waiting_support
                    :review        :waiting_review
                    :promo         :idle}  ; promo doesn't change state
   
   :waiting_support {:message      :chat_mode
                     :cancel       :idle}
   
   :chat_mode      {:resolved      :idle
                    :cancel        :idle}
   
   :waiting_review {:photo         :waiting_review  ; stay for more photos
                    :text          :waiting_review  ; stay for text
                    :submit        :idle            ; after moderation request
                    :cancel        :idle}})

;; ============================================
;; FSM Pure Functions
;; ============================================

(defn can-transition?
  "Check if transition from current state with given trigger is valid"
  [current-state trigger]
  (let [current (keyword current-state)
        trig (keyword trigger)]
    (contains? (get transitions current {}) trig)))

(defn next-state
  "Get the next state for a given current state and trigger.
   Returns nil if transition is not valid."
  [current-state trigger]
  (let [current (keyword current-state)
        trig (keyword trigger)]
    (get-in transitions [current trig])))

(defn compute-transition
  "Compute the transition result.
   Returns {:valid? bool :from state :to state :trigger trigger}"
  [current-state trigger]
  (let [current (keyword current-state)
        trig (keyword trigger)
        target (next-state current trig)]
    {:valid?  (some? target)
     :from    current
     :to      (or target current)
     :trigger trig}))

;; ============================================
;; State Context Helpers
;; ============================================

(defn initial-state
  "Get the initial state for a new user"
  []
  :idle)

(defn initial-state-data
  "Get the initial state data for a new user"
  []
  {})

(defn with-state-data
  "Add or update state data"
  [current-data key value]
  (assoc (or current-data {}) key value))

(defn clear-state-data
  "Clear all state data (useful on state reset)"
  []
  {})

;; ============================================
;; Trigger Detection
;; ============================================

(defn detect-trigger
  "Detect the FSM trigger from a unified message.
   Returns a keyword trigger or nil."
  [message user-state]
  (let [{:keys [type content]} message
        {:keys [command callback-data text]} content
        current-state (keyword user-state)]
    (cond
      ;; Commands from menu
      (= command "/start")     :menu
      (= command "/support")   :support
      (= command "/review")    :review
      (= command "/promo")     :promo
      (= command "/cancel")    :cancel
      
      ;; Callback buttons
      (= callback-data "menu_support")  :support
      (= callback-data "menu_review")   :review
      (= callback-data "menu_promo")    :promo
      (= callback-data "cancel")        :cancel
      (= callback-data "submit_review") :submit
      
      ;; Content based on current state
      (and (= current-state :waiting_support) 
           (= type :text))              :message
      
      (and (= current-state :waiting_review)
           (= type :photo))             :photo
      
      (and (= current-state :waiting_review)
           (= type :text))              :text
      
      (and (= current-state :chat_mode)
           (or (= type :text) 
               (= type :photo)))        :message
      
      :else nil)))

;; ============================================
;; State Display Names (for user messages)
;; ============================================

(def state-descriptions
  {:idle            "Главное меню"
   :waiting_support "Ожидание описания проблемы"
   :chat_mode       "Диалог с оператором"
   :waiting_review  "Создание отзыва"})

(defn state-description
  "Get human-readable description of a state"
  [state]
  (get state-descriptions (keyword state) "Неизвестное состояние"))
