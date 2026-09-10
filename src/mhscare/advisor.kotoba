(ns mhscare.advisor
  "MhsCareAdvisor -- a contained intelligence node that drafts proposals
  for the MhsCareGovernor to evaluate. The advisor has no notion of whether
  a resident is actually registered/verified, whether its `:effect` secretly
  claims direct actuation, or whether it has drifted into out-of-scope territory.
  The governor handles all three constraints independently.

  Two seams: mock (deterministic, test-friendly) and real-LLM (via langchain.model).")

(defn mock-advise
  "Deterministic mock advisor for testing and demo."
  [{:keys [op resident-id]}]
  (case op
    :log-resident-note
    {:op :log-resident-note
     :resident-id resident-id
     :summary "Daily care note"
     :rationale "Routine observation of resident activity, mood, participation"
     :cites [resident-id]
     :effect :propose
     :value {:note-text "Resident participated in morning activities, mood stable"}
     :confidence 0.85}

    :schedule-family-or-guardian-visit
    {:op :schedule-family-or-guardian-visit
     :resident-id resident-id
     :summary "Schedule family visit"
     :rationale "Family engagement and continuity"
     :cites [resident-id]
     :effect :propose
     :value {:visit-type :family :date "2026-07-20" :duration-minutes 60}
     :confidence 0.8}

    :coordinate-supply-request
    {:op :coordinate-supply-request
     :resident-id resident-id
     :summary "Supply coordination"
     :rationale "Non-medication consumables resupply"
     :cites [resident-id]
     :effect :propose
     :value {:supply-type :linens :quantity 5 :justification "Weekly rotation"}
     :confidence 0.75}

    :schedule-staff-shift-proposal
    {:op :schedule-staff-shift-proposal
     :resident-id resident-id
     :summary "Staff shift proposal"
     :rationale "Staffing coordination"
     :cites [resident-id]
     :effect :propose
     :value {:shift-date "2026-07-22" :shift-type "day"}
     :confidence 0.7}

    :flag-safety-concern
    {:op :flag-safety-concern
     :resident-id resident-id
     :summary "Safety concern flagged"
     :rationale "Resident wellbeing observation"
     :cites [resident-id]
     :effect :propose
     :value {:concern-type :behavioral :description "Observed increased agitation during afternoon session"}
     :confidence 0.9}

    {:op :unknown :resident-id resident-id :effect :propose :confidence 0.0}))

(defn advise
  "Call the advisor to draft a proposal."
  ([advisor-impl _store request]
   (if (= advisor-impl :mock)
     (mock-advise request)
     ;; Real LLM seam would go here (via langchain.model)
     (mock-advise request)))
  ([_store request]
   (mock-advise request)))
