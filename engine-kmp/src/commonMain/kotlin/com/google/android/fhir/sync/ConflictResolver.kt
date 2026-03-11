/*
 * Copyright 2025-2026 Google LLC
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

package com.google.android.fhir.sync

import com.google.fhir.model.r4.Resource

// TODO: Phase 6 — Full conflict resolver implementation

/** Resolves conflicts between local and remote FHIR resources during synchronization. */
fun interface ConflictResolver {
  fun resolve(local: Resource, remote: Resource): ConflictResolutionResult
}

/** The result of resolving a conflict between local and remote resources. */
sealed class ConflictResolutionResult {
  object AcceptLocal : ConflictResolutionResult()

  object AcceptRemote : ConflictResolutionResult()
}

/** A [ConflictResolver] that always accepts the local version of the resource. */
object AcceptLocalConflictResolver : ConflictResolver {
  override fun resolve(local: Resource, remote: Resource) = ConflictResolutionResult.AcceptLocal
}

/** A [ConflictResolver] that always accepts the remote version of the resource. */
object AcceptRemoteConflictResolver : ConflictResolver {
  override fun resolve(local: Resource, remote: Resource) = ConflictResolutionResult.AcceptRemote
}
