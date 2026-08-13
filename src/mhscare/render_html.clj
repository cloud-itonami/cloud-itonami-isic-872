(ns mhscare.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-872`: this
  repo had NO demo page and no generator at all (measured on `main`
  and on every branch before this namespace existed).

  Every row on the produced page comes from actually RUNNING this
  repo's actor -- the REAL compiled `langgraph` StateGraph built by
  `mhscare.operation/build`, driven through `langgraph.graph/run*`
  exactly the way `mhscare.sim` and `test/mhscare/operation_test.clj`
  drive it, including the `interrupt-before #{:request-approval}`
  human-in-the-loop resume. Nothing on the page is hand-typed
  domain content: resident rows come from `mhscare.store/all-residents`,
  hold rows come from the `MhsCareGovernor`'s own returned
  `:violations`, the op allowlist / always-escalate set / rollout phase
  gate come from `mhscare.governor` and `mhscare.phase`'s own vars, and
  the ledger table is `mhscare.store/ledger` verbatim.

  Determinism: no timestamps are rendered (the store stamps facts with
  `System/currentTimeMillis`; those keys are deliberately never read
  here), residents are taken in `store/all-residents`' own sorted
  order, and set-valued contracts are sorted by name before rendering.
  Two runs against the same seed produce byte-identical output.

  Build-time invariant: `-main` REFUSES to write the file if the run
  produced zero HARD governor refusals (a hold fact carrying a
  non-empty `:violations` vector). A console for a governed actor that
  cannot show the governor actually saying no is not evidence of
  anything, so the requirement is enforced here rather than left as a
  convention. Note the invariant counts governor refusals SPECIFICALLY:
  this graph can also reach `:hold` with an EMPTY/absent `:violations`
  (an intake validation failure, or a human rejecting an escalation),
  and those are rendered in a separate table so a naive hold count
  cannot be mistaken for a governor refusal.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [mhscare.governor :as governor]
            [mhscare.operation :as operation]
            [mhscare.phase :as phase]
            [mhscare.store :as store]))

;; ============================================================================
;; Scenarios -- driven through the REAL compiled StateGraph
;; ============================================================================

(def scenarios
  "Each scenario is one full run of the compiled actor graph. Resident
  ids are ONLY the ones `mhscare.store/demo-data` actually seeds
  (`resident-1` Emma Brown -- registered+verified, `resident-2` Diego
  Lopez -- registered+verified, `resident-3` Sophie Chen -- registered
  but NOT verified). `:approval`, when present, is the human decision
  fed back into the SAME graph via `:resume?` after it interrupts at
  `:request-approval`; the approver id is `clinician-01`, the only
  human id this repo has anywhere (`test/mhscare/operation_test.clj`)
  -- this repo's `Store` has no staff/clinician directory to draw a
  second one from, and inventing one would put an untraceable id on
  the page.

  Between them these reach every disposition this actor can produce:
  auto-commit, rollout-gate escalation -> approval -> commit,
  always-escalate -> approval -> commit, always-escalate -> human
  rejection -> hold, three distinct HARD governor refusals, and an
  intake validation hold."
  [{:id "s1-note-r1"
    :label "Daily care note (clean, phase 3)"
    :request {:op :log-resident-note :resident-id "resident-1"}
    :phase 3}

   {:id "s2-visit-r2"
    :label "Family/guardian visit scheduling (clean, phase 3)"
    :request {:op :schedule-family-or-guardian-visit :resident-id "resident-2"}
    :phase 3}

   {:id "s3-shift-r1-phase1"
    :label "Staff shift proposal at phase 1 (rollout gate, not the governor)"
    :request {:op :schedule-staff-shift-proposal :resident-id "resident-1"}
    :phase 1
    :approval {:status :approved :by "clinician-01"}}

   {:id "s4-safety-r1-approved"
    :label "Safety concern -- ALWAYS escalates, clinician approves"
    :request {:op :flag-safety-concern :resident-id "resident-1"}
    :phase 3
    :approval {:status :approved :by "clinician-01"}}

   {:id "s5-safety-r2-rejected"
    :label "Safety concern -- ALWAYS escalates, clinician rejects"
    :request {:op :flag-safety-concern :resident-id "resident-2"}
    :phase 3
    :approval {:status :rejected :by "clinician-01"}}

   {:id "s6-note-r3-unverified"
    :label "Daily care note for a resident still in intake (not yet verified)"
    :request {:op :log-resident-note :resident-id "resident-3"}
    :phase 3}

   {:id "s7-supply-r2"
    :label "Consumable supply coordination (phase 3)"
    :request {:op :coordinate-supply-request :resident-id "resident-2"}
    :phase 3}

   {:id "s8-medication-r1"
    :label "Medication administration attempt (outside the closed allowlist)"
    :request {:op :administer-medication :resident-id "resident-1"}
    :phase 3}

   {:id "s9-missing-resident"
    :label "Malformed request -- no :resident-id at all"
    :request {:op :log-resident-note}
    :phase 3}])

(defn- run-scenario!
  "Runs one scenario through the compiled graph and, if it interrupts
  at `:request-approval` and the scenario carries a human decision,
  resumes the SAME thread with it. Captures exactly the ledger facts
  this scenario appended (by ledger position, so facts are attributed
  to their own run -- never joined back on `[op resident-id]`, which is
  NOT unique here: two scenarios share `:flag-safety-concern` and two
  share `:log-resident-note`)."
  [db actor {:keys [id approval request phase] :as sc}]
  (let [before      (count (store/ledger db))
        first-run   (g/run* actor {:request request :phase-num phase}
                            {:thread-id id})
        interrupted? (= :interrupted (:status first-run))
        resumed     (when (and interrupted? approval)
                      (g/run* actor {:approval approval}
                              {:thread-id id :resume? true}))
        final       (or resumed first-run)]
    (assoc sc
           :interrupted? interrupted?
           :status (:status final)
           :state (:state final)
           :facts (vec (drop before (store/ledger db))))))

(defn run-demo!
  "Runs every scenario against one freshly seeded store and one
  compiled actor. Returns `{:db .. :runs [..]}` -- `:runs` is the
  scenario list enriched with each run's real graph status, final
  state and the ledger facts it produced."
  []
  (let [db    (store/seed-db)
        actor (operation/build db)]
    {:db db
     :runs (mapv #(run-scenario! db actor %) scenarios)}))

;; ============================================================================
;; Classification -- governor refusal vs. gate/human hold
;; ============================================================================

(def ^:private approver-keys
  "Every key this repo's code can use to carry a human approver.
  Scanned at RENDER time (never assumed) so the page self-corrects if
  the store starts or stops retaining an approver."
  [:approved-by :approval-rejected-by])

(defn- approver-of [m]
  (some #(get m %) approver-keys))

(defn- hold-facts [run]
  (filter #(= :proposal-held (:op %)) (:facts run)))

(defn- governor-refusal?
  "TRUE only when the MhsCareGovernor itself returned violations. An
  intake validation hold and a human rejection both reach the same
  `:hold` node with no violations, and must NOT be counted here."
  [fact]
  (boolean (seq (:violations fact))))

(defn- governor-refusals
  "[{:run .. :fact ..}] for every HARD governor refusal in this run."
  [runs]
  (vec (for [run runs, f (hold-facts run) :when (governor-refusal? f)]
         {:run run :fact f})))

(defn- non-governor-holds
  [runs]
  (vec (for [run runs, f (hold-facts run) :when (not (governor-refusal? f))]
         {:run run :fact f})))

(defn- escalations
  [runs]
  (vec (for [run runs, f (:facts run) :when (= :proposal-escalated (:op f))]
         {:run run :fact f})))

(defn- commits
  [runs]
  (vec (for [run runs, f (:facts run) :when (= :proposal-committed (:op f))]
         {:run run :fact f})))

;; ============================================================================
;; Direct governor probes -- rules the mock advisor cannot reach
;; ============================================================================

(defn probe-rules
  "The MhsCareGovernor has three HARD rules. Two of them
  (`:resident-unverified`, scope/allowlist exclusion) are reachable end
  to end through the graph and are demonstrated above by real runs. The
  third (`:effect-not-propose`) cannot be: `mhscare.advisor/mock-advise`
  hard-codes `:effect :propose` on every arm it can return, so no
  request can make the advisor claim direct actuation. Rather than
  pretend that rule is untested or hand-write a fake row, this calls
  the repo's own `governor/out-of-scope-test-check` directly and labels
  the result as a direct probe, not a graph run."
  []
  [{:label "Advisor claims direct actuation instead of proposing"
    :proposal {:op :log-resident-note :resident-id "resident-1"
               :effect :execute :confidence 0.9}}
   {:label "Advisor drifts into physical-restraint territory"
    :proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
               :summary "Consider physical restraint overnight" :confidence 0.9}}
   {:label "Advisor drifts into end-of-life / DNR territory"
    :proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
               :summary "Discuss end-of-life options with the family" :confidence 0.9}}])

(defn- run-probes [db]
  (mapv (fn [{:keys [label proposal]}]
          {:label label
           :proposal proposal
           :result (governor/out-of-scope-test-check proposal db)})
        (probe-rules)))

;; ============================================================================
;; Rendering
;; ============================================================================

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- muted [v] (str "<span class=\"muted\">" (esc v) "</span>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" % "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows)
         (str/join "\n" rows)
         (str "        <tr><td colspan=\"" (count headers) "\">"
              (muted "no rows in this run") "</td></tr>"))
       "\n      </tbody>\n"
       "    </table>\n"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (when lede (str "    <p class=\"muted\">" lede "</p>\n"))
       body
       "  </section>\n"))

;; ----------------------------- rows -----------------------------

(defn- outcome-cell [{:keys [state status]}]
  (let [decision (:decision state)
        execution (:execution state)]
    (cond
      (= :committed execution) "<span class=\"ok\">committed</span>"
      (= :held execution)      "<span class=\"critical\">held</span>"
      (= :interrupted status)  "<span class=\"warn\">awaiting human sign-off</span>"
      :else                    (muted (kw (or decision execution "in progress"))))))

(defn- resident-rows [db runs]
  (for [r (store/all-residents db)]
    (let [rid (:resident-id r)
          rid-runs (filter #(= rid (:resident-id (:request %))) runs)]
      (row (code rid)
           (esc (:name r))
           (if (:registered? r) "<span class=\"ok\">registered</span>"
               "<span class=\"critical\">not registered</span>")
           (if (:verified? r) "<span class=\"ok\">verified</span>"
               "<span class=\"critical\">NOT verified &middot; every proposal is refused</span>")
           (str (count rid-runs))))))

(defn- run-rows [runs]
  (for [{:keys [label request phase state] :as run} runs]
    (row (esc label)
         (code (kw (:op request)))
         (if-let [rid (:resident-id request)] (code rid) (muted "(absent)"))
         (str phase " &middot; " (esc (kw (phase/phase-name phase))))
         (esc (kw (:status run)))
         (esc (kw (or (:decision state) "-")))
         (outcome-cell run))))

(defn- refusal-rows [refusals]
  (for [{:keys [run fact]} refusals
        v (:violations fact)]
    (row (esc (:label run))
         (code (kw (:op (:request run))))
         (if-let [rid (:resident-id (:request run))] (code rid) (muted "(absent)"))
         (str "<span class=\"critical\">" (esc (kw (:rule v))) "</span>")
         (esc (:detail v)))))

(defn- gate-hold-rows [holds]
  (for [{:keys [run fact]} holds]
    (row (esc (:label run))
         (code (kw (:op (:request run))))
         (cond
           (:approval-rejected-by fact) "<span class=\"warn\">human rejected the escalation</span>"
           (nil? (:proposal fact))      "<span class=\"warn\">intake validation &middot; advisor and governor never ran</span>"
           :else                        (muted "held without governor violations"))
         (if-let [by (approver-of fact)] (code by) (muted "-"))
         (muted (str "violations: "
                     (if (seq (:violations fact))
                       (str/join ", " (map #(kw (:rule %)) (:violations fact)))
                       "none"))))))

(defn- escalation-rows [runs]
  (for [{:keys [run fact]} (escalations runs)]
    (let [approval (:approval run)]
      (row (esc (:label run))
           (code (kw (:op (:proposal fact))))
           (if (:interrupted? run)
             "<span class=\"warn\">graph interrupted at :request-approval</span>"
             (muted "escalated"))
           (if approval
             (str (if (= :approved (:status approval))
                    "<span class=\"ok\">approved</span>"
                    "<span class=\"critical\">rejected</span>")
                  " &middot; " (code (:by approval)))
             (muted "unresolved in this run"))
           (outcome-cell run)))))

(defn- record-rows [db commit-pairs]
  (let [records (vec (store/coordination-log db))]
    (map-indexed
     (fn [i {:keys [fact]}]
       (let [record (get records i)
             proposal (:proposal (:record fact))]
         (row (code (kw (:op proposal)))
              (code (:resident-id proposal))
              (esc (:summary proposal))
              (esc (kw (:decision (:record fact))))
              ;; approver on the RECORD, scanned at render time
              (if-let [by (approver-of record)]
                (code by)
                (muted "not on record"))
              ;; approver on the AUDIT FACT, scanned at render time
              (if-let [by (approver-of fact)]
                (code by)
                (muted "auto-committed · no human approver")))))
     commit-pairs)))

(defn- observed-cell
  "What the op ACTUALLY did in this build's runs. Derived, not
  described -- this is where the gate column and reality can disagree,
  and when they do the disagreement is the interesting fact rather
  than something to smooth over."
  [runs op]
  (let [rs (filter #(= op (:op (:request %))) runs)]
    (if (empty? rs)
      (muted "not exercised in this run")
      (str/join " &middot; "
                (distinct
                 (for [r rs]
                   (let [refusal (first (filter governor-refusal? (hold-facts r)))]
                     (cond
                       refusal
                       (str "<span class=\"critical\">HARD refused &middot; "
                            (esc (str/join ", " (map #(kw (:rule %)) (:violations refusal))))
                            "</span>")
                       (= :committed (:execution (:state r)))
                       "<span class=\"ok\">committed</span>"
                       (= :held (:execution (:state r)))
                       "<span class=\"warn\">held (no governor violation)</span>"
                       :else (muted (kw (:status r)))))))))))

(defn- contract-rows [runs]
  (for [op (sort-by name governor/allowed-ops)]
    (row (code (kw op))
         (if (contains? governor/always-escalate-ops op)
           "<span class=\"warn\">ALWAYS escalates to a human &middot; never auto at any phase</span>"
           (let [auto-phases (sort (keep (fn [[n rules]]
                                           (when (contains? (:auto rules) op) n))
                                         phase/phase-rules))]
             (if (seq auto-phases)
               (str "<span class=\"ok\">auto-commit at phase "
                    (str/join ", " auto-phases)
                    "</span> &middot; <span class=\"warn\">human approval below that</span>")
               "<span class=\"warn\">human approval at every phase</span>")))
         (observed-cell runs op))))

(defn- phase-rows []
  (for [[n rules] (sort-by key phase/phase-rules)]
    (row (str n)
         (code (kw (:name rules)))
         (if (seq (:auto rules))
           (str/join ", " (map #(code (kw %)) (sort-by name (:auto rules))))
           (muted "nothing auto-commits · everything needs a human")))))

(defn- ledger-rows [db]
  (for [f (store/ledger db)]
    (let [proposal (or (:proposal f) (:proposal (:record f)))]
      (row (code (kw (:op f)))
           (if proposal (code (kw (:op proposal))) (muted "-"))
           (if (:resident-id proposal) (code (:resident-id proposal)) (muted "-"))
           (cond
             (seq (:violations f))
             (str "<span class=\"critical\">"
                  (esc (str/join ", " (map #(kw (:rule %)) (:violations f))))
                  "</span>")
             (approver-of f) (str "human: " (code (approver-of f)))
             (= :proposal-held (:op f)) (muted "no governor violations")
             :else (muted "-"))))))

(defn- probe-rows [probes]
  (for [{:keys [label proposal result]} probes]
    (row (esc label)
         (code (pr-str (select-keys proposal [:op :effect])))
         (if (:hard? result)
           (str "<span class=\"critical\">refused &middot; "
                (esc (str/join ", " (map #(kw (:rule %)) (:violations result))))
                "</span>")
           "<span class=\"ok\">allowed</span>")
         (esc (or (:detail (first (:violations result))) "")))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator console from a completed `run-demo!`
  result. Every table below is derived; nothing is hand-typed."
  [{:keys [db runs]}]
  (let [refusals    (governor-refusals runs)
        gate-holds  (non-governor-holds runs)
        commit-pairs (commits runs)
        probes      (run-probes db)
        records     (vec (store/coordination-log db))
        record-keeps-approver?
        (boolean (some approver-of records))
        approver-note
        (if record-keeps-approver?
          "The approver IS retained on the committed record itself."
          (str "The approver is <strong>audit only &mdash; not retained on the "
               "committed record</strong>: <code>mhscare.operation</code>'s "
               "<code>:commit</code> node builds the record as "
               "<code>{:proposal :decision :timestamp}</code> and attaches "
               "<code>:approved-by</code> only to the ledger fact. This column "
               "is scanned from the record at render time, so it fills in by "
               "itself if the store is ever fixed to retain it."))]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>cloud-itonami-isic-872 &middot; mhscare operator console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Residential care for mental health &amp; substance use (ISIC 872) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; generated at build time by running the real actor &middot; every safety concern is human-signed</span>\n"
     "</header>\n"
     "<main>\n"

     (section
      "Resident directory"
      (str "Seeded by <code>mhscare.store/demo-data</code> and read back through "
           "<code>store/all-residents</code>. A resident who is registered but not yet "
           "<em>verified</em> cannot have anything proposed about them at all — that is "
           "the governor's first HARD rule, and it is the consent/verification floor for "
           "this whole actor.")
      (table ["Resident" "Name" "Registration" "Verification" "Runs in this scenario set"]
             (resident-rows db runs)))

     (section
      "Actor runs (this build)"
      (str "Each row is one full pass of the compiled <code>langgraph</code> StateGraph from "
           "<code>mhscare.operation/build</code>, driven by <code>langgraph.graph/run*</code>. "
           "<code>interrupted</code> means the graph really stopped at "
           "<code>:request-approval</code> and waited for a human before it could continue.")
      (table ["Scenario" "Requested op" "Resident" "Rollout phase" "Graph status" "Decision" "Outcome"]
             (run-rows runs)))

     (section
      (str "HARD governor refusals &mdash; " (count refusals) " in this run")
      (str "The <strong>MhsCareGovernor</strong> said no. These are permanent and "
           "un-overridable: a HARD violation is routed straight to <code>:hold</code> and "
           "<em>never</em> offered to a human for approval. Each row is the governor's own "
           "returned <code>:violations</code> entry, verbatim.")
      (table ["Scenario" "Requested op" "Resident" "Rule" "Governor's reason"]
             (refusal-rows refusals)))

     (section
      (str "Holds that are NOT governor refusals &mdash; " (count gate-holds) " in this run")
      (str "The same <code>:hold</code> node is also reached without any governor violation: "
           "by intake validation (a malformed request never reaches the advisor or the "
           "governor at all) and by a human rejecting an escalation. These carry an empty "
           "<code>:violations</code> vector and are listed separately so a hold count can "
           "never be mistaken for a governor refusal.")
      (table ["Scenario" "Requested op" "Why it held" "Human" "Governor violations"]
             (gate-hold-rows gate-holds)))

     (section
      "Human-in-the-loop escalations"
      (str "Reached through the graph's real <code>:escalate</code> node and its "
           "<code>interrupt-before #{:request-approval}</code> checkpoint. Two different "
           "reasons appear below: <code>:flag-safety-concern</code> escalates at every "
           "phase by rule, and an otherwise-clean op escalates because the rollout phase "
           "does not yet permit it to auto-commit. Approvals and rejections resume the "
           "same checkpointed thread.")
      (table ["Scenario" "Proposal op" "Interrupt" "Human decision" "Final outcome"]
             (escalation-rows runs)))

     (section
      (str "Committed coordination records &mdash; " (count records) " in this run")
      (str "Written by <code>store/commit-record!</code> on the graph's <code>:commit</code> "
           "node. " approver-note)
      (table ["Op" "Resident" "Summary" "Decision" "Approver on record" "Approver in audit ledger"]
             (record-rows db commit-pairs)))

     (section
      "Closed op contract (MhsCareGovernor)"
      (str "The gate column is rendered from <code>governor/allowed-ops</code>, "
           "<code>governor/always-escalate-ops</code> and <code>phase/phase-rules</code> — "
           "the actual vars, not a description of them. Anything outside this allowlist is "
           "refused as <code>:op-not-allowed</code>. The last column is what the op actually "
           "did in this build, so the two can visibly disagree: an op the phase gate would "
           "auto-commit is still refused outright if the governor refuses it first.")
      (table ["Op" "Gate" "Observed in this build"]
             (contract-rows runs)))

     (section
      "Rollout phase gate"
      (str "From <code>mhscare.phase/phase-rules</code>. The phase gate can only ever "
           "<em>narrow</em> what auto-commits; it cannot approve anything the governor "
           "refused.")
      (table ["Phase" "Name" "Auto-commits"]
             (phase-rows)))

     (section
      "Direct governor probes (not graph runs)"
      (str "Two of the governor's three HARD rules are demonstrated above by real graph "
           "runs. The third, <code>:effect-not-propose</code>, is unreachable through the "
           "graph because <code>mhscare.advisor/mock-advise</code> hard-codes "
           "<code>:effect :propose</code> on every arm — so it is probed here directly via "
           "this repo's own <code>governor/out-of-scope-test-check</code>, and labelled as "
           "a probe rather than dressed up as a run.")
      (table ["Probe" "Proposal" "Governor" "Reason"]
             (probe-rows probes)))

     (section
      (str "Audit ledger &mdash; " (count (store/ledger db)) " facts in this run")
      (str "<code>mhscare.store/ledger</code>, append-only, in order. Timestamps are "
           "deliberately not rendered so this page is byte-identical across rebuilds.")
      (table ["Fact" "Proposal op" "Resident" "Basis"]
             (ledger-rows db)))

     "</main>\n"
     "<footer>\n"
     "  <p>Generated by <code>clojure -M:render-html</code> "
     "(<code>src/mhscare/render_html.clj</code>) by running the real actor. "
     "No row on this page was written by hand.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out       (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as demo} (run-demo!)
        refusals  (governor-refusals runs)
        kinds     (sort (distinct (for [{:keys [fact]} refusals
                                        v (:violations fact)]
                                    (name (:rule v)))))]
    ;; Build-time invariant, not a convention: a console that cannot show
    ;; the governor actually refusing something is not evidence that the
    ;; governor works. Holds WITHOUT violations (intake validation, human
    ;; rejection) deliberately do not count.
    (when (empty? refusals)
      (throw (ex-info (str "Refusing to write " out
                           ": the demo run produced ZERO HARD governor refusals. "
                           "Every hold in this run had an empty :violations vector, "
                           "which means the governor never actually refused anything.")
                      {:out out
                       :runs (count runs)
                       :ledger-facts (count (store/ledger db))
                       :holds (count (non-governor-holds runs))})))
    (let [html (render demo)]
      (spit out html)
      (println "wrote" out
               (str "(" (count html) " bytes, "
                    (count runs) " actor runs, "
                    (count (store/ledger db)) " ledger facts, "
                    (count refusals) " HARD governor refusals ["
                    (str/join ", " kinds) "], "
                    (count (non-governor-holds runs)) " non-governor holds, "
                    (count (store/coordination-log db)) " committed records)")))))
