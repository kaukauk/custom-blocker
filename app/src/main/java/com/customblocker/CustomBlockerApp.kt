package com.customblocker

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.customblocker.data.repo.BlockerRepository
import com.customblocker.domain.engine.EngineSync
import com.customblocker.service.conditions.SignalsCoordinator
import com.customblocker.service.conditions.UsageTracker
import com.customblocker.service.pkg.PackageAddedReceiver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

@HiltAndroidApp
class CustomBlockerApp : Application() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppEntryPoint {
        fun engineSync(): EngineSync
        fun signalsCoordinator(): SignalsCoordinator
        fun usageTracker(): UsageTracker
        fun repository(): BlockerRepository
    }

    override fun onCreate() {
        super.onCreate()
        // osmdroid needs a user-agent set before any tiles load, or tile servers 403.
        org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName
        val entry = EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java)
        // Restore persisted usage counters, start the live condition signals (wifi/charger), and
        // stream DB rules into the engine.
        entry.usageTracker().load()
        entry.signalsCoordinator().start()
        entry.engineSync().start()

        // Auto-block new apps: PACKAGE_ADDED isn't deliverable to manifest receivers (O+ implicit-
        // broadcast ban), so register at runtime. The process stays alive via the foreground a11y
        // service. NOT_EXPORTED — we only receive the system broadcast.
        val pkgFilter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        registerReceiver(PackageAddedReceiver(entry.repository()), pkgFilter, Context.RECEIVER_NOT_EXPORTED)
    }
}
