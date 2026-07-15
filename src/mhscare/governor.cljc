(ns mhscare.governor
  "MhsCareGovernor -- the independent compliance layer that earns
  the MhsCareAdvisor the right to commit. Three HARD checks, permanent,
  un-overridable:

    1. Resident unverified      -- must exist AND be `:registered?`/`:verified?`
    2. Effect not :propose      -- MUST be `:propose`, never direct action
    3. Scope exclusion          -- permanently blocks medication, clinical
                                   diagnosis, care-plan changes, restraint,
                                   guardianship, disciplinary, end-of-life,
                                   safety-authority decisions

  One ESCALATE (SOFT) gate: LLM confidence below floor, OR the op
  is `:flag-safety-concern` -- ALWAYS escalates to human."
  (:require [clojure.string :as str]
            [mhscare.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "Closed proposal-op allowlist."
  #{:log-resident-note :schedule-family-or-guardian-visit :coordinate-supply-request
    :schedule-staff-shift-proposal :flag-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings marking out-of-scope decision areas.
  Carefully qualified so legitimate safety-concern observations are never
  incorrectly blocked."
  ["medic" "薬" "medication" "dosing" "処方" "prescription"
   "clinical diagnosis" "clinical-diagnosis" "臨床診断" "assessment"
   "care plan" "care-plan" "ケアプラン" "treatment plan" "care coordination change"
   "physical restraint" "physical-restraint" "身体拘束" "restraint" "拘束"
   "guardianship" "custody" "legal-custody" "state custody" "親権" "後見"
   "disciplinary" "discipline" "punishment" "behavioural management" "懲罰"
   "end of life" "end-of-life" "dnr" "do not resuscitate" "終末期"
   "safety authority" "safety-authority" "safety enforcement" "license suspension" "license-suspension"
   "compliance enforcement" "compliance-enforcement" "investigat" "complaint" "違反"])

;; ----------------------------- checks -----------------------------

(defn- resident-unverified-violations
  [{:keys [resident-id]} st]
  (let [r (store/resident st resident-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :resident-unverified
        :detail (str resident-id " は未登録または未検証の入居者 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "投薬/臨床判断/ケアプラン変更/身体拘束/親権・後見/懲罰/終末期判断/安全当局の判断領域に触れる提案は永久に禁止"}])))

(defn check
  "Censors a proposal. Returns {:ok? bool :violations [...] :confidence c :escalate? bool :hard? bool}."
  [request _context proposal store]
  (let [resident-id (or (:resident-id proposal) (:resident-id request))
        hard (into []
                   (concat (resident-unverified-violations {:resident-id resident-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        op (:op proposal)
        escalate? (or (seq hard) (< conf confidence-floor) (contains? always-escalate-ops op))]
    {:ok? (not (seq hard))
     :violations hard
     :confidence conf
     :escalate? escalate?
     :high-stakes? (or (seq hard) (contains? always-escalate-ops op))
     :hard? (seq hard)}))

;; Test helper
(defn out-of-scope-test-check
  "Helper to verify the governor HARD-blocks an intentionally
  scope-excluded proposal. Used only in test suites."
  [proposal store]
  (check {} :test-context proposal store))
