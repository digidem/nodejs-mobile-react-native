package com.nodejsmobile.reactnative

import com.nodejsmobile.reactnative.RNNodeJsMobileModule.Companion.nodeIsReadyForAppEvents
import expo.modules.core.interfaces.ReactActivityLifecycleListener

class RNNodeJsMobileLifecycleListener : ReactActivityLifecycleListener {
    override fun onPause() {
        if (nodeIsReadyForAppEvents) {
            // send pause message to node channel
        }
    }

    override fun onResume() {
        if (nodeIsReadyForAppEvents) {
            // send resume message to node channel
        }
    }
}