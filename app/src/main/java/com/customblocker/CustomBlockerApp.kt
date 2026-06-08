package com.customblocker

import android.app.Application
import com.customblocker.domain.engine.EngineSync
import com.customblocker.service.conditions.SignalsCoordinator
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
    }

    override fun onCreate() {
        super.onCreate()
        // osmdroid needs a user-agent set before any tiles load, or tile servers 403.
        org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName
        val entry = EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java)
        // Start the live condition signals (wifi/charger) and stream DB rules into the engine.
        entry.signalsCoordinator().start()
        entry.engineSync().start()
    }
}
