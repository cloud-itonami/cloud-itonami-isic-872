(ns mhscare.operation-test
  "Integration tests for `mhscare.operation/build` -- builds the REAL
  compiled `langgraph.graph` StateGraph and runs it end-to-end via
  `langgraph.graph/run*` through commit / hard-hold / phase-escalate /
  invalid-request-hold / escalate-approve / escalate-reject routes.
  This namespace previously called `operation/run-proposal`, a plain
  nested-`if`/`let` pipeline that never touched `kotoba-lang/langgraph`
  at all (its own defining namespace's comment said so directly:
  \"Simple workflow runner for testing (not using langgraph-clj
  StateGraph machinery yet)\"), and whose `deps.edn` didn't even
  declare a `langgraph` dependency anywhere. These tests preserve
  every original `run-proposal` scenario's assertion (converted to the
  real compiled graph) and add falsifiable coverage for the genuinely
  NEW capability this fix introduces: a real human-in-the-loop
  approval workflow. The pre-fix `escalate-node` was a dead end -- an
  escalated proposal could never actually be approved-and-committed or
  rejected-and-held anywhere in this repo; `test-escalate-then-approve-
  commits` / `test-escalate-then-reject-holds` below are the first
  tests in this repo's history to exercise that path at all."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [mhscare.operation :as operation]
            [mhscare.store :as store]))

(defn- exec [actor tid request phase-num]
  (g/run* actor {:request request :phase-num phase-num} {:thread-id tid}))

(deftest commit-path-clean-proposal-phase-3
  (testing "a clean, phase-3, high-confidence log-resident-note request
            commits through the real compiled graph and appends EXACTLY
            ONE fact to the audit ledger, which was empty before the run"
    (let [s (store/seed-db)]
      (is (empty? (store/ledger s)) "ledger starts empty")
      (let [actor (operation/build s)
            result (exec actor "t-commit"
                         {:op :log-resident-note :resident-id "resident-1"} 3)
            state (:state result)]
        (is (= :done (:status result)))
        (is (= :commit (:decision state)))
        (is (= :committed (:execution state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :proposal-committed (:op (first ledger)))))
        (is (= 1 (count (store/coordination-log s)))
            "store/commit-record! (coordination-log write) is preserved on the graph's :commit node")))))

(deftest phase-escalate-path-clean-but-not-auto-commit-eligible
  (testing "a clean, non-hard, non-always-escalate proposal that isn't
            in the current phase's auto-commit set still ALWAYS
            escalates (never silently commits, never silently holds) --
            the real graph GENUINELY interrupts at :request-approval,
            after durably logging the escalation itself (preserves the
            pre-fix escalate-node's ledger-write, which happened before
            this fix even added a way to resolve the escalation)"
    (let [s (store/seed-db)
          actor (operation/build s)
          held (exec actor "t-phase-escalate"
                     {:op :log-resident-note :resident-id "resident-1"} 1)
          state (:state held)]
      (is (= :interrupted (:status held)))
      (is (= [:request-approval] (:frontier held)))
      (is (= :escalate (:decision state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (= :proposal-escalated (:op (first ledger)))))
      (is (empty? (store/coordination-log s))
          "not yet committed -- awaiting human sign-off"))))

(deftest hard-hold-path-unverified-resident
  (testing "a registered-but-NOT-verified resident is a HARD, permanent
            governor violation -- the real graph routes straight to
            :hold (no interrupt, no human-approval detour, :status is
            :done not :interrupted) and durably records the hold fact"
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-hold"
                       {:op :log-resident-note :resident-id "resident-3"} 3)
          state (:state result)]
      (is (= :done (:status result)) "a HARD violation never reaches :request-approval")
      (is (= :hold (:decision state)))
      (is (= :held (:execution state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (= :proposal-held (:op (first ledger))))
        (is (some #(= :resident-unverified (:rule %)) (:violations (first ledger)))))
      (is (empty? (store/coordination-log s))
          "a held proposal never reaches store/commit-record!"))))

(deftest invalid-request-holds-at-intake
  (testing "a request missing :resident-id is caught by the real
            :intake node -- genuinely reachable now (previously dead
            code: `run-proposal` never called `intake-node` at all, so
            this validation never actually ran; the missing field
            happened to still surface as a hold via the Governor's own
            resident-unverified check on a nil resident-id instead).
            The graph now short-circuits straight to :hold, never
            calling :advise/:govern at all."
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-invalid" {:op :log-resident-note} 3)
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :hold (:decision state)))
      (is (nil? (:proposal state)) ":advise never ran")
      (is (nil? (:check-result state)) ":govern never ran")
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (= :proposal-held (:op (first ledger))))))))

(deftest escalate-then-approve-commits
  (testing ":flag-safety-concern ALWAYS escalates -- the real graph
            GENUINELY interrupts (checkpointed) at :request-approval; a
            human clinician's approve! resumes the SAME compiled graph
            and commits via the graph's own :request-approval -> :commit
            edge, durably appending to the ledger AND the
            coordination-log. This resolution path did not exist at
            all before this fix -- the pre-graph `escalate-node` had no
            way to ever be resumed."
    (let [s (store/seed-db)
          actor (operation/build s)
          held (exec actor "t-escalate"
                     {:op :flag-safety-concern :resident-id "resident-1"} 3)]
      (is (= :interrupted (:status held)))
      (is (= [:request-approval] (:frontier held)))
      (is (= 1 (count (store/ledger s)))
          "the escalation itself is logged immediately (pre-existing
           behavior), but NOT yet committed -- awaiting human sign-off")
      (is (empty? (store/coordination-log s)))
      (let [approved (g/run* actor {:approval {:status :approved :by "clinician-01"}}
                             {:thread-id "t-escalate" :resume? true})
            approved-state (:state approved)]
        (is (= :done (:status approved)))
        (is (= :commit (:decision approved-state)))
        (is (= :committed (:execution approved-state)))
        (let [ledger (store/ledger s)]
          (is (= 2 (count ledger)))
          (is (= :proposal-committed (:op (last ledger))))
          (is (= "clinician-01" (:approved-by (last ledger)))))
        (is (= 1 (count (store/coordination-log s))))))))

(deftest escalate-then-reject-holds
  (testing "a human clinician rejecting an escalated proposal routes to
            :hold via the :request-approval node's own decision, and
            durably records the rejection -- not a hand-rolled parallel
            path. The proposal never commits."
    (let [s (store/seed-db)
          actor (operation/build s)
          _held (exec actor "t-reject"
                      {:op :flag-safety-concern :resident-id "resident-1"} 3)
          rejected (g/run* actor {:approval {:status :rejected :by "clinician-01"}}
                           {:thread-id "t-reject" :resume? true})
          rejected-state (:state rejected)]
      (is (= :done (:status rejected)))
      (is (= :hold (:decision rejected-state)))
      (is (= :held (:execution rejected-state)))
      (let [ledger (store/ledger s)]
        (is (= 2 (count ledger)))
        (is (= :proposal-held (:op (last ledger))))
        (is (= "clinician-01" (:approval-rejected-by (last ledger)))))
      (is (empty? (store/coordination-log s))))))
