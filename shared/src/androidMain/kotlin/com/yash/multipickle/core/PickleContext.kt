package com.yash.multipickle.core

import android.content.Context

object PickleContext {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val context: Context?
        get() = appContext
}
