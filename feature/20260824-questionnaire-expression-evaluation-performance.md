# Questionnaire Expression Evaluation Performance

| Field | Value |
|-------|-------|
| **Status** | Implemented |
| **Repos** | `android-fhir` (`datacapture/`), consumed by openSRP FHIRCore Android (`android/`) |
| **Branch** | `perf/questionnaire-expression-evaluation`, off `release-22.07.2026` |
| **Scope** | Pure performance. No change to when the UI updates, to evaluation results, or to any public API. |

Valid status values: `Draft` → `Approved` → `Implemented` → `Superseded`.

---

## Part I — Business spec

### 1. Problem

Rendering and answering a large questionnaire is slow, and the slowness grows with the number of
items rather than with the number of items the user can see.

The SDC state pipeline recomputes everything on every change. `QuestionnaireViewModel`'s state is

```kotlin
combine(modificationCount, currentPageIndexFlow, isInReviewModeFlow) { _, _, _ -> getQuestionnaireState() }
```

(`QuestionnaireViewModel.kt:622`), and `getQuestionnaireState()` walks the **whole** item tree —
only a *paginated* questionnaire narrows to the current page (`QuestionnaireViewModel.kt:757-774`).
For each item on each emission it evaluates `enableWhen`/`enableWhenExpression`, the text
`cqf-expression`, `answerExpression`, and every `answerOptionsToggleExpression`
(`getQuestionnaireAdapterItems`, `QuestionnaireViewModel.kt:947-995`).

That design is not what this change addresses. What it addresses is that **three separate things
inside that loop were recomputed from scratch on every single evaluation**, none of which can ever
change while a questionnaire is being answered:

1. **Every FHIRPath expression was re-parsed on every evaluation.** `FhirPathUtil` held a single
   shared `FHIRPathEngine` (good) but always handed it the expression *string*: the `String`
   overloads of `FHIRPathEngine.evaluate` call `parse()` internally, and `evaluateToBoolean` called
   `fhirPathEngine.parse(expression)` explicitly. A questionnaire evaluates the same fixed set of
   expressions over and over — evaluations scale with (items × answers), distinct expressions do
   not — so parsing dominated as questionnaires grew.

2. **`isExpressionReferencedBy` compiled a regular expression per call.**

   ```kotlin
   item.expressionBasedExtensions.any {
     it.castToExpression(it.value).expression
       .replace(" ", "")
       .contains(Regex(".*linkId='${this.linkId}'.*"))   // compiled every call
   }
   ```

   (`MoreQuestionnaireItemComponents.kt:739-762`, pre-change). This is the dependency test used by
   the calculated-expression machinery *and* by `QuestionnaireViewModel`'s per-answer
   `isReferenced` scan (`QuestionnaireViewModel.kt:396-408`), so a `Regex` was compiled per
   expression-based extension, per candidate item, per answer.

3. **Calculated-expression dependencies were re-derived on every answer.**
   `evaluateAllAffectedCalculatedExpressions` re-flattened the entire questionnaire and re-ran both
   the reference test and `findDependentVariables` for each calculable item, every time an answer
   changed. `detectExpressionCyclicDependency`, run once per load, compared **every pair** of
   calculable items with two of the regex compilations above per pair — O(n²) regex compiles before
   the first frame.

None of the inputs to 1–3 change while a questionnaire is open: the expressions and the link IDs
they reference are fixed for the lifetime of the rendered `Questionnaire`.

### 2. Goal

- Parse each distinct FHIRPath expression **once**, not once per evaluation.
- Extract the link IDs an element's expressions reference **once**, with one compiled regex, and
  make the reference test a set lookup.
- Derive each calculable item's dependencies **once per questionnaire**, and make cycle detection
  walk actual reference edges instead of all pairs.
- Change nothing observable: same evaluation results, same enablement, same calculated answers, same
  cycle-detection errors (including message text and which pair is reported first), same public API.

### 3. Decisions

- **No incremental re-render.** The obvious larger win is a real dependency graph driving which
  items are re-evaluated when an answer changes, instead of re-evaluating the whole tree. That is a
  behavioral change to when the UI updates and belongs in its own proposal; this change deliberately
  keeps the existing "recompute everything" model and only makes each recomputation cheap.
- **Cache parsed expressions globally, not per questionnaire.** Parsing is a pure function of the
  expression string and independent of engine state, so a process-wide LRU is both correct and more
  effective (shared across questionnaire launches) than a per-`ExpressionEvaluator` cache.
- **Preserve `isExpressionReferencedBy` as a function.** Its callers outside the evaluator (notably
  `QuestionnaireViewModel`) keep working unchanged, and gain the removal of the regex compilation
  for free, even though they do not yet hold on to the precomputed sets.

---

## Part II — Technical spec

### 4. Where it lives

| Area | Location |
|------|----------|
| FHIRPath parse cache | `datacapture/.../fhirpath/FhirPathUtil.kt` — `expressionNodeCache` (access-ordered `LinkedHashMap`, `MAX_CACHED_EXPRESSION_NODES = 512`, guarded by `synchronized`) and `parseExpression()`. Every entry point routes through it: both `evaluateToBase` overloads, `evaluateToBoolean`, `evaluateToDisplay`, `extractExpressionNode`. |
| Link-ID extraction | `datacapture/.../extensions/MoreQuestionnaireItemComponents.kt` — one top-level `LINK_ID_REFERENCE_REGEX = Regex("linkId='([^']*)'")`, a private `List<Extension>.expressionReferencedLinkIds()`, and `expressionReferencedLinkIds: Set<String>` on both `QuestionnaireItemComponent` and `Questionnaire`. |
| Reference test | Same file — both `isExpressionReferencedBy` overloads are now `<element>.expressionReferencedLinkIds.contains(this.linkId)`. |
| Per-questionnaire dependency index | `datacapture/.../fhirpath/ExpressionEvaluator.kt` — `calculatedExpressionItems` (flattened calculable items, `by lazy`) and `calculatedExpressionItemDependencies` (parallel list of `CalculatedExpressionDependencies(referencedLinkIds, dependsOnVariables)`, `by lazy`). |
| Per-answer scan | Same file — `evaluateAllAffectedCalculatedExpressions` filters `calculatedExpressionItems` by index against the precomputed dependencies. |
| Cycle detection | Same file — `detectExpressionCyclicDependency` extracts referenced link IDs once per calculable item, indexes item positions by referenced link ID, and checks only real edges. |

### 5. Cost model

Per **evaluation** of one expression:

| | Before | After |
|---|---|---|
| FHIRPath parse | every evaluation | first evaluation of that expression only (LRU, 512) |

Per **answer change**, with *n* = calculable items, *e* = expression-based extensions per item:

| | Before | After |
|---|---|---|
| `evaluateAllAffectedCalculatedExpressions` | flatten tree + O(n·e) regex compiles + O(n) variable-regex scans | O(n) set lookups |
| `QuestionnaireViewModel.isReferenced` scan | flatten tree + O(n·e) regex compiles | flatten tree + O(n·e) set builds (regex compilation gone; see §8) |

Per **questionnaire load**:

| | Before | After |
|---|---|---|
| `detectExpressionCyclicDependency` | O(n²) pairs × 2 regex compiles | O(n·e) extraction + one pass over actual reference edges |

### 6. Why sharing parsed expressions is safe

Verified against the exact HAPI core in use (`ca.uhn.hapi.fhir:org.hl7.fhir.r4:6.0.22`,
`Dependencies.kt` → `Versions.hapiFhirCore`) by disassembling `FHIRPathEngine`:

- Each `String` overload is literally `parse(path)` followed by the *same* body as its
  `ExpressionNode` counterpart — identical `isResource` handling, identical `ExecutionContext`
  construction, identical `execute` call. `evaluateToString(Base, String)` is exactly
  `convertToString(evaluate(base, path))`. So substituting the node overloads changes nothing but
  where the parse happens.
- The only methods that mutate an `ExpressionNode` are `parseExpression`, `gatherPrecedence`,
  `newGroup` (all parse-time) and `executeType`, which sets `types`/`opTypes`. `executeType` is the
  type-checking path reached through `check()`, which this library never calls. `evaluate` only
  reads the node.

Therefore a parsed node can be shared across evaluations and across threads without copying.

### 7. Behavior changes (all intentional, all improvements)

1. **Link IDs containing regex metacharacters.** The old code interpolated the link ID straight into
   a regex, so a link ID `B.B` matched an expression referencing `BxB` — `.` was a wildcard. Link IDs
   carrying `.`, `(`, `+` are common in generated content. The new extraction compares literal
   strings, so this class of false positive is gone. Covered by a regression test.
2. **`findDependentVariables` tolerates a null expression string.** Variable dependencies are now
   computed up front for every calculable item instead of being short-circuited by the reference
   test, so a malformed `Expression` without an expression string must not fail the whole
   questionnaire. `.orEmpty()` added.
3. Whitespace normalisation (`replace(" ", "")` before matching) is preserved, so
   `linkId = 'B'` still resolves to `B` exactly as before.

### 8. Non-goals / explicitly out of scope

Left untouched on purpose — each changes *when* the UI updates and needs its own proposal:

- **`QuestionnaireViewModel.kt:396-408`** — the per-answer `isReferenced` scan still flattens the
  whole questionnaire on every answer, merely to decide whether to show a loading indicator. It is
  now regex-free but still O(n·e). It should consume a precomputed index (or be dropped).
- **`QuestionnaireViewModel.kt:408` and `:412`** — `modificationCount` is bumped **twice** per
  answer change, so the entire item tree is recomputed twice per answer. The same double pass
  happens at load: `questionnaireStateStateFlow` bumps `modificationCount` after the first emission
  (`QuestionnaireViewModel.kt:640`), so nothing renders until the tree has been evaluated twice.
- **Whole-tree evaluation itself.** Non-paginated questionnaires evaluate every item on every
  emission. Consumers can avoid this today by paginating; a dependency-graph-driven incremental
  update is the real fix (§3).

### 9. Tests

`datacapture/src/test/.../fhirpath/FhirPathUtilTest.kt`:
- `evaluateToBase should return the same result when an expression is evaluated repeatedly` —
  re-evaluating a cached expression keeps returning the right result, and interleaving a second
  expression does not disturb it.
- `evaluateToBase should evaluate the same expression against different resources` — a cached node
  is not bound to the base it was first evaluated against.

`datacapture/src/test/.../extensions/MoreQuestionnaireItemComponentsTest.kt`:
- `isExpressionReferencedBy should return true for reference with whitespace` — `linkId = 'B'`.
- `isExpressionReferencedBy should return false for link id matching another one as a regex` —
  the `B.B` vs `BxB` regression (§7.1).
- `expressionReferencedLinkIds should return link ids of all expression based extensions` — across
  `calculatedExpression` and `enableWhenExpression`, ignoring non-expression extensions.
- `expressionReferencedLinkIds should return empty set for expression without reference`.

Pre-existing test defect fixed as part of this change: `MoreQuestionnaireItemComponentsTest` called
`item2.isReferencedBy(item1)` in two tests, and no such function exists anywhere in the repository —
**the `datacapture` unit test source set did not compile on `release-22.07.2026`**. Both calls are
renamed to `isExpressionReferencedBy`, which is what they mean.

### 10. Verification

- `:datacapture:compileDebugKotlin` — BUILD SUCCESSFUL (only pre-existing deprecation warnings, in
  files this change does not touch).
- `:datacapture:assembleDebug` — BUILD SUCCESSFUL.
- `:datacapture:testDebugUnitTest` for `fhirpath.*` and `MoreQuestionnaireItemComponentsTest` —
  **189 tests, 0 failures**: `MoreQuestionnaireItemComponentsTest` 145 (incl. 4 new),
  `ExpressionEvaluatorTest` 29 (incl. both cyclic-dependency tests, which assert exact message text),
  `FhirPathUtilTest` 3 (incl. 2 new), `FHIRPathEngineHostServicesTest` 12.
- `QuestionnaireViewModelTest` — 41 of 127 fail, **all pre-existing**. Established by running the
  same class in a detached worktree on `origin/release-22.07.2026` carrying *only* the
  `isReferencedBy` rename: the failing sets are identical, name for name. New failures: none. The
  failures are unrelated to expressions (`EXTRA_SHOW_CANCEL_BUTTON…`, pagination, review button) and
  are invisible in CI only because the test source set does not compile (§9).
- `:datacapture:spotlessCheck` — the files this change touches are clean. Seven violations remain in
  files it does not touch (stale copyright years).

### 11. Open items

- Measure on a real large questionnaire. The intended consumer measurement is
  `./gradlew :datacapture:publishToMavenLocal` here, then building openSRP FHIRCore, whose
  `build.gradle.kts` already lists `mavenLocal()` first (add `--refresh-dependencies` on the first
  build, since the artifact version is unchanged).
- Decide whether `MAX_CACHED_EXPRESSION_NODES = 512` should be configurable via `DataCaptureConfig`.
  512 comfortably covers any single questionnaire; the bound exists only to keep a process-lifetime
  cache from growing without limit across many questionnaire launches.
- Port to `datacapture-kmp`. `master` has moved on to the KMP migration, so upstreaming this needs a
  rebase onto the ported sources; `FhirPathUtil`'s engine access in particular may need a different
  home there.
