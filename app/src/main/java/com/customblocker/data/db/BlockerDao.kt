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

    // --- apps within a profile ---
    @Query("SELECT * FROM profile_apps WHERE profileId = :profileId ORDER BY packageName")
    fun observeApps(profileId: Long): Flow<List<ProfileAppEntity>>

    @Query("SELECT packageName FROM profile_apps WHERE profileId = :profileId AND enabled = 1")
    fun observeBlockedPackages(profileId: Long): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addApp(app: ProfileAppEntity)

    @Query("DELETE FROM profile_apps WHERE profileId = :profileId AND packageName = :packageName")
    suspend fun removeApp(profileId: Long, packageName: String)
}
