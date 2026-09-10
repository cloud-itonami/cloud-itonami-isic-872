(ns mhscare.phase
  "MhsCarephase -- staged rollout (Phase 0→3) governing which ops
  can auto-commit vs. require human approval.

  - Phase 0: read-only
  - Phase 1: log-resident-note (approval-gated)
  - Phase 2: + schedule-family-or-guardian-visit, coordinate-supply-request,
             schedule-staff-shift-proposal (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals
             (safety concerns always escalate, regardless of phase)

  `:flag-safety-concern` is NEVER in any `:auto` set -- it always escalates.")

(def phase-rules
  {0 {:name :read-only :auto #{}}
   1 {:name :intake-gated :auto #{}}
   2 {:name :coordination-gated :auto #{}}
   3 {:name :auto-commit :auto #{:log-resident-note :schedule-family-or-guardian-visit
                                  :coordinate-supply-request :schedule-staff-shift-proposal}}})

(defn can-auto-commit?
  "Determine if an op can auto-commit at the given phase.
  Note: :flag-safety-concern is NEVER auto-committing, regardless of phase."
  [op phase-num]
  (and phase-num
       (let [rules (get phase-rules phase-num)]
         (contains? (:auto rules) op))))

(defn phase-name [phase-num]
  (get-in phase-rules [phase-num :name]))

(defn valid-phase? [phase-num]
  (contains? phase-rules phase-num))
