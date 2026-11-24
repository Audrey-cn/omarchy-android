// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.ui.misc

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** One exit path for the notification and desktop overlay, without killing the app process. */
object OmarchyDesktopShutdown {
    private const val TAG = "OmarchyDesktopStop"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activities = WeakHashMap<Activity, Unit>()
    private var application: Application? = null
    private var keepAlive: WeakReference<OmarchyKeepAliveService>? = null
    private val coordinator = DesktopShutdownCoordinator(scope, ::finishDesktop) {
        Log.w(TAG, "Desktop cleanup failed", it)
    }

    fun register(app: Application) {
        if (application != null || Application.getProcessName() != app.packageName) return
        application = app
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { activities[activity] = Unit }
            override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        })
    }

    internal fun claimSession(owner: Any, stop: suspend () -> Unit): Boolean = coordinator.claim(owner, stop)
    internal val isStopping: Boolean get() = coordinator.isStopping
    internal fun releaseSession(owner: Any) = coordinator.release(owner)
    internal fun serviceStarted(service: OmarchyKeepAliveService) { keepAlive = WeakReference(service) }
    internal fun serviceStopped(service: OmarchyKeepAliveService) {
        if (keepAlive?.get() === service) keepAlive = null
    }

    fun requestStop() {
        scope.launch { coordinator.requestStop() }
    }

    internal fun stopKeepAliveService() {
        val service = keepAlive?.get()
        try {
            // Unbind synchronously before finishing activities so BIND_AUTO_CREATE
            // cannot immediately recreate a stopped :xserver process.
            if (service != null) service.stopForDesktopShutdown()
            else application?.let { it.stopService(Intent(it, OmarchyKeepAliveService::class.java)) }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Foreground service stop failed", error)
        }
    }

    private fun finishDesktop() {
        Global.x11SessionActive = false
        Global.userRequestedManagementPage = false
        stopKeepAliveService()
        for (activity in activities.keys.toList()) {
            try { if (!activity.isDestroyed) activity.finishAndRemoveTask() }
            catch (error: RuntimeException) { Log.w(TAG, "Activity finish failed", error) }
        }
    }
}
