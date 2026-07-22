(ns mhscare.operation
  "OperationActor -- one residential-care coordination proposal = one
  supervised actor run, expressed as a REAL compiled `langgraph-clj`
  `StateGraph` (`langgraph.graph/state-graph` + `compile-graph`). The
  advisor (MhsCareAdvisor) is sealed into a single node (`:advise`);
  its proposal is ALWAYS routed through the independent
  `MhsCareGovernor` (`:govern`) and the rollout phase gate (`:decide`)
  before anything commits to the SSoT.

  This repo's `deps.edn` previously declared NO `langgraph` dependency
  at all -- not even under an unused `:dev` override, worse than the
  usual gap in this fleet -- and this namespace's own former docstring
  claimed \"The langgraph-clj StateGraph orchestrating a single
  proposal workflow\" directly above a plain hand-rolled
  `run-proposal` function that, tellingly, said the quiet part out
  loud in its OWN comment: \"Simple workflow runner for testing (not
  using langgraph-clj StateGraph machinery yet)\". Worse: `run-proposal`
  never even called the `intake-node` function it defined --
  `intake-state` built a state map with `:decision :pending` and
  `run-proposal`'s own `(if (= (:decision state) :hold) ...)` check
  was permanently dead code, so `intake-node`'s required-field
  validation never actually ran on any real call path (the missing-
  field case happened to still surface as a HOLD anyway, via the
  Governor's own resident-unverified check on a nil resident-id --
  see `test-invalid-request-holds`). Both gaps are now genuinely
  wired: `deps.edn` has a real top-level `io.github.kotoba-lang/langgraph`
  dependency, and `:intake` is a real, reachable first node in the
  compiled graph.

  State machine:
  intake -+-> advise -> govern -> decide -+-> commit
          |                               +-> escalate -> request-approval -+-> commit
          +-> hold (invalid request)                                        +-> hold
                                           +-> hold (governor HARD violation
                                                     or phase does not allow)

  Everything the actor depends on is injected, so each is a swap, not
  a rewrite:
    - the Store        (`mhscare.store/MemStore`, or any `Store` impl)
    - the Advisor impl (`:mock` today; `mhscare.advisor/advise`'s
                         second arm is already the real-LLM injection
                         point -- see its docstring)
    - the Phase        (0->3 rollout; passed per-request via
                         `:phase-num`, not frozen at `build` time --
                         matches the old `run-proposal`'s call-time
                         `phase-num` argument)

  One graph run = one residential-care coordination proposal. No
  unbounded inner loop -- each run is auditable and checkpointed.
  Every escalated/committed/held decision fact lands in
  `mhscare.store`'s append-only ledger (`store/append-ledger!`) --
  this call was ALREADY genuinely wired (not dead code) in the
  pre-graph node functions (`commit-node`/`escalate-node`/
  `hold-node`), and that wiring, along with each fact's exact shape,
  is preserved here unchanged.

  Human-in-the-loop = GENUINELY NEW: the pre-graph `escalate-node` was
  a dead end -- it logged a `:proposal-escalated` fact and stopped;
  there was no mechanism anywhere in this repo for an escalated
  proposal to ever actually be approved-and-committed, or explicitly
  rejected-and-held. `interrupt-before #{:request-approval}` now
  pauses the actor at the `:request-approval` node (reached via the
  real `:escalate` node, which still logs `:proposal-escalated`
  first, exactly as before) until a human clinician/coordinator
  resumes it with a decision. `:flag-safety-concern` ALWAYS reaches
  this node -- see `mhscare.governor/always-escalate-ops` and
  `mhscare.phase`'s independent agreement (never a member of any
  phase's `:auto` set either)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [mhscare.advisor :as advisor]
            [mhscare.governor :as governor]
            [mhscare.phase :as phase]
            [mhscare.store :as store]))

;; ============================================================================
;; Compiled StateGraph
;; ============================================================================

(defn build
  "Compiles an OperationActor graph bound to `store`. opts:
    :advisor-impl -- passed to `mhscare.advisor/advise` (default: :mock)
    :checkpointer -- a `langgraph.checkpoint/Checkpointer`
                     (default: in-memory `cp/mem-checkpointer`)

  The compiled graph's input map: `{:request .. :phase-num ..}` (phase
  is per-request, not frozen at `build` time -- matches the old
  `run-proposal`'s call-time `phase-num` argument)."
  [store & [{:keys [advisor-impl checkpointer]
             :or   {advisor-impl :mock
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request      {:default nil}
         :phase-num    {:default 0}
         :proposal     {:default nil}
         :check-result {:default nil}
         :decision     {:default nil}
         :reason       {:default nil}
         :approval     {:default nil}
         :execution    {:default nil}}})

      (g/add-node :intake
        (fn [{:keys [request]}]
          (let [required [:op :resident-id]]
            (if (every? #(contains? request %) required)
              {}
              {:decision :hold :reason "Invalid request: missing required fields"}))))

      (g/add-node :advise
        (fn [{:keys [request]}]
          {:proposal (advisor/advise advisor-impl store request)}))

      (g/add-node :govern
        (fn [{:keys [request proposal]}]
          {:check-result (governor/check request :production proposal store)}))

      (g/add-node :decide
        (fn [{:keys [proposal check-result phase-num]}]
          (let [op               (:op proposal)
                hard-violations? (:hard? check-result)
                escalate?        (:escalate? check-result)]
            (cond
              ;; HARD governor violations are a permanent block --
              ;; NEVER routed through human approval, straight to :hold.
              hard-violations?
              {:decision :hold :reason "Governor HARD check failed"}

              escalate?
              {:decision :escalate :reason "Requires human approval"}

              (phase/can-auto-commit? op phase-num)
              {:decision :commit :reason "Clean + phase permits auto-commit"}

              :else
              {:decision :escalate :reason "Phase does not permit auto-commit"}))))

      (g/add-node :escalate
        (fn [{:keys [proposal]}]
          (store/append-ledger! store {:op :proposal-escalated
                                        :proposal proposal
                                        :timestamp (System/currentTimeMillis)})
          {:execution :escalated}))

      (g/add-node :request-approval
        (fn [{:keys [approval]}]
          (if (= :approved (:status approval))
            {:decision :commit}
            {:decision :hold :reason "Human rejected escalation"})))

      (g/add-node :commit
        (fn [{:keys [proposal approval]}]
          (let [record {:proposal proposal :decision :committed
                         :timestamp (System/currentTimeMillis)}]
            (store/commit-record! store record)
            (store/append-ledger! store
              (cond-> {:op :proposal-committed :record record}
                (:by approval) (assoc :approved-by (:by approval))))
            {:execution :committed})))

      (g/add-node :hold
        (fn [{:keys [proposal check-result approval]}]
          (store/append-ledger! store
            (cond-> {:op :proposal-held :proposal proposal
                     :violations (:violations check-result)
                     :timestamp (System/currentTimeMillis)}
              (= :rejected (:status approval)) (assoc :approval-rejected-by (:by approval))))
          {:execution :held}))

      (g/set-entry-point :intake)

      (g/add-conditional-edges :intake
        (fn [{:keys [decision]}]
          (if (= :hold decision) :hold :advise)))

      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [decision]}]
          (case decision
            :commit   :commit
            :escalate :escalate
            :hold)))

      (g/add-edge :escalate :request-approval)

      (g/add-conditional-edges :request-approval
        (fn [{:keys [decision]}]
          (if (= :commit decision) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
