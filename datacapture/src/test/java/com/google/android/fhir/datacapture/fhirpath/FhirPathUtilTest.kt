/*
 * Copyright 2022-2023 Google LLC
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
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.Patient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FhirPathUtilTest {

  @Test
  fun `evaluateToDisplay should return concatenated string for expressions evaluation on given resource`() {
    val expressions = listOf("name.given", "name.family")
    val resource =
      Patient().apply {
        addName(
          HumanName().apply {
            this.family = "Doe"
            this.addGiven("John")
          },
        )
      }

    assertThat(evaluateToDisplay(expressions, resource)).isEqualTo("John Doe")
  }

  @Test
  fun `evaluateToBase should return the same result when an expression is evaluated repeatedly`() {
    // Parsed expressions are cached and shared between evaluations, so re-evaluating an expression
    // must keep returning the result for the base it is evaluated against, and evaluating another
    // expression in between must not affect it.
    val patient =
      Patient().apply {
        addName(
          HumanName().apply {
            this.family = "Doe"
            this.addGiven("John")
          },
        )
      }

    val given = evaluateToBase(patient, "name.given").map { it.primitiveValue() }
    val family = evaluateToBase(patient, "name.family").map { it.primitiveValue() }

    assertThat(given).containsExactly("John")
    assertThat(family).containsExactly("Doe")
    assertThat(evaluateToBase(patient, "name.given").map { it.primitiveValue() }).isEqualTo(given)
    assertThat(evaluateToBase(patient, "name.family").map { it.primitiveValue() }).isEqualTo(family)
  }

  @Test
  fun `evaluateToBase should evaluate the same expression against different resources`() {
    val john = Patient().apply { addName(HumanName().apply { this.addGiven("John") }) }
    val jane = Patient().apply { addName(HumanName().apply { this.addGiven("Jane") }) }

    assertThat(evaluateToBase(john, "name.given").map { it.primitiveValue() })
      .containsExactly("John")
    assertThat(evaluateToBase(jane, "name.given").map { it.primitiveValue() })
      .containsExactly("Jane")
  }
}
