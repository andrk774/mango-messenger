package com.mango.core.data.outbox

import com.mango.core.database.entity.OutboxOperationEntity
import com.mango.core.database.entity.OutboxStatus

/**
 * Единственное место, где создаётся запись очереди.
 *
 * Выделено после ошибки: статус операции дублировался в двух репозиториях,
 * и в одном из них зависимость не переводила операцию в BLOCKED. Инвариант
 * «есть зависимость → статус BLOCKED» должен задаваться один раз.
 */
internal fun outboxOperation(
    clientOpId: String,
    opType: String,
    entityType: String,
    entityId: String,
    payloadJson: String,
    nowMillis: Long,
    dependsOnOpId: String? = null,
): OutboxOperationEntity = OutboxOperationEntity(
    id = clientOpId,
    clientOpId = clientOpId,
    opType = opType,
    entityType = entityType,
    entityId = entityId,
    payloadJson = payloadJson,
    status = if (dependsOnOpId == null) OutboxStatus.PENDING else OutboxStatus.BLOCKED,
    attemptCount = 0,
    nextAttemptAt = nowMillis,
    lastError = null,
    dependsOnOpId = dependsOnOpId,
    createdAt = nowMillis,
    updatedAt = nowMillis,
)
