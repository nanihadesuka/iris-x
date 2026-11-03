package com.nanihadesuka.irisx

import android.app.Application
import com.nanihadesuka.irisx.data.background.KeepAliveWorker
import com.nanihadesuka.irisx.data.di.dataModule
import com.nanihadesuka.irisx.di.presentationModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class IrisXApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@IrisXApp)
            modules(dataModule, presentationModule)
        }
        KeepAliveWorker.schedule(this)
    }
}
