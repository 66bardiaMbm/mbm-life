package com.mbmlife.companion.tracking

internal data class TrackingIdentity(
    val uid: String,
    val familyId: String
)

/** Pure recovery rules shared by TrackingService and its restart tests. */
internal object TrackingRecoveryPolicy {
    fun shouldKeepBackgroundDelivery(
        trackingEnabled: Boolean,
        explicitStopRequested: Boolean
    ): Boolean = trackingEnabled && !explicitStopRequested

    fun resolveIdentity(
        authenticatedUid: String?,
        persistedUid: String?,
        familyId: String?
    ): TrackingIdentity? {
        val uid = authenticatedUid?.takeIf { it.isNotBlank() }
            ?: persistedUid?.takeIf { it.isNotBlank() }
            ?: return null
        val family = familyId?.takeIf { it.isNotBlank() } ?: return null
        return TrackingIdentity(uid, family)
    }

    fun nextStayStartAtMs(
        currentStayStartAtMs: Long,
        stationary: Boolean,
        arrivalAtMs: Long?,
        movementStartedAtMs: Long
    ): Long = when {
        !stationary -> 0L
        arrivalAtMs != null -> arrivalAtMs
        currentStayStartAtMs > 0L -> currentStayStartAtMs
        movementStartedAtMs > 0L -> movementStartedAtMs
        else -> 0L
    }
}
