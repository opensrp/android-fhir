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

import timber.log.Timber

/**
 * The questionnaire response items a rewritten search stands for: which [ItemIndexAccess] to read,
 * and the link ID to narrow to, or `null` when the search stands for every item that mode reaches.
 */
internal data class IndexedItemCollection(val access: ItemIndexAccess, val linkId: String?)

/**
 * An expression in which every `%resource.repeat(item)` and `%resource.item` search has been
 * replaced by a constant standing for the questionnaire response items that search would have had
 * to find, so that [QuestionnaireResponseItemIndex] can supply them instead of the engine searching
 * the response for them. See [indexedItemSearchExpression].
 *
 * @param expression the rewritten expression
 * @param itemCollections the constant each search was replaced by
 */
internal class IndexedItemSearchExpression(
  val expression: String,
  val itemCollections: Map<String, IndexedItemCollection>,
)

/**
 * The prefix of the constants introduced for [ItemIndexAccess.Leaf] searches. A questionnaire
 * defined variable of the same name would be shadowed by one of them, so an expression that
 * mentions the prefix is left alone entirely.
 */
private const val LEAF_COLLECTION_CONSTANT_PREFIX = "sdcRepeatItems"

/**
 * The prefix of the constants introduced for [ItemIndexAccess.Tree] searches. Same shadowing rule
 * as [LEAF_COLLECTION_CONSTANT_PREFIX].
 */
private const val TREE_COLLECTION_CONSTANT_PREFIX = "sdcTreeItems"

/** `%resource.repeat(item)`, the flattened (leaf) search. */
private val REPEAT_ITEM_SEARCH = Regex("""%resource\s*\.\s*repeat\s*\(\s*item\s*\)""")

/** `%resource.item`, the direct-child (tree) search. Does not match `repeat(item)`. */
private val TREE_ITEM_SEARCH = Regex("""%resource\s*\.\s*item\b""")

/** A `.where(` call, matched immediately after a rewritten item search. */
private val WHERE_CALL = Regex("""\s*\.\s*where\s*\(""")

/** A `where` condition that is exactly a link ID equality, in either order. */
private val LINK_ID_EQUALITY = Regex("""\s*linkId\s*=\s*'((?:[^'\\]|\\.)*)'\s*""")
private val REVERSED_LINK_ID_EQUALITY = Regex("""\s*'((?:[^'\\]|\\.)*)'\s*=\s*linkId\s*""")

/**
 * Operators that can make a `where` condition true for an item whose link ID does not match, so a
 * condition containing one of them at the top level says nothing about the items it selects.
 */
private val NON_CONJUNCTIVE_OPERATORS = setOf("or", "xor", "implies", "|")

/** The conjunction whose terms a link ID equality may be one of. */
private const val CONJUNCTION = "and"

/** The maximum number of rewrites kept in [indexedItemSearchExpressionCache]. */
private const val MAX_CACHED_INDEXED_EXPRESSIONS = 512

/**
 * Rewrites keyed by the expression they were derived from, including the expressions that cannot be
 * rewritten, with the least recently used entry evicted once [MAX_CACHED_INDEXED_EXPRESSIONS] is
 * exceeded.
 *
 * A rewrite depends on the expression text only, never on the response or the item being evaluated,
 * so it is worth doing once per expression instead of once per evaluation.
 */
private val indexedItemSearchExpressionCache =
  // `accessOrder = true` so that eviction is least recently used rather than insertion ordered.
  object : LinkedHashMap<String, IndexedItemSearchExpression?>(64, 0.75f, true) {
    override fun removeEldestEntry(
      eldest: MutableMap.MutableEntry<String, IndexedItemSearchExpression?>,
    ) = size > MAX_CACHED_INDEXED_EXPRESSIONS
  }

/**
 * Returns [expression] with every `%resource.repeat(item)` (leaf) and `%resource.item` (tree)
 * search replaced by a constant naming the items that search has to produce, or `null` when it
 * contains no such search or cannot be rewritten safely.
 *
 * Only the search itself is replaced; everything the expression does with its result, `where`
 * condition included, is kept verbatim and still evaluated by the engine. The rewrite is therefore
 * equivalent as long as the constant resolves to exactly what that search returns, which is what
 * [QuestionnaireResponseItemIndex.items] guarantees for each [ItemIndexAccess].
 *
 * A search whose `where` condition makes a link ID mandatory - the condition is a link ID equality,
 * or a conjunction one of whose terms is one - is narrowed further to the items with that link ID.
 * The condition still runs, on those items, so narrowing cannot change the result: every item it
 * could have selected has that link ID. Anything less clear cut, a top level `or` in particular, is
 * left to stand for every item the search reaches.
 */
internal fun indexedItemSearchExpression(expression: String): IndexedItemSearchExpression? =
  synchronized(indexedItemSearchExpressionCache) {
    if (indexedItemSearchExpressionCache.containsKey(expression)) {
      indexedItemSearchExpressionCache[expression]
    } else {
      rewriteItemSearches(expression).also { indexedItemSearchExpressionCache[expression] = it }
    }
  }

private fun rewriteItemSearches(expression: String): IndexedItemSearchExpression? {
  // Keep the common case - an expression with nothing to rewrite - to a substring search, and
  // leave alone an expression that could refer to the constants introduced below.
  if (expression.contains(LEAF_COLLECTION_CONSTANT_PREFIX)) return null
  if (expression.contains(TREE_COLLECTION_CONSTANT_PREFIX)) return null
  if (!expression.contains("repeat") && !expression.contains(".item")) return null

  val searches = itemSearches(expression)
  if (searches.isEmpty()) return null

  val rewritten = StringBuilder()
  val itemCollections = mutableMapOf<String, IndexedItemCollection>()
  var copiedUpTo = 0
  for (search in searches) {
    val prefix =
      if (search.access == ItemIndexAccess.Leaf) {
        LEAF_COLLECTION_CONSTANT_PREFIX
      } else {
        TREE_COLLECTION_CONSTANT_PREFIX
      }
    val name = "$prefix${itemCollections.size}"
    itemCollections[name] =
      IndexedItemCollection(
        access = search.access,
        linkId = mandatoryLinkIdOfWhere(expression, search.range.last + 1),
      )
    rewritten.append(expression, copiedUpTo, search.range.first).append('%').append(name)
    copiedUpTo = search.range.last + 1
  }
  rewritten.append(expression, copiedUpTo, expression.length)

  // Parsing here, once, so that a rewrite the engine cannot parse is discarded instead of failing
  // the evaluations of an expression that parses perfectly well unrewritten.
  return try {
    extractExpressionNode(rewritten.toString())
    IndexedItemSearchExpression(rewritten.toString(), itemCollections)
  } catch (exception: Exception) {
    Timber.w("Could not parse $rewritten, evaluating $expression as it is", exception)
    null
  }
}

private data class ItemSearch(val range: IntRange, val access: ItemIndexAccess)

/** Leaf and tree searches of [expression], in source order, skipping those inside string literals. */
private fun itemSearches(expression: String): List<ItemSearch> {
  val searches = mutableListOf<ItemSearch>()
  for (match in REPEAT_ITEM_SEARCH.findAll(expression)) {
    if (!isInStringLiteral(expression, match.range.first)) {
      searches.add(ItemSearch(match.range, ItemIndexAccess.Leaf))
    }
  }
  for (match in TREE_ITEM_SEARCH.findAll(expression)) {
    if (!isInStringLiteral(expression, match.range.first)) {
      searches.add(ItemSearch(match.range, ItemIndexAccess.Tree))
    }
  }
  searches.sortBy { it.range.first }
  return searches
}

/**
 * The link ID that the `where` call starting at [index], if there is one, requires of every item it
 * selects, or `null` when it requires none.
 */
private fun mandatoryLinkIdOfWhere(expression: String, index: Int): String? {
  val whereCall = WHERE_CALL.find(expression, index)?.takeIf { it.range.first == index } ?: return null
  val condition = argumentOf(expression, whereCall.range.last) ?: return null
  return mandatoryLinkId(condition)
}

/**
 * The argument of the call whose opening parenthesis is at [openingParenthesis], or `null` when it
 * is not closed.
 */
private fun argumentOf(expression: String, openingParenthesis: Int): String? {
  var index = openingParenthesis + 1
  var depth = 1
  while (index < expression.length) {
    when (expression[index]) {
      '\'',
      '"',
      '`', -> {
        index = endOfStringLiteral(expression, index)
        continue
      }
      '(',
      '[', -> depth++
      ')',
      ']', -> {
        depth--
        if (depth == 0) return expression.substring(openingParenthesis + 1, index)
      }
    }
    index++
  }
  return null
}

/**
 * The link ID that [condition] requires of every item it selects, or `null` when it requires none.
 *
 * A link ID is only mandatory when it is compared for equality either by the whole condition or by
 * one term of a top level conjunction. Any other top level operator can let an item through on the
 * strength of a different term, so it makes the condition say nothing about link IDs at all.
 */
private fun mandatoryLinkId(condition: String): String? {
  val operators = topLevelOperators(condition)
  if (operators.any { condition.substring(it) in NON_CONJUNCTIVE_OPERATORS }) return null

  var termStart = 0
  val terms = mutableListOf<String>()
  for (operator in operators) {
    if (condition.substring(operator) == CONJUNCTION) {
      terms.add(condition.substring(termStart, operator.first))
      termStart = operator.last + 1
    }
  }
  terms.add(condition.substring(termStart))
  return terms.firstNotNullOfOrNull { linkIdEquality(it) }
}

/** The link ID [term] compares for equality, or `null` when that is not all it does. */
private fun linkIdEquality(term: String): String? {
  val literal =
    (LINK_ID_EQUALITY.matchEntire(term) ?: REVERSED_LINK_ID_EQUALITY.matchEntire(term))
      ?.groupValues
      ?.get(1)
      ?: return null
  // An escape sequence would have to be unescaped to be compared with a link ID. Link IDs needing
  // one are pathological, so the search is left unnarrowed instead.
  return literal.takeIf { !it.contains('\\') }
}

/**
 * The ranges of the words and of the `|` operators of [text] that are not nested in a parenthesis,
 * an indexer or a string literal, i.e. those that operate on the whole of [text].
 */
private fun topLevelOperators(text: String): List<IntRange> {
  val operators = mutableListOf<IntRange>()
  var index = 0
  var depth = 0
  while (index < text.length) {
    val character = text[index]
    when {
      character == '\'' || character == '"' || character == '`' -> {
        index = endOfStringLiteral(text, index)
        continue
      }
      character == '(' || character == '[' -> depth++
      character == ')' || character == ']' -> depth--
      character == '|' -> if (depth == 0) operators.add(index..index)
      character.isLetter() || character == '_' -> {
        val start = index
        while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) {
          index++
        }
        if (depth == 0) operators.add(start until index)
        continue
      }
    }
    index++
  }
  return operators
}

/** Whether [index] is inside a string literal of [expression]. */
private fun isInStringLiteral(expression: String, index: Int): Boolean {
  var position = 0
  while (position < index) {
    when (expression[position]) {
      '\'',
      '"',
      '`', -> {
        val end = endOfStringLiteral(expression, position)
        if (index < end) return true
        position = end
      }
      else -> position++
    }
  }
  return false
}

/**
 * The position just after the string literal that starts with the delimiter at [start], or the
 * length of [expression] when the literal is not closed.
 */
private fun endOfStringLiteral(expression: String, start: Int): Int {
  val delimiter = expression[start]
  var index = start + 1
  while (index < expression.length) {
    when (expression[index]) {
      '\\' -> index++
      delimiter -> return index + 1
    }
    index++
  }
  return expression.length
}
