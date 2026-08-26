/*
 * Copyright 2022-2026 Google LLC
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
import org.hl7.fhir.r4.hapi.ctx.HapiWorkerContext
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.ExpressionNode
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.utils.FHIRPathEngine

private val fhirPathEngine: FHIRPathEngine =
  with(FhirContext.forCached(FhirVersionEnum.R4)) {
    FHIRPathEngine(HapiWorkerContext(this, this.validationSupport)).apply {
      hostServices = FHIRPathEngineHostServices
    }
  }

/** The maximum number of parsed expressions kept in [expressionNodeCache]. */
private const val MAX_CACHED_EXPRESSION_NODES = 512

/**
 * Parsed FHIRPath expressions, keyed by the expression they were parsed from, with the least
 * recently used entry evicted once [MAX_CACHED_EXPRESSION_NODES] is exceeded.
 *
 * A questionnaire evaluates the same small set of expressions over and over: every item is
 * re-evaluated on each UI state emission, i.e. on every answer the user gives, so the number of
 * evaluations grows with (number of items x number of answers) while the number of distinct
 * expressions stays constant. Parsing is therefore worth doing once per expression instead of once
 * per evaluation.
 *
 * Sharing a parsed [ExpressionNode] between evaluations is safe: [FHIRPathEngine] only mutates the
 * nodes while parsing them and while type checking them in `check`, which this library never calls.
 */
private val expressionNodeCache =
  // `accessOrder = true` so that eviction is least recently used rather than insertion ordered.
  object : LinkedHashMap<String, ExpressionNode>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ExpressionNode>) =
      size > MAX_CACHED_EXPRESSION_NODES
  }

/** Returns the parsed [expression], parsing it only if it is not already cached. */
private fun parseExpression(expression: String): ExpressionNode =
  synchronized(expressionNodeCache) {
    expressionNodeCache.getOrPut(expression) { fhirPathEngine.parse(expression) }
  }

/**
 * Evaluates the expressions over list of resources [Resource] and joins to space separated string
 */
internal fun evaluateToDisplay(expressions: List<String>, data: Resource) =
  expressions.joinToString(" ") {
    fhirPathEngine.convertToString(fhirPathEngine.evaluate(data, parseExpression(it)))
  }

/** Evaluates the expression over resource [Resource] and returns string value */
internal fun evaluateToString(
  expression: ExpressionNode,
  data: Resource?,
  contextMap: Map<String, Base?>,
) =
  fhirPathEngine.evaluateToString(
    /* appInfo = */ contextMap,
    /* focusResource = */ null,
    /* rootResource = */ null,
    /* base = */ data,
    /* node = */ expression,
  )

/**
 * Evaluates the expression and returns the boolean result. The resources [QuestionnaireResponse]
 * and [QuestionnaireResponseItemComponent] are passed as fhirPath supplements as defined in fhir
 * specs https://build.fhir.org/ig/HL7/sdc/expressions.html#fhirpath-supplements
 *
 * %resource = [QuestionnaireResponse], %context = [QuestionnaireResponseItemComponent]
 */
internal fun evaluateToBoolean(
  questionnaireResponse: QuestionnaireResponse,
  questionnaireResponseItemComponent: QuestionnaireResponseItemComponent,
  expression: String,
  contextMap: Map<String, Base?> = mapOf(),
): Boolean {
  return fhirPathEngine.evaluateToBoolean(
    contextMap,
    questionnaireResponse,
    null,
    questionnaireResponseItemComponent,
    parseExpression(expression),
  )
}

/**
 * Evaluates the expression and returns the list of [Base]. The resources [QuestionnaireResponse]
 * and [QuestionnaireResponseItemComponent] are passed as fhirPath supplements as defined in fhir
 * specs https://build.fhir.org/ig/HL7/sdc/expressions.html#fhirpath-supplements. All other
 * constants are passed as contextMap
 *
 * %resource = [QuestionnaireResponse], %context = [QuestionnaireResponseItemComponent]
 *
 * [itemCollections] holds the questionnaire response items the constants of an
 * [IndexedItemSearchExpression] stand for, if [expression] is one.
 */
internal fun evaluateToBase(
  questionnaireResponse: QuestionnaireResponse?,
  questionnaireResponseItem: QuestionnaireResponseItemComponent?,
  expression: String,
  contextMap: Map<String, Base?> = mapOf(),
  itemCollections: Map<String, List<Base>> = emptyMap(),
): List<Base> {
  return fhirPathEngine.evaluate(
    /* appContext = */ appContextOf(contextMap, itemCollections),
    /* focusResource = */ questionnaireResponse,
    /* rootResource = */ null,
    /* base = */ questionnaireResponseItem,
    /* node = */ parseExpression(expression),
  )
}

/**
 * The constants to evaluate an expression with: the variables in [contextMap], and the collections
 * in [itemCollections] that the constants of an [IndexedItemSearchExpression] stand for.
 */
private fun appContextOf(
  contextMap: Map<String, Base?>,
  itemCollections: Map<String, List<Base>>,
): Map<String, Any?> =
  if (itemCollections.isEmpty()) {
    contextMap
  } else {
    HashMap<String, Any?>(contextMap).apply { putAll(itemCollections) }
  }

/** Evaluates the given expression and returns list of [Base] */
internal fun evaluateToBase(base: Base, expression: String): List<Base> {
  return fhirPathEngine.evaluate(
    /* base = */ base,
    /* node = */ parseExpression(expression),
  )
}

/** Evaluates the given list of [Base] elements and returns boolean result */
internal fun convertToBoolean(items: List<Base>) = fhirPathEngine.convertToBoolean(items)

/** Parse the given expression into [ExpressionNode] */
internal fun extractExpressionNode(fhirPath: String) = parseExpression(fhirPath)
