package com.customblocker

import android.app.Application
import com.customblocker.domain.engine.EngineSync
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
    }

    override fun onCreate() {
        super.onCreate()
        // Start streaming the DB-backed block rules into the engine for this process.
        EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java).engineSync().start()
    }
}
