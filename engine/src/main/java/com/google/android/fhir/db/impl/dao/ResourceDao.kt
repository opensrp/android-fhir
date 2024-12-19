/*
 * Copyright 2023-2024 Google LLC
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

package com.google.android.fhir.db.impl.dao

import androidx.annotation.VisibleForTesting
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.db.ResourceNotFoundException
import com.google.android.fhir.db.impl.entities.DateIndexEntity
import com.google.android.fhir.db.impl.entities.DateTimeIndexEntity
import com.google.android.fhir.db.impl.entities.NumberIndexEntity
import com.google.android.fhir.db.impl.entities.PositionIndexEntity
import com.google.android.fhir.db.impl.entities.QuantityIndexEntity
import com.google.android.fhir.db.impl.entities.ReferenceIndexEntity
import com.google.android.fhir.db.impl.entities.ResourceEntity
import com.google.android.fhir.db.impl.entities.StringIndexEntity
import com.google.android.fhir.db.impl.entities.TokenIndexEntity
import com.google.android.fhir.db.impl.entities.UriIndexEntity
import com.google.android.fhir.index.ResourceIndexer
import com.google.android.fhir.index.ResourceIndexer.Companion.createLocalLastUpdatedIndex
import com.google.android.fhir.index.ResourceIndices
import com.google.android.fhir.lastUpdated
import com.google.android.fhir.logicalId
import com.google.android.fhir.updateMeta
import com.google.android.fhir.versionId
import java.time.Instant
import java.util.Date
import java.util.UUID
import org.hl7.fhir.r4.model.InstantType
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType

@Dao
@VisibleForTesting
internal abstract class ResourceDao {
  // this is ugly but there is no way to inject these right now in Room as it is the one creating
  // the dao
  lateinit var resourceIndexer: ResourceIndexer

  /**
   * Updates the resource in the [ResourceEntity] and adds indexes as a result of changes made on
   * device.
   *
   * @param [resource] the resource with local (on device) updates
   * @param [timeOfLocalChange] time when the local change was made
   */
  suspend fun applyLocalUpdate(resource: Resource, timeOfLocalChange: Instant?) {
    getResourceEntity(resource.logicalId, resource.resourceType)?.let {
      val entity =
        it.copy(
          serializedResource =
            FhirContext.forR4Cached().newJsonParser().encodeResourceToString(resource),
          lastUpdatedLocal = timeOfLocalChange,
          lastUpdatedRemote = resource.meta.lastUpdated?.toInstant() ?: it.lastUpdatedRemote,
        )
      updateChanges(entity, resource)
    }
      ?: throw ResourceNotFoundException(
        resource.resourceType.name,
        resource.id,
      )
  }

  suspend fun updateResourceWithUuid(resourceUuid: UUID, updatedResource: Resource) {
    getResourceEntity(resourceUuid)?.let {
      val entity =
        it.copy(
          resourceId = updatedResource.logicalId,
          serializedResource =
            FhirContext.forR4Cached().newJsonParser().encodeResourceToString(updatedResource),
          lastUpdatedRemote = updatedResource.lastUpdated ?: it.lastUpdatedRemote,
          versionId = updatedResource.versionId ?: it.versionId,
        )
      updateChanges(entity, updatedResource)
    }
      ?: throw ResourceNotFoundException(
        resourceUuid,
      )
  }

  /**
   * Updates the resource in the [ResourceEntity] and adds indexes as a result of downloading the
   * resource from server.
   *
   * @param [resource] the resource with the remote(server) updates
   */
  private suspend fun applyRemoteUpdate(resource: Resource) {
    getResourceEntity(resource.logicalId, resource.resourceType)?.let {
      val entity =
        it.copy(
          serializedResource =
            FhirContext.forR4Cached().newJsonParser().encodeResourceToString(resource),
          lastUpdatedRemote = resource.meta.lastUpdated?.toInstant(),
          versionId = resource.versionId,
        )
      updateChanges(entity, resource)
    }
      ?: throw ResourceNotFoundException(resource.resourceType.name, resource.id)
  }

  private suspend fun updateChanges(entity: ResourceEntity, resource: Resource) {
    // The foreign key in Index entity tables is set with cascade delete constraint and
    // insertResource has REPLACE conflict resolution. So, when we do an insert to update the
    // resource, it deletes old resource and corresponding index entities (based on foreign key
    // constrain) before inserting the new resource.
    insertResource(entity)
    val index =
      ResourceIndices.Builder(resourceIndexer.index(resource))
        .apply {
          entity.lastUpdatedLocal?.let { instant ->
            addDateTimeIndex(
              createLocalLastUpdatedIndex(resource.resourceType, InstantType(Date.from(instant))),
            )
          }
        }
        .build()
    updateIndicesForResource(index, resource.resourceType, entity.resourceUuid)
  }

  suspend fun insertAllRemote(resources: List<Resource>): List<UUID> {
    return insertRemoteResource(*resources.toTypedArray())
  }

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertResource(vararg resource: ResourceEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertStringIndex(vararg stringIndexEntity: StringIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertReferenceIndex(vararg referenceIndexEntity: ReferenceIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertCodeIndex(vararg tokenIndexEntity: TokenIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertQuantityIndex(vararg quantityIndexEntity: QuantityIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertUriIndex(vararg uriIndexEntity: UriIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertDateIndex(vararg dateIndexEntity: DateIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertDateTimeIndex(vararg dateTimeIndexEntity: DateTimeIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertNumberIndex(vararg numberIndexEntity: NumberIndexEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertPositionIndex(vararg positionIndexEntity: PositionIndexEntity)

  @Query(
    """
        UPDATE ResourceEntity
        SET versionId = :versionId,
            lastUpdatedRemote = :lastUpdatedRemote
        WHERE resourceId = :resourceId
        AND resourceType = :resourceType
    """,
  )
  abstract suspend fun updateRemoteVersionIdAndLastUpdate(
    resourceId: String,
    resourceType: ResourceType,
    versionId: String?,
    lastUpdatedRemote: Instant?,
  )

  @Query(
    """
        DELETE FROM ResourceEntity
        WHERE resourceId = :resourceId AND resourceType = :resourceType""",
  )
  abstract suspend fun deleteResource(resourceId: String, resourceType: ResourceType): Int

  @Query(
    """
        SELECT serializedResource
        FROM ResourceEntity
        WHERE resourceId = :resourceId AND resourceType = :resourceType""",
  )
  abstract suspend fun getResource(resourceId: String, resourceType: ResourceType): String?

  @Query(
    """
        SELECT *
        FROM ResourceEntity
        WHERE resourceId = :resourceId AND resourceType = :resourceType
    """,
  )
  abstract suspend fun getResourceEntity(
    resourceId: String,
    resourceType: ResourceType,
  ): ResourceEntity?

  @Query(
    """
        SELECT *
        FROM ResourceEntity
        WHERE resourceUuid = :resourceUuid
    """,
  )
  abstract suspend fun getResourceEntity(
    resourceUuid: UUID,
  ): ResourceEntity?

  @RawQuery
  abstract suspend fun getResources(query: SupportSQLiteQuery): List<SerializedResourceWithUuid>

  @RawQuery
  abstract suspend fun getForwardReferencedResources(
    query: SupportSQLiteQuery,
  ): List<ForwardIncludeSearchResponse>

  @RawQuery
  abstract suspend fun getReverseReferencedResources(
    query: SupportSQLiteQuery,
  ): List<ReverseIncludeSearchResponse>

  @RawQuery abstract suspend fun countResources(query: SupportSQLiteQuery): Long

  suspend fun insertLocalResource(resource: Resource, timeOfChange: Instant) =
    insertResource(resource, lastUpdatedLocal = timeOfChange).single()

  // Check if the resource already exists using its logical ID, if it does, we just update the
  // existing [ResourceEntity]
  // Else, we insert with a new [ResourceEntity]
  private suspend fun insertRemoteResource(vararg resources: Resource): List<UUID> {
    val resourceResourceEntityPairs =
      resources.map { it to getResourceEntity(it.logicalId, it.resourceType) }
    val existingResourceEntityUuid =
      resourceResourceEntityPairs
        .filter { it.second != null }
        .map {
          val (resource, resourceEntity) = it
          applyRemoteUpdate(resource)
          resourceEntity!!.resourceUuid
        }
    val insertedResourceUuid =
      resourceResourceEntityPairs
        .filter { it.second == null }
        .map { (resource, _) -> resource }
        .let { insertResource(*it.toTypedArray(), lastUpdatedLocal = null) }
    return existingResourceEntityUuid + insertedResourceUuid
  }

  private suspend fun insertResource(
    vararg resources: Resource,
    lastUpdatedLocal: Instant?,
  ): List<UUID> {
    val indexResourceEntityPairs =
      resources.map { resource ->
        val resourceUuid = UUID.randomUUID()

        // Use the local UUID as the logical ID of the resource
        if (resource.id.isNullOrEmpty()) {
          resource.id = resourceUuid.toString()
        }

        val index =
          ResourceIndices.Builder(resourceIndexer.index(resource))
            .apply {
              lastUpdatedLocal?.let {
                addDateTimeIndex(
                  createLocalLastUpdatedIndex(resource.resourceType, InstantType(Date.from(it))),
                )
              }
            }
            .build()

        index to
          ResourceEntity(
            id = 0,
            resourceType = resource.resourceType,
            resourceUuid = resourceUuid,
            resourceId = resource.logicalId,
            serializedResource =
              FhirContext.forR4Cached().newJsonParser().encodeResourceToString(resource),
            versionId = resource.versionId,
            lastUpdatedRemote = resource.lastUpdated,
            lastUpdatedLocal = lastUpdatedLocal,
          )
      }
    val resourceEntities = indexResourceEntityPairs.map { it.second }
    insertResource(*resourceEntities.toTypedArray())

    val stringIndexes =
      indexResourceEntityPairs.flatMap {
        val (resourceIndices, resourceEntity) = it
        resourceIndices.stringIndices.map { stringIndex ->
          StringIndexEntity(
            id = 0,
            resourceType = resourceEntity.resourceType,
            index = stringIndex,
            resourceUuid = resourceEntity.resourceUuid,
          )
        }
      }
    insertStringIndex(*stringIndexes.toTypedArray())
    val referenceIndexes =
      indexResourceEntityPairs.flatMap {
        val (resourceIndices, resourceEntity) = it
        resourceIndices.referenceIndices.map { referenceIndex ->
          ReferenceIndexEntity(
            id = 0,
            resourceType = resourceEntity.resourceType,
            index = referenceIndex,
            resourceUuid = resourceEntity.resourceUuid,
          )
        }
      }
    insertReferenceIndex(*referenceIndexes.toTypedArray())

    val tokenIndexes =
      indexResourceEntityPairs.flatMap {
        val (resourceIndices, resourceEntity) = it
        resourceIndices.tokenIndices.map { tokenIndex ->
          TokenIndexEntity(
            id = 0,
            resourceType = resourceEntity.resourceType,
            index = tokenIndex,
            resourceUuid = resourceEntity.resourceUuid,
          )
        }
      }
    insertCodeIndex(*tokenIndexes.toTypedArray())

    val quantityIndexes =
      indexResourceEntityPairs.flatMap {
        val (resourceIndices, resourceEntity) = it
        resourceIndices.quantityIndices.map { quantityIndex ->
          QuantityIndexEntity(
            id = 0,
            resourceType = resourceEntity.resourceType,
            index = quantityIndex,
            resourceUuid = resourceEntity.resourceUuid,
          )
        }
      }
    insertQuantityIndex(*quantityIndexes.toTypedArray())

    val uriIndexes =
      indexResourceEntityPairs.flatMap {
        val (resourceIndices, resourceEntity) = it
        resourceIndices.uriIndices.map { uriIndex ->
          UriIndexEntity(
            id = 0,
            resourceType = resourceEntity.resourceType,
            index = uriIndex,
            resourceUuid = resourceEntity.resourceUuid,
          )
        }
      }
    insertUriIndex(*uriIndexes.toTypedArray())

    val dateIndexes =
      indexResourceEntityPairs.flatMap {
        val (resourceIndices, resourceEntity) = it
        resourceIndices.dateIndices.map { dateIndex ->
          DateIndexEntity(
            id = 0,
            resourceType = resourceEntity.resourceType,
            index = dateIndex,
            resourceUuid = resourceEntity.resourceUuid,
          )
        }
      }
    insertDateIndex(*dateIndexes.toTypedArray())

    val dateTimeIndexes =
      indexResourceEntityPairs.flatMap {
        val (resourceIndices, resourceEntity) = it
        resourceIndices.dateTimeIndices.map { dateTimeIndex ->
          DateTimeIndexEntity(
            id = 0,
            resourceType = resourceEntity.resourceType,
            index = dateTimeIndex,
            resourceUuid = resourceEntity.resourceUuid,
          )
        }
      }
    insertDateTimeIndex(*dateTimeIndexes.toTypedArray())

    val numberIndexes =
      indexResourceEntityPairs.flatMap {
        val (resourceIndices, resourceEntity) = it
        resourceIndices.numberIndices.map { numberIndex ->
          NumberIndexEntity(
            id = 0,
            resourceType = resourceEntity.resourceType,
            index = numberIndex,
            resourceUuid = resourceEntity.resourceUuid,
          )
        }
      }
    insertNumberIndex(*numberIndexes.toTypedArray())

    val positionIndexes =
      indexResourceEntityPairs.flatMap {
        val (resourceIndices, resourceEntity) = it
        resourceIndices.positionIndices.map { positionIndex ->
          PositionIndexEntity(
            id = 0,
            resourceType = resourceEntity.resourceType,
            index = positionIndex,
            resourceUuid = resourceEntity.resourceUuid,
          )
        }
      }
    insertPositionIndex(*positionIndexes.toTypedArray())

    return resourceEntities.map { it.resourceUuid }
  }

  suspend fun updateAndIndexRemoteVersionIdAndLastUpdate(
    resourceId: String,
    resourceType: ResourceType,
    versionId: String?,
    lastUpdatedRemote: Instant?,
  ) {
    getResourceEntity(resourceId, resourceType)?.let { oldResourceEntity ->
      val resource =
        FhirContext.forR4Cached()
          .newJsonParser()
          .parseResource(oldResourceEntity.serializedResource) as Resource
      resource.updateMeta(versionId, lastUpdatedRemote)
      updateResourceWithUuid(oldResourceEntity.resourceUuid, resource)
    }
  }

  private suspend fun updateIndicesForResource(
    index: ResourceIndices,
    resourceType: ResourceType,
    resourceUuid: UUID,
  ) {
    // TODO Move StringIndices to persistable types
    //  https://github.com/jingtang10/fhir-engine/issues/31
    //  we can either use room-autovalue integration or go w/ embedded data classes.
    //  we may also want to merge them:
    //  https://github.com/jingtang10/fhir-engine/issues/33
    index.stringIndices
      .map {
        StringIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        )
      }
      .let { insertStringIndex(*it.toTypedArray()) }

    index.referenceIndices
      .map {
        ReferenceIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        )
      }
      .let { insertReferenceIndex(*it.toTypedArray()) }

    index.tokenIndices
      .map {
        TokenIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        )
      }
      .let {
        insertCodeIndex(
          *it.toTypedArray(),
        )
      }

    index.quantityIndices
      .map {
        QuantityIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        )
      }
      .let {
        insertQuantityIndex(
          *it.toTypedArray(),
        )
      }

    index.uriIndices
      .map {
        UriIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        )
      }
      .let {
        insertUriIndex(
          *it.toTypedArray(),
        )
      }

    index.dateIndices
      .map {
        DateIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        )
      }
      .let {
        insertDateIndex(
          *it.toTypedArray(),
        )
      }

    index.dateTimeIndices
      .map {
        DateTimeIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        )
      }
      .let {
        insertDateTimeIndex(
          *it.toTypedArray(),
        )
      }

    index.numberIndices
      .map {
        NumberIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        )
      }
      .let {
        insertNumberIndex(
          *it.toTypedArray(),
        )
      }

    index.positionIndices
      .map {
        PositionIndexEntity(
          id = 0,
          resourceType = resourceType,
          index = it,
          resourceUuid = resourceUuid,
        )
      }
      .let {
        insertPositionIndex(
          *it.toTypedArray(),
        )
      }
  }
}

internal class ForwardIncludeSearchResponse(
  @ColumnInfo(name = "index_name") val matchingIndex: String,
  @ColumnInfo(name = "resourceUuid") val baseResourceUUID: UUID,
  val serializedResource: String,
)

internal class ReverseIncludeSearchResponse(
  @ColumnInfo(name = "index_name") val matchingIndex: String,
  @ColumnInfo(name = "index_value") val baseResourceTypeAndId: String,
  val serializedResource: String,
)

/**
 * Data class representing a forward included [Resource], index on which the match was done and the
 * uuid of the base [Resource] for which this [Resource] has been included.
 */
internal data class ForwardIncludeSearchResult(
  val searchIndex: String,
  val baseResourceUUID: UUID,
  val resource: Resource,
)

/**
 * Data class representing a reverse included [Resource], index on which the match was done and the
 * type and logical id of the base [Resource] for which this [Resource] has been included.
 */
internal data class ReverseIncludeSearchResult(
  val searchIndex: String,
  val baseResourceTypeWithId: String,
  val resource: Resource,
)

internal data class SerializedResourceWithUuid(
  @ColumnInfo(name = "resourceUuid") val uuid: UUID,
  val serializedResource: String,
)
