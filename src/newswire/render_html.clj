(ns newswire.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 6): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`newswire.actor/build-graph` -> `newswire.governor` -> `newswire.store`)
  through a scenario built from this repo's own seeded demo data
  (`newswire.store/seed-db`, client `bureau-1`, stories story-1..story-4)
  and renders the result deterministically -- no invented numbers, no
  timestamps in the page content, byte-identical across reruns against
  the same seed (verified by diffing two consecutive runs).

  LAYOUT NOTE: unlike the isic-6820/isic-851 siblings this template was
  extracted from, this repo has no `operation.cljc` -- the
  `langgraph.graph` StateGraph lives in `newswire.actor`'s `build-graph`,
  which already exposes `run-request!`/`approve!` wrapping `g/run*`. This
  namespace uses those two functions directly as the harness instead of
  redefining local `exec!`/`approve!` -- same behavior, `graph` passed
  explicitly rather than closed over.

  TRAP CHECK (per `90-docs/business/cloud-itonami-flagship-generator-
  template.edn`'s documented isic-851 trap): this repo's own
  `newswire.sim` (`clojure -M:run`) was actually run before writing this
  scenario, not assumed correct. Unlike isic-851's broken sim, this
  repo's `newswire.sim` drives real seeded ids (`story-1`..`story-4`
  under `bureau-1`) and produces the exact dispositions its own comments
  claim (auto-commit / always-escalate / three distinct HARD holds) --
  confirmed correct, and this file's scenario below mirrors it (trimmed
  to a representative subset, same discipline as the isic-6820 realty
  render_html.clj).

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [newswire.store :as store]
            [newswire.actor :as actor]))

(def ^:private editor
  {:actor-id "op-1" :actor-role :wire-editor :phase 3 :now "2020-01-01T00:00:00Z"})

(def ^:private cid "bureau-1")

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: story-1 clears intake (auto-commit, phase-3
  clean), source verification and sensitivity screening (BOTH always
  escalate -- not in the phase-3 :auto set -- approved), then
  distribution (auto-commit: clean, sourced, unembargoed, non-sensitive),
  then a correction (ALWAYS escalates -- `:actuation/issue-correction`
  never auto-commits at any phase -- approved); story-2 HARD-holds a
  distribution attempt on `:embargo-violated` (embargo-until is far in
  the future); story-3 HARD-holds a distribution attempt on
  `:source-not-verified` (no verification record on file); story-1
  HARD-holds a SECOND distribution attempt on `:already-distributed`.
  Every HARD hold never reaches a human. Returns the resulting store --
  every field read by `render` below is real governor/store output, not
  a hand-typed copy."
  []
  (let [db (store/seed-db)
        graph (actor/build-graph db)
        exec! (fn [tid request]
                (actor/run-request! graph (assoc request :client-id cid) editor tid))
        approve! (fn [tid]
                   (actor/approve! graph tid {:status :approved :by "op-1"}))]

    (exec! "s1-intake" {:op :story/intake :subject "story-1"
                         :patch {:story-id "story-1"
                                 :headline "Clean, unembargoed, non-sensitive story"}})

    (exec! "s1-verify" {:op :source/verify :subject "story-1"
                         :sources ["on-the-record spokesperson"]})
    (approve! "s1-verify")

    (exec! "s1-screen" {:op :sensitivity/screen :subject "story-1"})
    (approve! "s1-screen")

    (exec! "s1-distribute" {:op :actuation/distribute :subject "story-1"})

    (exec! "s1-correct" {:op :actuation/issue-correction :subject "story-1" :kind :correction})
    (approve! "s1-correct")

    (exec! "s2-verify" {:op :source/verify :subject "story-2" :sources ["wire copy"]})
    (approve! "s2-verify")
    (exec! "s2-distribute" {:op :actuation/distribute :subject "story-2"})

    (exec! "s3-distribute" {:op :actuation/distribute :subject "story-3"})

    (exec! "s1-distribute-again" {:op :actuation/distribute :subject "story-1"})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger story-id]
  (last (filter #(= (:subject %) story-id) ledger)))

(defn- status-cell [ledger story-id]
  (let [f (last-fact-for ledger story-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- story-row [ledger {:keys [story-id headline embargo-until legally-sensitive? distributed? retracted?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc story-id) (esc headline)
          (if embargo-until (esc embargo-until) "<span class=\"muted\">none</span>")
          (if legally-sensitive? "<span class=\"warn\">flagged</span>" "<span class=\"ok\">clear</span>")
          (cond retracted? "<span class=\"err\">retracted</span>"
                distributed? "<span class=\"ok\">distributed</span>"
                :else "<span class=\"muted\">not yet</span>")
          (status-cell ledger story-id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README `Ops`
  ;; table, `newswire.governor`/`newswire.phase`) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:story/intake</code></td><td><span class=\"ok\">phase-3 auto-commit when clean</span></td></tr>"
   "        <tr><td><code>:source/verify</code></td><td><span class=\"warn\">ALWAYS human approval &middot; not in any phase's auto set</span></td></tr>"
   "        <tr><td><code>:sensitivity/screen</code></td><td><span class=\"warn\">ALWAYS human approval &middot; not in any phase's auto set</span></td></tr>"
   "        <tr><td><code>:actuation/distribute</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, sourced, unembargoed, non-sensitive</span></td></tr>"
   "        <tr><td><code>:actuation/issue-correction</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto, any phase</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        stories (store/all-stories db cid)
        story-rows (str/join "\n" (map (partial story-row ledger) stories))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-6391 &middot; news-agency wire-service</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>News-agency wire-service (ISIC 6391) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · the actor never pushes directly to the wire</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Stories (client bureau-1)</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>newswire.store</code> via <code>newswire.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Story</th><th>Headline</th><th>Embargo until</th><th>Legal sensitivity</th><th>Distribution</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     story-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Wire Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Sourcing completeness and the embargo instant are independently recomputed from the story's own ground-truth fields, never trusted from the advisor's proposal alone.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/distribution-history db)) "distributions,"
             (count (store/correction-history db)) "corrections )")))
