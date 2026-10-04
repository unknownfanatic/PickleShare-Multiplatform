package com.yash.multipickle

import android.app.Application
import com.yash.multipickle.core.PickleContext
import com.yash.multipickle.core.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext.startKoin

class PickleShareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PickleContext.init(this)
        startKoin {
            androidLogger()
            androidContext(this@PickleShareApplication)
            modules(appModule)
        }
    }
}
