(ns mhscare.operation
  "The langgraph-clj StateGraph orchestrating a single proposal workflow:
  intake → advise → govern → decide → {commit | hold | escalate}.

  One run = one proposal, no unbounded internal loops. Human sign-off
  is coordinated via `interrupt-before` checkpoints."
  (:require [mhscare.store :as store]
            [mhscare.advisor :as advisor]
            [mhscare.governor :as governor]
            [mhscare.phase :as phase]))

;; ----------------------------- workflow state -----------------------------

(defn intake-state
  "Initial state for a proposal request."
  [request]
  {:request request
   :proposal nil
   :check-result nil
   :decision :pending
   :reason nil})

;; ----------------------------- workflow nodes -----------------------------

(defn intake-node
  "Entry point: validate request shape."
  [state store _ctx]
  (let [request (:request state)
        required [:op :resident-id]]
    (if (every? #(contains? request %) required)
      state
      (assoc state :decision :hold :reason "Invalid request: missing required fields"))))

(defn advise-node
  "Call the advisor to draft a proposal."
  [state store advisor-impl _ctx]
  (let [request (:request state)
        proposal (advisor/advise advisor-impl store request)]
    (assoc state :proposal proposal)))

(defn govern-node
  "Apply governor checks to the proposal."
  [state store _ctx]
  (let [request (:request state)
        proposal (:proposal state)
        check-result (governor/check request :production proposal store)]
    (assoc state :check-result check-result)))

(defn decide-node
  "Decide: commit (auto), escalate (human), or hold (error)."
  [state store phase-num _ctx]
  (let [check-result (:check-result state)
        proposal (:proposal state)
        op (:op proposal)
        hard-violations? (:hard? check-result)
        escalate? (:escalate? check-result)]
    (cond
      hard-violations?
      (assoc state :decision :hold :reason "Governor HARD check failed")

      escalate?
      (assoc state :decision :escalate :reason "Requires human approval")

      (phase/can-auto-commit? op phase-num)
      (assoc state :decision :commit :reason "Clean + phase permits auto-commit")

      :else
      (assoc state :decision :escalate :reason "Phase does not permit auto-commit"))))

(defn commit-node
  "Record the decision to the store."
  [state store _ctx]
  (let [decision (:decision state)]
    (if (= decision :commit)
      (let [proposal (:proposal state)
            record {:proposal proposal :decision :committed :timestamp (System/currentTimeMillis)}]
        (store/commit-record! store record)
        (store/append-ledger! store {:op :proposal-committed :record record})
        (assoc state :execution :committed))
      state)))

(defn escalate-node
  "Log escalation to human (no auto-action)."
  [state store _ctx]
  (let [decision (:decision state)]
    (if (= decision :escalate)
      (let [proposal (:proposal state)
            fact {:op :proposal-escalated :proposal proposal :timestamp (System/currentTimeMillis)}]
        (store/append-ledger! store fact)
        (assoc state :execution :escalated))
      state)))

(defn hold-node
  "Log hold (governor rejection) to audit ledger."
  [state store _ctx]
  (let [decision (:decision state)]
    (if (= decision :hold)
      (let [proposal (:proposal state)
            check-result (:check-result state)
            fact {:op :proposal-held :proposal proposal :violations (:violations check-result) :timestamp (System/currentTimeMillis)}]
        (store/append-ledger! store fact)
        (assoc state :execution :held))
      state)))

;; Simple workflow runner for testing (not using langgraph-clj StateGraph machinery yet)
(defn run-proposal
  "Simple workflow runner: intake → advise → govern → decide → {commit|hold|escalate}."
  [store request advisor-impl phase-num]
  (let [state (intake-state request)]
    (if (= (:decision state) :hold)
      state
      (let [state (advise-node state store advisor-impl nil)]
        (if (:proposal state)
          (let [state (govern-node state store nil)]
            (if (:check-result state)
              (let [state (decide-node state store phase-num nil)]
                (case (:decision state)
                  :commit (commit-node state store nil)
                  :escalate (escalate-node state store nil)
                  :hold (hold-node state store nil)
                  state))
              state))
          state)))))
