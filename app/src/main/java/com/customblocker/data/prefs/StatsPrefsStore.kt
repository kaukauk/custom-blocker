package com.customblocker.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.statsDataStore by preferencesDataStore(name = "stats")

/**
 * Per-statistics preferences. Holds the "ignore list": packages the user has chosen to exclude from
 * the Statistics dashboard (e.g. System UI), so their usage neither shows as a row nor inflates the
 * totals. Ignoring is reversible — restoring a package re-includes its existing recorded stats.
 */
@Singleton
class StatsPrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val IGNORED = stringSetPreferencesKey("stats_ignored_packages")
    }

    val ignored: Flow<Set<String>> = context.statsDataStore.data.map { it[Keys.IGNORED] ?: emptySet() }

    suspend fun ignore(packageName: String) = context.statsDataStore.edit {
        it[Keys.IGNORED] = (it[Keys.IGNORED] ?: emptySet()) + packageName
    }

    suspend fun unignore(packageName: String) = context.statsDataStore.edit {
        it[Keys.IGNORED] = (it[Keys.IGNORED] ?: emptySet()) - packageName
    }
}
