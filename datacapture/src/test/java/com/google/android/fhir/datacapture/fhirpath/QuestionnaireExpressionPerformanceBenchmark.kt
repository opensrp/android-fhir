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

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.google.android.fhir.datacapture.extensions.EXTENSION_CALCULATED_EXPRESSION_URL
import java.io.File
import org.hl7.fhir.r4.hapi.ctx.HapiWorkerContext
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.ExpressionNode
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent
import org.hl7.fhir.r4.utils.FHIRPathEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Four-scenario benchmark of the two SDC expression-evaluation changes, on the HAPI FHIRPath engine
 * this library uses (`org.hl7.fhir.r4:6.0.22`).
 *
 * Change 1: parse each distinct FHIRPath once and memoize results for the span of one questionnaire
 * state computation.
 *
 * Change 2: evaluate `%resource.repeat(item)` once, index items by link ID, and rewrite
 * `repeat(item).where(linkId=...)` searches to a collection-valued constant.
 *
 * The first three items are source answers only. Every later item's calculatedExpression reads the
 * three preceding items and sums them. Test data 1 uses `repeat(item)`; test data 2 uses
 * `%resource.item.where`, which change 2 does not rewrite.
 */
class QuestionnaireExpressionPerformanceBenchmark {

  @Test
  fun `400 item calculatedExpression four scenarios`() {
    val output = StringBuilder()
    fun log(line: String) {
      println(line)
      output.appendLine(line)
    }

    val itemCount = 400
    val warmupRuns = 2
    val measuredRuns = 5
    val response = questionnaireResponse(itemCount)
    val questionnaireRepeat = questionnaire(itemCount, Dataset.Repeat)
    val questionnaireFull = questionnaire(itemCount, Dataset.Full)
    val engine = newEngine()

    log(
      "android-fhir HAPI FHIRPath (org.hl7.fhir.r4:6.0.22)  |  $itemCount items, ${itemCount - SOURCE_ITEM_COUNT} calculatedExpressions (each uses 3 other items; first $SOURCE_ITEM_COUNT are source answers)",
    )
    log(
      "warmup=$warmupRuns  measured=$measuredRuns  host=${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
    )
    log("")

    data class Scenario(
      val name: String,
      val useParseCache: Boolean,
      val useIndex: Boolean,
      val useMemo: Boolean,
    )

    val scenarios =
      listOf(
        Scenario("vanilla", useParseCache = false, useIndex = false, useMemo = false),
        Scenario(
          "vanilla + change 1 (parse cache + memo)",
          useParseCache = true,
          useIndex = false,
          useMemo = true,
        ),
        Scenario(
          "vanilla + change 2 (repeat(item) index)",
          useParseCache = false,
          useIndex = true,
          useMemo = false,
        ),
        Scenario(
          "vanilla + change 1 & 2",
          useParseCache = true,
          useIndex = true,
          useMemo = true,
        ),
      )

    for (dataset in Dataset.values()) {
      val questionnaire = if (dataset == Dataset.Repeat) questionnaireRepeat else questionnaireFull
      val jobs = evaluationJobs(questionnaire, response)
      log("=== Test data ${dataset.ordinal + 1}: ${dataset.label} ===")
      log("calculable items: ${jobs.size}")
      log("example: ${jobs.first().expression}")

      val expected =
        jobs.take(CORRECTNESS_SAMPLE).map { job ->
          evaluateOne(
              engine = engine,
              expression = job.expression,
              response = response,
              questionnaire = questionnaire,
              questionnaireItem = job.questionnaireItem,
              responseItem = job.responseItem,
              index = null,
              parseCache = null,
              memo = null,
            )
            .map { it.describe() }
        }

      val rows = mutableListOf<String>()
      for (scenario in scenarios) {
        val parseCache =
          if (scenario.useParseCache) {
            LinkedHashMap<String, ExpressionNode>(64, 0.75f, true)
          } else {
            null
          }
        val eval = {
          evaluateAll(
            engine = engine,
            jobs = jobs,
            response = response,
            questionnaire = questionnaire,
            useIndex = scenario.useIndex,
            parseCache = parseCache,
            useMemo = scenario.useMemo,
          )
        }

        val sample = eval().take(CORRECTNESS_SAMPLE).map { result -> result.map { it.describe() } }
        assertEquals(
          "${scenario.name} / ${dataset.name} disagreed with vanilla on the first $CORRECTNESS_SAMPLE expressions",
          expected,
          sample,
        )

        repeat(warmupRuns) { eval() }
        val durationsNanos =
          (1..measuredRuns).map {
            val start = System.nanoTime()
            eval()
            System.nanoTime() - start
          }
        val sorted = durationsNanos.sorted()
        val median = sorted[sorted.size / 2]
        val min = sorted.first()
        val max = sorted.last()
        rows += "| ${scenario.name} | ${formatMs(median)} | ${formatMs(min)} | ${formatMs(max)} |"
        log(
          "  ${scenario.name}: median=${formatMs(median)}  min=${formatMs(min)}  max=${formatMs(max)}",
        )
      }
      log("")
      log("| scenario | median | min | max |")
      log("|---|---:|---:|---:|")
      rows.forEach { log(it) }
      log("")
    }

    log("=== Engine-only 3-item expression, $itemCount response items ===")
    val oneRepeat = threeItemExpression(Dataset.Repeat, "q000", "q001", "q002")
    val oneFull = threeItemExpression(Dataset.Full, "q000", "q001", "q002")
    for ((label, expression) in listOf("repeat(item) x3" to oneRepeat, "item.where x3" to oneFull)) {
      repeat(5) { engine.evaluate(emptyMap<String, Any?>(), response, null, response, expression) }
      val durationsNanos =
        (1..10).map {
          val start = System.nanoTime()
          engine.evaluate(emptyMap<String, Any?>(), response, null, response, expression)
          System.nanoTime() - start
        }
      log(
        "  $label x1: median=${formatMs(durationsNanos.sorted()[durationsNanos.size / 2])}  (parse+eval, no cache)",
      )
    }

    log("")
    log("=== SDC leftover from change 1: detectExpressionCyclicDependency (isReferencedBy regex) ===")
    val items = questionnaireRepeat.item
    cyclicDependencyScan(items)
    val cyclicDurations =
      (1..3).map {
        val start = System.nanoTime()
        cyclicDependencyScan(items)
        System.nanoTime() - start
      }
    log(
      "  O(n^2) regex scan over ${itemCount - SOURCE_ITEM_COUNT} calculable items: median=${formatMs(cyclicDurations.sorted()[cyclicDurations.size / 2])}",
    )

    val outFile = File("/tmp/android-fhir-bench-results.txt")
    outFile.writeText(output.toString())
    log("")
    log("Wrote ${outFile.absolutePath}")
  }

  private data class EvaluationJob(
    val questionnaireItem: Questionnaire.QuestionnaireItemComponent,
    val responseItem: QuestionnaireResponseItemComponent,
    val expression: String,
  )

  private fun evaluationJobs(
    questionnaire: Questionnaire,
    response: QuestionnaireResponse,
  ): List<EvaluationJob> =
    questionnaire.item.mapIndexedNotNull { i, questionnaireItem ->
      val expression = calculatedExpressionOf(questionnaireItem) ?: return@mapIndexedNotNull null
      EvaluationJob(questionnaireItem, response.item[i], expression)
    }

  private fun evaluateAll(
    engine: FHIRPathEngine,
    jobs: List<EvaluationJob>,
    response: QuestionnaireResponse,
    questionnaire: Questionnaire,
    useIndex: Boolean,
    parseCache: MutableMap<String, ExpressionNode>?,
    useMemo: Boolean,
  ): List<List<Base>> {
    val index = if (useIndex) QuestionnaireResponseItemIndex(response) else null
    val memo = if (useMemo) mutableMapOf<String, List<Base>>() else null
    return jobs.map { job ->
      evaluateOne(
        engine = engine,
        expression = job.expression,
        response = response,
        questionnaire = questionnaire,
        questionnaireItem = job.questionnaireItem,
        responseItem = job.responseItem,
        index = index,
        parseCache = parseCache,
        memo = memo,
      )
    }
  }

  private fun evaluateOne(
    engine: FHIRPathEngine,
    expression: String,
    response: QuestionnaireResponse,
    questionnaire: Questionnaire,
    questionnaireItem: Questionnaire.QuestionnaireItemComponent,
    responseItem: QuestionnaireResponseItemComponent,
    index: QuestionnaireResponseItemIndex?,
    parseCache: MutableMap<String, ExpressionNode>?,
    memo: MutableMap<String, List<Base>>?,
  ): List<Base> {
    val contextMap =
      hashMapOf<String, Any?>(
        "questionnaire" to questionnaire,
        "qItem" to questionnaireItem,
        "context" to responseItem,
      )
    VARIABLE_REGEX.findAll(expression).map { it.groupValues[1] }.toSet()

    val (expr, appContext) =
      if (index != null) {
        val rewritten = indexedItemSearchExpression(expression)
        if (rewritten != null) {
          rewritten.itemCollections.forEach { (name, collection) ->
            contextMap[name] = index.items(collection.access, collection.linkId)
          }
          rewritten.expression to contextMap
        } else {
          expression to contextMap
        }
      } else {
        expression to contextMap
      }

    memo?.get(expr)?.let {
      return it
    }
    val result =
      if (parseCache != null) {
        val node = parseCache.getOrPut(expr) { engine.parse(expr) }
        engine.evaluate(appContext, response, null, responseItem, node)
      } else {
        engine.evaluate(appContext, response, null, responseItem, expr)
      }
    memo?.put(expr, result)
    return result
  }

  private fun calculatedExpressionOf(
    item: Questionnaire.QuestionnaireItemComponent,
  ): String? =
    item.getExtensionByUrl(EXTENSION_CALCULATED_EXPRESSION_URL)?.let { extension ->
      (extension.value as Expression).expression
    }

  private fun cyclicDependencyScan(items: List<Questionnaire.QuestionnaireItemComponent>) {
    val calculable = items.filter { calculatedExpressionOf(it) != null }
    for (current in calculable) {
      for (dependent in calculable) {
        isReferencedBy(current, dependent)
      }
    }
  }

  /**
   * Pre-change `isExpressionReferencedBy`: compiles a Regex interpolating the link ID on every
   * call.
   */
  private fun isReferencedBy(
    source: Questionnaire.QuestionnaireItemComponent,
    item: Questionnaire.QuestionnaireItemComponent,
  ): Boolean {
    val linkId = source.linkId ?: return false
    val expression = calculatedExpressionOf(item) ?: return false
    return expression.replace(" ", "").contains(Regex(".*linkId='$linkId'.*"))
  }

  private enum class Dataset(val label: String) {
    Repeat("repeat(item) lookup of the 3 preceding items, summed"),
    Full("%resource.item.where lookup of the 3 preceding items, summed"),
  }

  private fun threeItemExpression(dataset: Dataset, first: String, second: String, third: String):
    String {
    fun lookup(linkId: String): String {
      val search =
        when (dataset) {
          Dataset.Repeat -> "%resource.repeat(item)"
          Dataset.Full -> "%resource.item"
        }
      return "$search.where(linkId='$linkId' and answer.empty().not()).select(answer.value).first().toInteger()"
    }
    return "${lookup(first)} + ${lookup(second)} + ${lookup(third)}"
  }

  private fun questionnaire(itemCount: Int, dataset: Dataset): Questionnaire =
    Questionnaire().apply {
      status = Enumerations.PublicationStatus.ACTIVE
      repeat(itemCount) { i ->
        addItem(
          Questionnaire.QuestionnaireItemComponent().apply {
            linkId = linkIdOf(i)
            type = Questionnaire.QuestionnaireItemType.INTEGER
            if (i >= SOURCE_ITEM_COUNT) {
              addExtension().apply {
                url = EXTENSION_CALCULATED_EXPRESSION_URL
                setValue(
                  Expression().apply {
                    language = "text/fhirpath"
                    expression =
                      threeItemExpression(
                        dataset,
                        linkIdOf(i - 3),
                        linkIdOf(i - 2),
                        linkIdOf(i - 1),
                      )
                  },
                )
              }
            }
          },
        )
      }
    }

  private fun questionnaireResponse(itemCount: Int): QuestionnaireResponse =
    QuestionnaireResponse().apply {
      status = QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED
      repeat(itemCount) { i ->
        addItem(
          QuestionnaireResponseItemComponent().apply {
            linkId = linkIdOf(i)
            addAnswer(
              QuestionnaireResponseItemAnswerComponent().apply { value = IntegerType(i) },
            )
          },
        )
      }
    }

  private fun linkIdOf(index: Int): String = "q" + index.toString().padStart(3, '0')

  private fun newEngine(): FHIRPathEngine =
    with(FhirContext.forCached(FhirVersionEnum.R4)) {
      FHIRPathEngine(HapiWorkerContext(this, this.validationSupport)).apply {
        hostServices = FHIRPathEngineHostServices
      }
    }

  private fun Base.describe(): String =
    if (isPrimitive) "${fhirType()}:${primitiveValue()}" else fhirType()

  private fun formatMs(nanos: Long): String = "%.1f ms".format(nanos / 1_000_000.0)

  private companion object {
    const val SOURCE_ITEM_COUNT = 3
    const val CORRECTNESS_SAMPLE = 5
    val VARIABLE_REGEX = Regex("%([A-Za-z0-9\\-']{1,64})")
  }
}
