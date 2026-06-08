package com.customblocker.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 2 schema (subset of PLAN.md §6). A Profile holds a set of apps and a blocking mode.
 * Conditions (time/wifi/location/...) and websites arrive in later phases via migrations
 * (dev uses destructive fallback). A Profile with no conditions is treated as always-active.
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val enabled: Boolean = true,
    /** 0 = BLOCKLIST (block these), 1 = ALLOWLIST (allow only these). */
    val blockingMode: Int = 0,
)

@Entity(
    tableName = "profile_apps",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "packageName"], unique = true),
    ],
)
data class ProfileAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val packageName: String,
    val enabled: Boolean = true,
)
