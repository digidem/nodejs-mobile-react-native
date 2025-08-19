package com.nodejsmobile.reactnative

import android.content.Context
import expo.modules.core.interfaces.Package
import expo.modules.core.interfaces.ReactActivityLifecycleListener

class RNNodeJsMobilePackage : Package {
    override fun createReactActivityLifecycleListeners(activityContext: Context?): List<ReactActivityLifecycleListener?>? {
        return listOf(
            RNNodeJsMobileLifecycleListener()
        )
    }
}