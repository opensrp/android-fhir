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
import org.junit.Test

class IndexedItemSearchExpressionTest {

  @Test
  fun `should not rewrite an expression without an item search`() {
    assertThat(indexedItemSearchExpression("answer.value")).isNull()
    assertThat(indexedItemSearchExpression("%resource.descendants().where(linkId='a')")).isNull()
  }

  @Test
  fun `should replace a tree item search with the items of the link id it requires`() {
    val indexed = indexedItemSearchExpression("%resource.item.where(linkId='a').answer.value")!!

    assertThat(indexed.expression).isEqualTo("%sdcTreeItems0.where(linkId='a').answer.value")
    assertThat(indexed.itemCollections).containsExactly("sdcTreeItems0", tree("a"))
  }

  @Test
  fun `should replace an item search with the items of the link id it requires`() {
    val indexed =
      indexedItemSearchExpression("%resource.repeat(item).where(linkId='a').answer.value")!!

    assertThat(indexed.expression).isEqualTo("%sdcRepeatItems0.where(linkId='a').answer.value")
    assertThat(indexed.itemCollections).containsExactly("sdcRepeatItems0", leaf("a"))
  }

  @Test
  fun `should keep the where condition of a narrowed search verbatim`() {
    val indexed =
      indexedItemSearchExpression(
        "%resource.repeat(item).where(linkId='a' and answer.empty().not()).select(answer.value)",
      )!!

    assertThat(indexed.expression)
      .isEqualTo(
        "%sdcRepeatItems0.where(linkId='a' and answer.empty().not()).select(answer.value)",
      )
    assertThat(indexed.itemCollections).containsExactly("sdcRepeatItems0", leaf("a"))
  }

  @Test
  fun `should require the link id of a reversed equality`() {
    val indexed = indexedItemSearchExpression("%resource.repeat(item).where('a' = linkId).answer")!!

    assertThat(indexed.itemCollections).containsExactly("sdcRepeatItems0", leaf("a"))
  }

  @Test
  fun `should tolerate whitespace in the search and in the condition`() {
    val indexed =
      indexedItemSearchExpression("%resource . repeat( item ) . where( linkId = 'a' ).answer")!!

    assertThat(indexed.expression).isEqualTo("%sdcRepeatItems0 . where( linkId = 'a' ).answer")
    assertThat(indexed.itemCollections).containsExactly("sdcRepeatItems0", leaf("a"))
  }

  @Test
  fun `should require the link id of a term of a conjunction whatever its position`() {
    val indexed =
      indexedItemSearchExpression(
        "%resource.repeat(item).where(item.where(linkId='x' or linkId='y').exists() and linkId='a')",
      )!!

    assertThat(indexed.itemCollections).containsExactly("sdcRepeatItems0", leaf("a"))
  }

  @Test
  fun `should not require a link id when the condition has a top level or`() {
    val indexed =
      indexedItemSearchExpression("%resource.repeat(item).where(linkId='a' or answer.exists())")!!

    assertThat(indexed.itemCollections).containsExactly("sdcRepeatItems0", leaf(null))
  }

  @Test
  fun `should not require a link id when the condition has a top level implies`() {
    val indexed =
      indexedItemSearchExpression(
        "%resource.repeat(item).where(linkId='a' implies answer.exists())",
      )!!

    assertThat(indexed.itemCollections).containsExactly("sdcRepeatItems0", leaf(null))
  }

  @Test
  fun `should not require a link id when the condition brackets a disjunction`() {
    val indexed =
      indexedItemSearchExpression(
        "%resource.repeat(item).where((linkId='a' or linkId='b') and answer.exists())",
      )!!

    assertThat(indexed.itemCollections).containsExactly("sdcRepeatItems0", leaf(null))
  }

  @Test
  fun `should not require a link id when the condition only excludes one`() {
    val indexed = indexedItemSearchExpression("%resource.repeat(item).where(linkId != 'a')")!!

    assertThat(indexed.itemCollections).containsExactly("sdcRepeatItems0", leaf(null))
  }

  @Test
  fun `should not require a link id when the search is not filtered`() {
    val indexed = indexedItemSearchExpression("%resource.repeat(item).linkId")!!

    assertThat(indexed.expression).isEqualTo("%sdcRepeatItems0.linkId")
    assertThat(indexed.itemCollections).containsExactly("sdcRepeatItems0", leaf(null))
  }

  @Test
  fun `should not require a link id whose literal is escaped`() {
    val indexed = indexedItemSearchExpression("""%resource.repeat(item).where(linkId='a\'b')""")!!

    assertThat(indexed.itemCollections).containsExactly("sdcRepeatItems0", leaf(null))
  }

  @Test
  fun `should replace every item search of an expression`() {
    val indexed =
      indexedItemSearchExpression(
        "%resource.repeat(item).where(linkId='a').answer.value = " +
          "%resource.repeat(item).where(linkId='b').answer.value",
      )!!

    assertThat(indexed.expression)
      .isEqualTo(
        "%sdcRepeatItems0.where(linkId='a').answer.value = " +
          "%sdcRepeatItems1.where(linkId='b').answer.value",
      )
    assertThat(indexed.itemCollections)
      .containsExactly("sdcRepeatItems0", leaf("a"), "sdcRepeatItems1", leaf("b"))
  }

  @Test
  fun `should leave an item search inside a string literal alone`() {
    val indexed =
      indexedItemSearchExpression(
        "%resource.repeat(item).where(linkId='a').answer.value = '%resource.repeat(item)'",
      )!!

    assertThat(indexed.expression)
      .isEqualTo("%sdcRepeatItems0.where(linkId='a').answer.value = '%resource.repeat(item)'")
    assertThat(indexed.itemCollections).containsExactly("sdcRepeatItems0", leaf("a"))
  }

  @Test
  fun `should not rewrite an expression that could refer to the constants introduced`() {
    assertThat(
        indexedItemSearchExpression(
          "%resource.repeat(item).where(linkId='a').answer.value + %sdcRepeatItems0",
        ),
      )
      .isNull()
    assertThat(
        indexedItemSearchExpression("%resource.item.where(linkId='a').answer.value + %sdcTreeItems0"),
      )
      .isNull()
  }

  @Test
  fun `should replace leaf and tree searches in the same expression`() {
    val indexed =
      indexedItemSearchExpression(
        "%resource.repeat(item).where(linkId='a').count() = " +
          "%resource.item.where(linkId='b').count()",
      )!!

    assertThat(indexed.expression)
      .isEqualTo("%sdcRepeatItems0.where(linkId='a').count() = %sdcTreeItems1.where(linkId='b').count()")
    assertThat(indexed.itemCollections)
      .containsExactly("sdcRepeatItems0", leaf("a"), "sdcTreeItems1", tree("b"))
  }

  @Test
  fun `should return the same rewrite for the same expression`() {
    val expression = "%resource.repeat(item).where(linkId='cached').answer.value"

    assertThat(indexedItemSearchExpression(expression))
      .isSameInstanceAs(indexedItemSearchExpression(expression))
  }

  private fun leaf(linkId: String?) = IndexedItemCollection(ItemIndexAccess.Leaf, linkId)

  private fun tree(linkId: String?) = IndexedItemCollection(ItemIndexAccess.Tree, linkId)
}
