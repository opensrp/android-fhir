/*
 * Copyright 2022 Google LLC
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

package com.google.android.fhir.workflow

import com.google.android.fhir.workflow.testing.CqlBuilder
import com.google.android.fhir.workflow.testing.PlanDefinition
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Resource
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class PlanDefinitionProcessorJavaTest {
  @Test
  fun testChildRoutineVisit() =
    PlanDefinition.Assert.that(
        "ChildRoutineVisit-PlanDefinition-1.0.0",
        "Patient/ChildRoutine-Reportable",
        null
      )
      .withData("/plan-definition/child-routine-visit/child_routine_visit_patient.json")
      .withLibrary("/plan-definition/child-routine-visit/child_routine_visit_plan_definition.json")
      .apply()
      .isEqualsTo("/plan-definition/child-routine-visit/child_routine_visit_careplan.json")

  @Test
  fun testHelloWorld() =
    PlanDefinition.Assert.that(
        "hello-world-patient-view",
        "helloworld-patient-1",
        "helloworld-patient-1-encounter-1"
      )
      .withData("/plan-definition/hello-world/hello-world-patient-data.json")
      .withLibrary("/plan-definition/hello-world/hello-world-patient-view-bundle.json")
      .apply()
      .isEqualsTo("/plan-definition/hello-world/hello-world-careplan.json")

  @Test
  @Ignore("https://github.com/google/android-fhir/issues/1890")
  fun testOpioidRec10PatientView() =
    PlanDefinition.Assert.that(
        "opioidcds-10-patient-view",
        "example-rec-10-patient-view-POS-Cocaine-drugs",
        "example-rec-10-patient-view-POS-Cocaine-drugs-prefetch"
      )
      .withData(
        "/plan-definition/opioid-Rec10-patient-view/opioid-Rec10-patient-view-patient-data.json"
      )
      .withLibrary(
        "/plan-definition/opioid-Rec10-patient-view/opioid-Rec10-patient-view-bundle.json"
      )
      .apply()
      .isEqualsTo(
        "/plan-definition/opioid-Rec10-patient-view/opioid-Rec10-patient-view-careplan.json"
      )

  @Test
  fun testRuleFiltersNotReportable() =
    PlanDefinition.Assert.that(
        "plandefinition-RuleFilters-1.0.0",
        "NotReportable",
        null,
      )
      .withData("/plan-definition/rule-filters/tests-NotReportable-bundle.json")
      .withLibrary("/plan-definition/rule-filters/RuleFilters-1.0.0-bundle.json")
      .apply()
      .isEqualsTo("/plan-definition/rule-filters/NotReportableCarePlan.json")

  @Test
  fun testRuleFiltersReportable() =
    PlanDefinition.Assert.that(
        "plandefinition-RuleFilters-1.0.0",
        "Reportable",
        null,
      )
      .withData("/plan-definition/rule-filters/tests-Reportable-bundle.json")
      .withLibrary("/plan-definition/rule-filters/RuleFilters-1.0.0-bundle.json")
      .apply()
      .isEqualsTo("/plan-definition/rule-filters/ReportableCarePlan.json")

  @Test
  fun testAncVisitContainedActivityDefinition() =
    PlanDefinition.Assert.that("MedRequest-Example", "Patient/Patient-Example")
      .withData("/plan-definition/med-request/med_request_patient.json")
      .withLibrary("/plan-definition/med-request/med_request_plan_definition.json")
      .apply()
      .isEqualsTo("/plan-definition/med-request/med_request_careplan.json")






























    val b = Bundle()
    @Test
    fun testWHOBCGPlanDefinition() =
        PlanDefinition.Assert.that("IMMZ.DT.01.BCG", "Patient/IMMZ-Patient-enadult-f")
            .also {
                File(javaClass.getResource("/who/full/activitydefinition").toURI()).listFiles()
                    .forEach {
                    b.addEntry().resource = PlanDefinition.jsonParser.parseResource(String(it.readBytes())) as Resource
                }
                val cqlPath = "/who/full/cql"
                File(javaClass.getResource(cqlPath).toURI()).listFiles()
                    .forEach {
                        CqlBuilder.compileAndBuild(it.inputStream(), librarySourceProvider(cqlPath))
                        b.addEntry().resource = PlanDefinition.jsonParser.parseResource(String(it.readBytes())) as Resource
                    }
            }
            .withData("/who/IMMZ-Immunization-BCG-data-bundle.json")
            .withLibrary("/who/IMMZ-Immunization-BCG-metadata-bundle.json")
            .apply(b)
            .isEqualsTo("???????????")

    private fun librarySourceProvider(path: String): LibrarySourceProvider {
        return LibrarySourceProvider { libraryIdentifier ->
            File(PlanDefinition.javaClass.getResource(path).toURI()).listFiles()
                .find { it.name.startsWith(libraryIdentifier!!.id) }!!.inputStream()
        }
    }
}
