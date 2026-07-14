(ns newswire.advisor
  "Wire Advisor client -- the *contained intelligence node* for the
  news-agency wire-service actor (ISIC 6391).

  It normalizes story intake, drafts a sourcing/verification checklist,
  screens a story for legally-sensitive subject matter (ongoing
  litigation, unverified criminal allegations, etc.), drafts the
  distribution action, and drafts the correction/retraction action.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal*, NEVER a committed record or a real push onto the
  subscriber wire. `:effect` is ALWAYS the literal `:propose` --
  a fixed invariant `newswire.governor`'s `no-actuation-violations`
  independently re-checks on every proposal (mirroring
  `cloud-itonami-isco-3521`'s `media.advisor`, this fleet's closest
  domain analog, rather than the telecom siblings' shape, where the
  specific SSoT-mutation kind and the propose-marker are the same
  field) -- the actor itself never pushes content to the wire; it only
  proposes to. `:action` names WHICH specific SSoT mutation a governor-
  cleared commit would apply. Every output is censored downstream by
  `newswire.governor` before anything touches the SSoT, and
  `:actuation/issue-correction` proposals NEVER auto-commit at any
  phase (`:actuation/distribute` may auto-commit at phase 3, but only
  when clean AND not legally-sensitive -- see README `Actuation` and
  this repo's own `docs/adr/0001-architecture.md`).

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by governor checks
     :cites      [kw|str ..]    ; sources/evidence the LLM used
     :effect     :propose       ; FIXED invariant, never anything else
     :action     kw             ; the specific SSoT mutation a commit
                                 ; would apply (:story/upsert |
                                 ; :verification/set |
                                 ; :sensitivity-screen/set |
                                 ; :story/mark-distributed |
                                 ; :story/mark-retracted)
     :value      map
     :stake      kw|nil         ; :actuation/distribute | :actuation/issue-correction | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [newswire.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the story, its headline or its embargo timestamp.
  High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "ストーリー記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :propose
   :action     :story/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- verify-source
  "Sourcing/verification checklist draft -- did this story's own draft
  actually cite an identifiable, checkable source (a named person, a
  named organization/document, an on-the-record attribution)? A story
  with NO citeable source is the failure mode this actor must defend
  against: the Wire Governor must reject distributing it (see
  `newswire.governor`'s `source-not-verified-violations`)."
  [db {:keys [subject sources]}]
  (let [st (store/story db subject)
        have (vec (or sources []))]
    (if (empty? have)
      {:summary    (str subject " の引用可能な情報源が確認できません")
       :rationale  "情報源のない配信は不可。要件を推測で作らない。"
       :cites      []
       :effect     :propose
       :action     :verification/set
       :value      {:story-id subject :checklist [] :sourced? false}
       :stake      nil
       :confidence 0.9}
      {:summary    (str (:headline st) " 向け情報源 " (count have) " 件を確認")
       :rationale  (str "引用元: " (str/join ", " have))
       :cites      have
       :effect     :propose
       :action     :verification/set
       :value      {:story-id subject :checklist have :sourced? true}
       :stake      nil
       :confidence 0.9})))

(defn- screen-sensitivity
  "Legal-sensitivity screening draft (ongoing litigation, unverified
  criminal allegations, or similar). `:legally-sensitive?` on the story
  record injects the failure mode: the Wire Governor must ESCALATE
  (never silently auto-publish) any story this screening flags, or any
  story already flagged on file -- see `newswire.governor`'s
  `legally-sensitive-violations`. Unlike an embargo or sourcing
  violation, this is NOT an absolute block: a human editor may sign off
  and clear it for distribution."
  [db {:keys [subject]}]
  (let [st (store/story db subject)]
    (cond
      (nil? st)
      {:summary "対象ストーリー記録が見つかりません" :rationale "no story record"
       :cites [] :effect :propose :action :sensitivity-screen/set
       :value {:story-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (true? (:legally-sensitive? st))
      {:summary    (str (:headline st) ": 法的リスクのある題材を検出（訴訟中/未確認の刑事容疑等）")
       :rationale  "スクリーニングが法的リスクのある題材を検出。配信前に編集責任者の人手承認が必須。"
       :cites      [:sensitivity-check]
       :effect     :propose
       :action     :sensitivity-screen/set
       :value      {:story-id subject :verdict :sensitive}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:headline st) ": 法的リスクのある題材は検出されませんでした")
       :rationale  "法的リスクスクリーニング完了。"
       :cites      [:sensitivity-check]
       :effect     :propose
       :action     :sensitivity-screen/set
       :value      {:story-id subject :verdict :clear}
       :stake      nil
       :confidence 0.9})))

(defn- propose-distribution
  "Draft the actual DISTRIBUTION action -- pushing a story onto the real
  subscriber wire. ALWAYS `:effect :propose` (the actor never itself
  pushes to the wire -- see ns docstring) and ALWAYS `:stake
  :actuation/distribute` -- this is a REAL-WORLD act. Unlike
  `:actuation/issue-correction`, a clean, non-sensitive distribution
  MAY auto-commit at phase 3 (`newswire.phase`); the Wire Governor
  independently HARD-gates on sourcing completeness and the embargo
  instant, and ESCALATE-gates on a legally-sensitive flag, regardless
  of phase."
  [db {:keys [subject]}]
  (let [st (store/story db subject)]
    {:summary    (str subject " 向け配信提案"
                      (when st (str " (story=" (:headline st) ")")))
     :rationale  (if st
                   (str "embargo-until=" (:embargo-until st)
                        " legally-sensitive?=" (:legally-sensitive? st))
                   "ストーリー記録が見つかりません")
     :cites      (if st [subject] [])
     :effect     :propose
     :action     :story/mark-distributed
     :value      {:story-id subject}
     :stake      :actuation/distribute
     :confidence (if st 0.9 0.3)}))

(defn- propose-correction
  "Draft the actual CORRECTION/RETRACTION action -- issuing a correction
  or retraction notice for a story this bureau has ALREADY distributed.
  ALWAYS `:effect :propose` and ALWAYS `:stake :actuation/issue-
  correction` -- this is a REAL-WORLD act, and unlike
  `:actuation/distribute`, NEVER auto-commits at ANY phase (see README
  `Actuation` and this repo's own `docs/adr/0001-architecture.md`): the
  actor never silently overwrites what it already distributed, so this
  is a distinct, always-human-signoff action."
  [db {:keys [subject kind] :or {kind :correction}}]
  (let [st (store/story db subject)]
    {:summary    (str subject " 向け" (if (= kind :retraction) "撤回" "訂正") "提案"
                      (when st (str " (story=" (:headline st) ")")))
     :rationale  (if st
                   (str "distributed?=" (:distributed? st) " retracted?=" (:retracted? st))
                   "ストーリー記録が見つかりません")
     :cites      (if st [subject] [])
     :effect     :propose
     :action     :story/mark-retracted
     :value      {:story-id subject :kind kind}
     :stake      :actuation/issue-correction
     :confidence (if st 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :story/intake                (normalize-intake db request)
    :source/verify               (verify-source db request)
    :sensitivity/screen          (screen-sensitivity db request)
    :actuation/distribute        (propose-distribution db request)
    :actuation/issue-correction  (propose-correction db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :propose :action :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたはニュース通信社の配信・訂正/撤回エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :effect(常に :propose) "
       ":action(:story/upsert|:verification/set|:sensitivity-screen/set|"
       ":story/mark-distributed|:story/mark-retracted) "
       ":stake(:actuation/distribute か :actuation/issue-correction か nil) :confidence(0..1)。\n"
       "重要: 情報源のないストーリーの配信を絶対に提案してはいけません。"
       "embargo-until が未到来のストーリーの配信も絶対に提案してはいけません。"
       ":effect は必ず :propose のみ — 実配線は行わない。"))

(defn- facts-for [st {:keys [subject]}]
  {:story (store/story st subject)})

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Wire Governor
  escalates/holds -- an LLM hiccup can never auto-distribute a story or
  auto-issue a correction."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (assoc :effect :propose)
          (update :action #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :propose :action :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
