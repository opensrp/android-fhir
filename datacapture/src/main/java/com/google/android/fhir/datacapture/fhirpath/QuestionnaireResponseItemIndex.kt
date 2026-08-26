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
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent

/** The FHIRPath search that [ItemIndexAccess.Leaf] stands in for. */
internal const val REPEAT_ITEM_SEARCH_EXPRESSION = "%resource.repeat(item)"

/** The FHIRPath search that [ItemIndexAccess.Tree] stands in for. */
internal const val TREE_ITEM_SEARCH_EXPRESSION = "%resource.item"

/**
 * How a rewritten search reads the index. `repeat(item)` flattens the item tree (leaf). `.item`
 * returns only the direct children of the resource (tree). The two item sets are not the same:
 * nested items are in the leaf set and not the tree set, and `repeat(item)` also drops
 * `equalsDeep` duplicates.
 */
internal enum class ItemIndexAccess {
  Leaf,
  Tree,
}

/**
 * The questionnaire response items that an indexed search would have had to find, in the access
 * mode that search uses.
 *
 * `repeat(item)` is expensive out of proportion to the size of the response: for every item it
 * projects it compares that item with `equalsDeep` against every item collected so far
 * (`FHIRPathEngine.funcRepeat`), so a single evaluation is quadratic in the number of items and
 * recursive in their depth. `%resource.item.where(linkId = …)` is the same lookup without that
 * flattening: it still filters every direct child, once per expression.
 *
 * Each mode is built by asking the engine itself, rather than by walking the response, because the
 * observable properties of the two searches would be lost by a hand written traversal. Leaf
 * (`repeat(item)`):
 * - it reaches items through `item` only, so items nested under `answer.item` - how this library
 *   stores repeated groups and questions with nested items - are not part of the result;
 * - it drops an item that is `equalsDeep` to one already collected, so two instances of a repeated
 *   group with identical content count once;
 * - it returns items breadth first, which `first()`, `last()` and `select()` expose.
 *
 * Tree (`%resource.item`) is the resource's `item` children, in list order, with no flattening and
 * no de-duplication. Nested items are not in it.
 *
 * Each mode is evaluated on first use, so an expression that only searches `.item` does not pay
 * for `repeat(item)`, and the other way around.
 *
 * An index reflects the response as it was when it was built, including the answers, since
 * de-duplication depends on them. It is therefore owned by [QuestionnaireExpressionCache] and lives
 * only as long as the memoization it belongs to.
 */
internal class QuestionnaireResponseItemIndex(
  private val questionnaireResponse: QuestionnaireResponse,
) {

  /** Every item `repeat(item)` reaches, in the order the engine returns them. */
  val allItems: List<Base> by lazy {
    evaluateToBase(
      questionnaireResponse = questionnaireResponse,
      questionnaireResponseItem = null,
      expression = REPEAT_ITEM_SEARCH_EXPRESSION,
    )
  }

  private val itemsByLinkId: Map<String?, List<Base>> by lazy {
    allItems.filterIsInstance<QuestionnaireResponseItemComponent>().groupBy { it.linkId }
  }

  /** Direct `item` children of the resource, in the order `%resource.item` returns them. */
  val treeItems: List<Base> by lazy {
    evaluateToBase(
      questionnaireResponse = questionnaireResponse,
      questionnaireResponseItem = null,
      expression = TREE_ITEM_SEARCH_EXPRESSION,
    )
  }

  private val treeItemsByLinkId: Map<String?, List<Base>> by lazy {
    treeItems.filterIsInstance<QuestionnaireResponseItemComponent>().groupBy { it.linkId }
  }

  /** The items with [linkId], in the order `%resource.repeat(item)` returns them. */
  fun itemsWithLinkId(linkId: String): List<Base> = itemsByLinkId[linkId].orEmpty()

  /**
   * The items [access] stands for, narrowed to [linkId] when the search's `where` condition makes
   * that link ID mandatory, or the whole set for that mode when it does not.
   */
  fun items(access: ItemIndexAccess, linkId: String?): List<Base> =
    when (access) {
      ItemIndexAccess.Leaf -> if (linkId == null) allItems else itemsByLinkId[linkId].orEmpty()
      ItemIndexAccess.Tree -> if (linkId == null) treeItems else treeItemsByLinkId[linkId].orEmpty()
    }
}
