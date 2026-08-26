/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.datacapture.fhirpath

import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.QuestionnaireResponse

/**
 * Memoizes expression evaluation for the duration of a single questionnaire state computation.
 *
 * Building the questionnaire state evaluates the expressions of every item, and those expressions
 * commonly walk the whole questionnaire response (`%resource.repeat(item).where(linkId=...)`), so
 * the work is quadratic in the number of items. Sibling items also frequently carry byte-identical
 * expressions, e.g. several questions enabled by the same answer.
 *
 * Memoizing is only sound while the values the expressions read do not change. Within one state
 * computation they do not: the only writers of answers are calculated expressions, which run
 * between state computations, and the pass-local edits that do happen are announced through
 * [invalidate]. The cache is therefore off by default, switched on for the duration of a pass by
 * [activate], and emptied by [invalidate] whenever something a memoized value could have depended
 * on changes.
 */
internal class QuestionnaireExpressionCache {

  /** Whether values are currently being memoized. */
  var isActive = false
    private set

  private val expressionResults = mutableMapOf<String, List<Base>>()

  private val questionnaireVariableValues = mutableMapOf<String, Base?>()

  private var responseItemIndex: QuestionnaireResponseItemIndex? = null

  /** Starts memoizing, discarding anything memoized earlier. */
  fun activate() {
    invalidate()
    isActive = true
  }

  /** Stops memoizing and discards everything memoized. */
  fun deactivate() {
    isActive = false
    invalidate()
  }

  /** Discards everything memoized, without changing whether memoizing is on. */
  fun invalidate() {
    expressionResults.clear()
    questionnaireVariableValues.clear()
    // The index reflects the answers as well as the structure of the response, because
    // `repeat(item)` drops items that are equal to one it has already reached.
    responseItemIndex = null
  }

  /**
   * The items of [questionnaireResponse] indexed by link ID, built on first use, or `null` while
   * memoizing is off.
   *
   * The index is only handed out while memoizing because that is the span over which it is known to
   * stay valid: outside it the answers change without [invalidate] being called.
   */
  fun responseItemIndex(
    questionnaireResponse: QuestionnaireResponse,
  ): QuestionnaireResponseItemIndex? {
    if (!isActive) return null
    return responseItemIndex
      ?: QuestionnaireResponseItemIndex(questionnaireResponse).also { responseItemIndex = it }
  }

  fun cachedResult(key: String): List<Base>? = if (isActive) expressionResults[key] else null

  fun cacheResult(key: String, result: List<Base>) {
    if (isActive) expressionResults[key] = result
  }

  /**
   * Whether a value for the questionnaire level variable [name] is memoized. Needed separately from
   * [cachedQuestionnaireVariable] because a variable can legitimately evaluate to `null`.
   */
  fun hasCachedQuestionnaireVariable(name: String) =
    isActive && questionnaireVariableValues.containsKey(name)

  fun cachedQuestionnaireVariable(name: String): Base? = questionnaireVariableValues[name]

  fun cacheQuestionnaireVariable(name: String, value: Base?) {
    if (isActive) questionnaireVariableValues[name] = value
  }
}
