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

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent
import org.hl7.fhir.r4.model.StringType
import org.junit.Test

/**
 * Pins evaluation through [QuestionnaireResponseItemIndex] to evaluation without it.
 *
 * Every test asserts the same result both ways, so that the index cannot start returning items
 * `%resource.repeat(item)` would not have returned - in a different order, without its
 * de-duplication, or reaching items it cannot reach - without a test failing. The expected value is
 * asserted as well, so the tests also record what those semantics are.
 */
class IndexedItemSearchEvaluationTest {

  @Test
  fun `should find no item nested under an answer`() {
    assertEvaluatesTo("%resource.repeat(item).where(linkId='nested').answer.value")
  }

  @Test
  fun `should count two instances of a repeated group with identical content once`() {
    assertEvaluatesTo("%resource.repeat(item).where(linkId='dup').count()", "integer:1")
  }

  @Test
  fun `should count two instances of a repeated group with different answers twice`() {
    assertEvaluatesTo("%resource.repeat(item).where(linkId='distinct').count()", "integer:2")
  }

  @Test
  fun `should apply a first() to all the items a search finds`() {
    assertEvaluatesTo(
      "%resource.repeat(item).where(linkId='distinct').answer.first().value",
      "string:x",
    )
  }

  @Test
  fun `should evaluate a condition with a top level or over every item`() {
    assertEvaluatesTo(
      "%resource.repeat(item).where(linkId='a' or linkId='b').answer.value",
      "string:1",
      "string:2",
    )
  }

  @Test
  fun `should evaluate a conjunction condition`() {
    assertEvaluatesTo(
      "%resource.repeat(item).where(linkId='distinct' and answer.value = 'y').answer.value",
      "string:y",
    )
  }

  @Test
  fun `should evaluate an expression with two searches`() {
    assertEvaluatesTo(
      "%resource.repeat(item).where(linkId='a').answer.value = " +
        "%resource.repeat(item).where(linkId='b').answer.value",
      "boolean:false",
    )
  }

  @Test
  fun `should evaluate an unfiltered search`() {
    assertEvaluatesTo(
      "%resource.repeat(item).linkId",
      "string:a",
      "string:b",
      "string:group",
      "string:question-with-nested",
      "string:dup",
      "string:distinct",
      "string:distinct",
    )
  }

  @Test
  fun `tree item search should find a top level item`() {
    assertEvaluatesTo("%resource.item.where(linkId='a').answer.value", "string:1")
  }

  @Test
  fun `tree item search should not find a nested item`() {
    assertEvaluatesTo("%resource.item.where(linkId='dup').count()", "integer:0")
  }

  @Test
  fun `leaf repeat search should find a nested item the tree search does not`() {
    assertEvaluatesTo("%resource.repeat(item).where(linkId='dup').count()", "integer:1")
  }

  @Test
  fun `should evaluate a search the index cannot narrow`() {
    assertEvaluatesTo(
      "%resource.repeat(item).where(linkId.startsWith('dist')).answer.value",
      "string:x",
      "string:y",
    )
  }

  @Test
  fun `should read the items a search finds from the index while the cache is active`() {
    // The index is built on first use and discarded by invalidate(), which QuestionnaireViewModel
    // calls for the only two answer edits that can happen while it is active. Adding an item to the
    // response mid computation cannot happen at all, and is done here only to make the index
    // observable: an evaluation that still finds the response as it was has read the index.
    val questionnaireResponse = questionnaireResponse()
    val cache = QuestionnaireExpressionCache().apply { activate() }
    val evaluator =
      ExpressionEvaluator(
        questionnaire = Questionnaire(),
        questionnaireResponse = questionnaireResponse,
        expressionCache = cache,
      )

    assertThat(evaluator.evaluate("%resource.repeat(item).where(linkId='late').count()"))
      .containsExactly("integer:0")

    questionnaireResponse.addItem(responseItem("late", "arrived"))

    // A different expression, so this is the index being reused rather than a memoized result.
    assertThat(evaluator.evaluate("%resource.repeat(item).where(linkId='late').answer.value"))
      .isEmpty()

    cache.invalidate()

    assertThat(evaluator.evaluate("%resource.repeat(item).where(linkId='late').answer.value"))
      .containsExactly("string:arrived")
  }

  private fun assertEvaluatesTo(expression: String, vararg expected: String) {
    val indexed = evaluate(expression, useIndex = true)

    assertThat(indexed).containsExactlyElementsIn(expected).inOrder()
    assertThat(indexed).isEqualTo(evaluate(expression, useIndex = false))
  }

  private fun evaluate(expression: String, useIndex: Boolean): List<String> =
    ExpressionEvaluator(
        questionnaire = Questionnaire(),
        questionnaireResponse = questionnaireResponse(),
        expressionCache = QuestionnaireExpressionCache().apply { if (useIndex) activate() },
      )
      .evaluate(expression)

  private fun ExpressionEvaluator.evaluate(expression: String): List<String> =
    runBlocking {
        evaluateExpression(
          Questionnaire.QuestionnaireItemComponent(),
          null,
          Expression().apply {
            language = "text/fhirpath"
            this.expression = expression
          },
        )
      }
      .map { it.describe() }

  private fun Base.describe() =
    if (isPrimitive) "${fhirType()}:${primitiveValue()}" else fhirType()

  /**
   * A response holding what makes `repeat(item)` and a link ID index differ: two instances of a
   * repeated group with identical content, two with different content, and a question with an item
   * nested under its answer.
   */
  private fun questionnaireResponse() =
    QuestionnaireResponse().apply {
      addItem(responseItem("a", "1"))
      addItem(responseItem("b", "2"))
      addItem(
        QuestionnaireResponseItemComponent().apply {
          linkId = "group"
          addItem(responseItem("dup", "same"))
          addItem(responseItem("dup", "same"))
          addItem(responseItem("distinct", "x"))
          addItem(responseItem("distinct", "y"))
        },
      )
      addItem(
        QuestionnaireResponseItemComponent().apply {
          linkId = "question-with-nested"
          addAnswer(
            QuestionnaireResponseItemAnswerComponent().apply {
              value = StringType("outer")
              addItem(responseItem("nested", "hidden"))
            },
          )
        },
      )
    }

  private fun responseItem(linkId: String, answer: String) =
    QuestionnaireResponseItemComponent().apply {
      this.linkId = linkId
      addAnswer(QuestionnaireResponseItemAnswerComponent().apply { value = StringType(answer) })
    }
}
