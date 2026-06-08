package com.customblocker.data.repo

import com.customblocker.data.db.BlockerDao
import com.customblocker.data.db.ProfileAppEntity
import com.customblocker.data.db.ProfileEntity
import com.customblocker.domain.engine.BlockingMode
import com.customblocker.domain.engine.EngineSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth over the Room layer. Phase 2 operates on one implicit "default"
 * profile (multi-profile UI is Phase 3). Exposes the [EngineSnapshot] stream the engine
 * consumes, plus CRUD used by the UI.
 */
@Singleton
class BlockerRepository @Inject constructor(
    private val dao: BlockerDao,
) {
    /** Create the default profile on first run; returns its id. */
    suspend fun ensureDefaultProfile(): Long {
        dao.getFirstProfile()?.let { return it.id }
        return dao.insertProfile(ProfileEntity(title = "Default", enabled = true, blockingMode = 0))
    }

    /** Live snapshot for the engine: enabled + mode + blocked packages of the first profile. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun engineSnapshot(): Flow<EngineSnapshot> =
        dao.observeFirstProfile().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(EngineSnapshot())
            } else {
                dao.observeBlockedPackages(profile.id).map { packages ->
                    EngineSnapshot(
                        enabled = profile.enabled,
                        mode = if (profile.blockingMode == 1) BlockingMode.ALLOWLIST else BlockingMode.BLOCKLIST,
                        apps = packages.toSet(),
                    )
                }
            }
        }

    /** The first profile (for the UI header: enabled toggle + mode). */
    fun observeProfile(): Flow<ProfileEntity?> = dao.observeFirstProfile()

    /** Apps in the default profile (for the block-list screen). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeApps(): Flow<List<ProfileAppEntity>> =
        dao.observeFirstProfile().flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else dao.observeApps(profile.id)
        }

    suspend fun addApp(packageName: String) {
        val id = ensureDefaultProfile()
        dao.addApp(ProfileAppEntity(profileId = id, packageName = packageName, enabled = true))
    }

    suspend fun removeApp(packageName: String) {
        val id = ensureDefaultProfile()
        dao.removeApp(id, packageName)
    }

    suspend fun setEnabled(enabled: Boolean) {
        val profile = dao.getFirstProfile() ?: ProfileEntity(id = ensureDefaultProfile(), title = "Default")
        dao.updateProfile(profile.copy(enabled = enabled))
    }

    suspend fun setMode(mode: BlockingMode) {
        val profile = dao.getFirstProfile() ?: ProfileEntity(id = ensureDefaultProfile(), title = "Default")
        dao.updateProfile(profile.copy(blockingMode = if (mode == BlockingMode.ALLOWLIST) 1 else 0))
    }
}
