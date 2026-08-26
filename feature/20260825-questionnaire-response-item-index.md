# Questionnaire Response Item Index

| Field | Value |
|-------|-------|
| **Status** | Implemented |
| **Repos** | `android-fhir` (`datacapture/`), consumed by openSRP FHIRCore Android (`android/`) |
| **Branch** | `perf/questionnaire-expression-evaluation`, off `release-22.07.2026` |
| **Scope** | Pure performance. No change to evaluation results, to enablement, or to any public API. |

Valid status values: `Draft` → `Approved` → `Implemented` → `Superseded`.

Follows [Questionnaire Expression Evaluation Performance](20260824-questionnaire-expression-evaluation-performance.md),
which made each evaluation cheap to *set up*. This one makes the evaluation itself cheap: it removes
the search that nearly every SDC expression performs.

---

## Part I — Business spec

### 1. Problem

The canonical way an SDC expression reads another question's answer is

```
%resource.repeat(item).where(linkId='a-birthdate').answer.value
```

and `repeat(item)` is expensive out of all proportion to the size of the response. From
`FHIRPathEngine.funcRepeat` (`org.hl7.fhir.r4:6.0.22`):

```java
for (Base b : added) {
  boolean isnew = true;
  for (Base t : result) {          // every item already collected
    if (b.equalsDeep(t)) {         // recursive deep comparison of whole subtrees
      isnew = false;
    }
  }
  ...
```

So a single evaluation is quadratic in the number of response items and recursive in their depth,
and every expression pays it in full. Rendering evaluates the expressions of every item on every
emission, so the questionnaire as a whole is *cubic-ish* in item count, which is what makes large
questionnaires with many expressions unusable rather than merely slow.

The `where` that follows is a second, smaller cost of the same shape: the condition is executed once
per item the search reached, i.e. once per item in the response, per expression.

`enableWhen` — the non-FHIRPath form — has the same problem in miniature.
`EnablementEvaluator.findEnableWhenQuestionnaireResponseItem` located the question by
`questionnaireResponseItemPreOrderList.indexOf(origin)` followed by a backward and a forward scan of
that list, so each constraint of each item scanned the whole response up to three times.

### 2. Goal

- Evaluate `%resource.repeat(item)` **once per state computation** instead of once per expression.
- Evaluate `%resource.item` **once per state computation** as well, so the same lookup works for
  expressions that navigate the item tree without `repeat()`.
- Turn `where(linkId = 'x')` into a lookup instead of a filter over every item.
- Make the `enableWhen` question lookup a binary search instead of two linear scans.
- Change nothing observable: the same items, in the same order, with the same de-duplication, and
  the same choice of `enableWhen` occurrence.

### 3. Decisions

- **The index is built from the engine's own `repeat(item)` output, never from a hand written
  traversal of the response.** `repeat(item)` has three observable properties that a traversal would
  have to reproduce and keep reproducing as HAPI changes; see §4.1. Asking the engine once and
  grouping the answer by link ID inherits all three by construction.
- **Only the search is replaced. Everything the expression does with the result — the `where`
  condition included — is left verbatim and still evaluated by the engine.** The rewrite is
  therefore equivalent as long as the constant resolves to what `%resource.repeat(item)` returns, and
  no FHIRPath semantics are reimplemented.
- **Narrowing to one link ID happens only when the condition makes that link ID mandatory.** A top
  level `or`, `xor`, `implies` or `|` means the condition says nothing about link IDs, and the search
  then stands for every item. Anything unrecognised falls back the same way, so the analysis can only
  cost speed, not correctness (§4.2).
- **Reuse the `QuestionnaireExpressionCache` lifetime rather than inventing a second one.** The index
  reflects the answers, not only the structure, because de-duplication compares content. It is
  therefore valid exactly where memoized results are: for the span of one state computation, with the
  same `invalidate()` calls covering the two mid-computation answer edits.
- **`enableWhen` gets a separate index.** It needs the nearest *ancestor*, then *preceding*, then
  *following* occurrence, and unlike `repeat(item)` it does reach items nested under `answer.item`.
  Sharing one index between the two would mean giving one of them the wrong item set.

---

## Part II — Technical spec

### 4. Where it lives

| Area | Location |
|------|----------|
| The index | `datacapture/.../fhirpath/QuestionnaireResponseItemIndex.kt` (new) — two access modes, each built lazily from the engine: **leaf** (`%resource.repeat(item)`, flattened, de-duplicated) and **tree** (`%resource.item`, direct children only). `items(access, linkId)` looks up by link ID in that mode. |
| Index lifetime | `datacapture/.../fhirpath/QuestionnaireExpressionCache.kt` — `responseItemIndex(questionnaireResponse)` builds it on first use and hands it out only while `isActive`; `invalidate()` drops it. |
| Rewrite | `datacapture/.../fhirpath/IndexedItemSearchExpression.kt` (new) — `indexedItemSearchExpression(expression)`, LRU cached (`MAX_CACHED_INDEXED_EXPRESSIONS = 512`) including negative results, since a rewrite depends on the expression text alone. |
| Evaluation | `datacapture/.../fhirpath/ExpressionEvaluator.kt` — `evaluateWithIndexedItemSearches`, used by `evaluateExpression` and by the FHIRPath branch of `evaluateVariable`. |
| Constant plumbing | `datacapture/.../fhirpath/FhirPathUtil.kt` — `evaluateToBase(…, itemCollections)` and `appContextOf`; `datacapture/.../fhirpath/FHIRPathEngineHostServices.kt` — `resolveConstant` now also resolves a list valued entry. |
| `enableWhen` lookup | `datacapture/.../enablement/EnablementEvaluator.kt` — `questionnaireResponseItemPreOrderIndexMap` and `questionnaireResponseItemPreOrderIndicesByLinkId`, both built in the existing `init`, consumed by `findEnableWhenQuestionnaireResponseItem` through one `binarySearch`. |

What the rewrite does, given `%resource.repeat(item).where(linkId='a' and answer.empty().not()).select(answer.value)`:

```
%sdcRepeatItems0.where(linkId='a' and answer.empty().not()).select(answer.value)
   with sdcRepeatItems0 -> index.itemsWithLinkId("a")
```

and given a condition it cannot read, `sdcRepeatItems0 -> index.items(Leaf, linkId = null)`.
`%resource.item.where(linkId='a')` becomes `%sdcTreeItems0.where(linkId='a')` with
`sdcTreeItems0 -> index.items(Tree, "a")`. The constants are
generated (`sdcRepeatItems0`, `sdcRepeatItems1`, …) rather than derived from the link ID because a
`%` constant only lexes as plain alphanumerics — `%sdc_items` is a parse error — while real link IDs
routinely contain `_`, `.` and `-`. An expression that mentions the prefix itself is left alone
entirely, so a questionnaire variable of that name can never be shadowed.

### 4.1 Why the index must come from the engine

Verified against `org.hl7.fhir.r4:6.0.22` with a standalone harness driving the same
`FHIRPathEngine` the library uses:

1. **`repeat(item)` does not reach items nested under `answer.item`.** It projects `item` from items
   only, and answers are not items, so
   `%resource.repeat(item).where(linkId='nested').answer.value` on a response whose `nested` item
   sits under an answer returns **nothing**. This library stores questions with nested items and
   repeated group instances exactly there (`QuestionnaireViewModel.kt:1192`,
   `MoreQuestionnaireItemComponents.kt:972`), so an index that walked `answer.item` would start
   finding items the expressions cannot currently see. See §7.
2. **It drops an item that is `equalsDeep` to one already collected.** Two instances of a repeated
   group with identical content count **once**:
   `…where(linkId='c').count()` returns `1`, not `2`.
3. **It returns items breadth first**, which `first()`, `last()` and `select()` expose.

Grouping the engine's own result by link ID preserves all three. De-duplication in particular is not
weakened by grouping: two items can only be `equalsDeep` if their link IDs are equal too, so every
comparison `funcRepeat` would have made across the whole response is a comparison within one group.

### 4.2 When a search may be narrowed to one link ID

`mandatoryLinkId` reads the `where` condition as a top level conjunction and accepts it only if one
term is exactly a link ID equality, `linkId = 'x'` or `'x' = linkId`. Then every item the condition
could select has that link ID, so evaluating the same condition over that group instead of over
every item cannot change the result.

Everything else falls back to the whole item set:

- a top level `or`, `xor`, `implies` or `|` — `linkId='a' implies …` is true for items whose link ID
  is *not* `a`, and `and` binds tighter than `or`, so `linkId='a' and x or y` selects on `y` alone;
- a bracketed disjunction, `(linkId='a' or linkId='b') and …`;
- a negated or partial comparison, `linkId != 'a'`, `linkId.startsWith('a')`, `not(linkId='a')`;
- a link ID literal carrying an escape sequence, which would have to be unescaped to be compared;
- no `where` at all, `%resource.repeat(item).linkId`.

The scan that finds the top level operators skips string literals, parentheses and indexers, so a
condition nesting `or` inside a term (`item.where(linkId='x' or linkId='y').exists() and linkId='a'`)
is still narrowed on `linkId='a'`, and a `%resource.repeat(item)` occurring *inside* a string literal
is not rewritten at all.

Every rewritten expression is parsed once, at rewrite time. A rewrite that does not parse is
discarded and the original expression is evaluated, so a rewriting bug degrades to the old speed
rather than to a broken questionnaire.

### 4.3 Why a list valued constant is safe

`FHIRPathEngine.IEvaluationContext.resolveConstant` already returns `List<Base>`, and the engine
feeds that list into the rest of the path exactly as it feeds the result of `repeat(item)`. Confirmed
with the harness: `%items.answer.value` over a two element constant yields two values, while
`%items.answer.first().value` yields **one** — the tail operates on the whole collection, not on each
element, which is precisely the semantics that a per item evaluation followed by concatenation would
have got wrong.

`resolveConstant` returns a fresh list, because the engine treats what it is given as its own working
collection.

### 5. Cost model

Per **state computation**, with *n* response items and *m* expressions:

| | Before | After |
|---|---|---|
| `%resource.repeat(item)` | *m* evaluations, each O(*n*²) deep comparisons | 1 evaluation, plus one `groupBy` |
| `where(linkId = 'x')` | *m* × *n* condition executions | *m* × (items with that link ID) |
| `enableWhen` question lookup | O(*n*) `indexOf` + two O(*n*) scans per constraint | O(1) map lookup + O(log k) binary search |

Measured with the harness on a desktop JVM, 410 response items and 400 expressions of the
`…where(linkId='…' and answer.empty().not()).select(answer.value)` shape (Android is slower, and the
relative figures are what matter):

| variant | 400 expressions |
|---|---|
| `%resource.repeat(item).where(…)` | 690–1200 ms |
| constant standing for every item, condition unchanged | 21–39 ms |
| constant standing for the items of one link ID | 0.5 ms |
| building the index (one warm `repeat(item)` + `groupBy`) | 1.7–2.9 ms |

So roughly **30×** from the search alone, and **300×** when the link ID can be read off the
condition.

### 6. Behavior changes

None intended, and none found. The index reproduces `repeat(item)` exactly (§4.1), the `where`
condition still runs (§4.2), and the `enableWhen` lookup returns the same occurrence as the two scans
it replaces, including the two edge cases the old code had: the origin itself is excluded from both
axes, and an origin that is not in the pre-order list at all falls through to the first occurrence
anywhere.

### 7. Discovered defect, deliberately not fixed here

**Items nested under `answer.item` are invisible to every `%resource.repeat(item)` expression**
(§4.1.1). A question with nested items, and every instance of a repeated group, stores its children
there, so `enableWhenExpression`, `calculatedExpression` and friends cannot read any answer inside a
repeated group. `enableWhen` *can* — `QuestionnaireResponse.allItems` walks `answer.item` — so the
two mechanisms disagree about what the response contains.

That is a pre-existing bug in what the expressions are handed, not in how fast they run, and fixing
it means changing results. It needs its own proposal — most likely making the expressions evaluate
against a response whose items are reachable by `item`, or supplying a supplement that spans both.
This change deliberately preserves the current behaviour and pins it with a test.

### 8. Non-goals

- **`%questionnaire.repeat(item).where(linkId = …)`** — the same rewrite applies and the questionnaire
  never changes while it is rendered, so its index could live for the whole session instead of one
  computation. Left out to keep this change to one index with one lifetime.
- **Other search shapes** — `%resource.item.where(…)`, `descendants()`, `children()`. Not rewritten;
  they are rare in authored content and each needs its own equivalence argument.
- **Using the index outside a state computation.** Calculated expressions are evaluated between
  computations, where nothing invalidates an index. They keep searching the response.
- Everything listed as out of scope in the previous note (double `modificationCount` bump, whole-tree
  evaluation, the per-answer `isReferenced` scan) is still out of scope.

### 9. Tests

`datacapture/src/test/.../fhirpath/IndexedItemSearchExpressionTest.kt` (new, 16 tests) — the rewrite:
the simple search, the condition kept verbatim, reversed equality, whitespace in both the search and
the condition, a link ID found in any term of a conjunction, and the fallbacks of §4.2 (`or`,
`implies`, bracketed disjunction, `!=`, no `where`, escaped literal). Plus: every search in an
expression is replaced, a search inside a string literal is not, an expression mentioning the
constant prefix is left alone, and the rewrite is cached.

`datacapture/src/test/.../fhirpath/IndexedItemSearchEvaluationTest.kt` (new, 10 tests) — evaluation
with the index pinned to evaluation without it. Each test asserts both paths agree *and* what the
result is, over a response built to contain what the two could differ on: two instances of a repeated
group with identical content (de-duplication, `count()` is 1), two with different content (2), an item
nested under an answer (unreachable, §7), a non-distributive tail (`answer.first().value`), a top
level `or`, a conjunction, two searches in one expression, an unfiltered search (breadth first order),
and a condition the index cannot narrow. One further test proves the index is genuinely in the path,
by observing that an item added to the response mid-computation is not seen until `invalidate()`.

`datacapture/src/test/.../enablement/EnablementEvaluatorTest.kt` (4 new tests) — the occurrence the
`enableWhen` lookup picks: nearest ancestor over nearest preceding, nearest preceding over following,
following when nothing precedes, and another occurrence when the question is the origin's own link
ID.

`datacapture/src/test/.../fhirpath/FHIRPathEngineHostServicesTest.kt` (2 new tests) — a list valued
constant resolves to every element, as a copy.

### 10. Verification

- `:datacapture:compileDebugKotlin` — BUILD SUCCESSFUL.
- `:datacapture:spotlessCheck` — the files this change touches are clean; the seven violations that
  remain are the pre-existing ones in files it does not touch.
- `:datacapture:testDebugUnitTest` for `fhirpath.*` and `enablement.*` — **119 tests, 0 failures**
  (`ExpressionEvaluatorTest` 34, `EnablementEvaluatorTest` 43, `IndexedItemSearchExpressionTest` 16,
  `IndexedItemSearchEvaluationTest` 10, `FHIRPathEngineHostServicesTest` 14, `FhirPathUtilTest` 3).
- Full `:datacapture:testDebugUnitTest` — **1064 tests, 41 failures, all pre-existing**. Established
  by recording the failing test names, stashing this change, re-running `QuestionnaireViewModelTest`
  on the branch head and diffing: the two sets are identical, name for name. Those 41 are the
  failures the previous note documents (`EXTRA_SHOW_CANCEL_BUTTON…`, pagination, review button); no
  other class fails. (`enabledAnswerOptions should toggle options in answerOptionsToggleExpression
  occurrence when answers to depended question change` passed on one of four runs and failed on the
  other three, with and without this change: it is flaky, not fixed.)
- The behaviour of `funcRepeat`, and of a list valued constant, was established by running the exact
  `FHIRPathEngine` build the library depends on outside the project rather than by reading its source
  only.

### 11. Open items

- Measure on a real large questionnaire, through openSRP FHIRCore, as in the previous note.
- Decide whether §7 is filed as a bug against the SDK or handled by the consumer.
- Consider extending the rewrite to `%questionnaire.repeat(item)` (§8), which is strictly simpler
  because the questionnaire is immutable while it is rendered.
- Port to `datacapture-kmp`, together with the previous change.
