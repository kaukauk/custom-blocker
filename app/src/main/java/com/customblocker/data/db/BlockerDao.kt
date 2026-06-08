package com.customblocker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockerDao {

    // --- profiles ---
    @Insert
    suspend fun insertProfile(profile: ProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: ProfileEntity)

    @Query("SELECT * FROM profiles ORDER BY id LIMIT 1")
    suspend fun getFirstProfile(): ProfileEntity?

    @Query("SELECT * FROM profiles ORDER BY id LIMIT 1")
    fun observeFirstProfile(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles ORDER BY id")
    fun observeAllProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observeProfile(id: Long): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfile(id: Long): ProfileEntity?

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfile(id: Long)

    // --- apps ---
    @Query("SELECT * FROM profile_apps ORDER BY packageName")
    fun observeAllApps(): Flow<List<ProfileAppEntity>>

    @Query("SELECT * FROM profile_apps WHERE profileId = :profileId ORDER BY packageName")
    fun observeApps(profileId: Long): Flow<List<ProfileAppEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addApp(app: ProfileAppEntity)

    @Query("DELETE FROM profile_apps WHERE profileId = :profileId AND packageName = :packageName")
    suspend fun removeApp(profileId: Long, packageName: String)

    // --- time intervals ---
    @Query("SELECT * FROM time_intervals")
    fun observeAllIntervals(): Flow<List<TimeIntervalEntity>>

    @Query("SELECT * FROM time_intervals WHERE profileId = :profileId ORDER BY fromMinuteOfDay")
    fun observeIntervals(profileId: Long): Flow<List<TimeIntervalEntity>>

    @Insert
    suspend fun addInterval(interval: TimeIntervalEntity)

    @Query("DELETE FROM time_intervals WHERE id = :id")
    suspend fun removeInterval(id: Long)

    // --- wifi networks ---
    @Query("SELECT * FROM wifi_networks")
    fun observeAllWifi(): Flow<List<WifiNetworkEntity>>

    @Query("SELECT * FROM wifi_networks WHERE profileId = :profileId ORDER BY ssid")
    fun observeWifi(profileId: Long): Flow<List<WifiNetworkEntity>>

    @Insert
    suspend fun addWifi(network: WifiNetworkEntity)

    @Query("DELETE FROM wifi_networks WHERE id = :id")
    suspend fun removeWifi(id: Long)

    // --- usage / launch limits ---
    @Query("SELECT * FROM usage_limits")
    fun observeAllUsageLimits(): Flow<List<UsageLimitEntity>>

    @Query("SELECT * FROM usage_limits WHERE profileId = :profileId")
    fun observeUsageLimits(profileId: Long): Flow<List<UsageLimitEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsageLimit(limit: UsageLimitEntity)

    @Query("DELETE FROM usage_limits WHERE profileId = :profileId AND limitType = :limitType")
    suspend fun removeUsageLimit(profileId: Long, limitType: Int)

    // --- geofences ---
    @Query("SELECT * FROM geo_addresses")
    fun observeAllGeo(): Flow<List<GeoAddressEntity>>

    @Insert
    suspend fun addGeo(geo: GeoAddressEntity)

    @Query("DELETE FROM geo_addresses WHERE id = :id")
    suspend fun removeGeo(id: Long)
}
