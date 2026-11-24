// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.ui.misc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.fct.tc4.R
import com.termux.x11.CmdEntryPointService
import com.termux.x11.MainActivity

/** Keeps the active container visible to Android while its desktop is in the background. */
class OmarchyKeepAliveService : Service() {
    companion object {
        private const val CHANNEL = "omarchy-desktop-session"
        private const val NOTICE = 2402
    }

    private var xserverBound = false
    private val xserverConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {}
        // CmdEntryPointService deliberately supplies no binder. Keep the binding
        // until onDestroy: it exists to protect the separate :xserver process.
        override fun onNullBinding(name: ComponentName?) {}
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.omarchy_keep_alive_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        val returnIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val notice = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.omarchy_keep_alive_title))
            .setContentText(getString(R.string.omarchy_keep_alive_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.omarchy_keep_alive_text)))
            .setContentIntent(PendingIntent.getActivity(this, 0, returnIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        // API 35+ may reject dataSync after its time budget is exhausted. Do not
        // turn that OS residency limit into a crash of the desktop process.
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTICE, notice)
            }
        } catch (error: RuntimeException) {
            Log.w("OmarchyKeepAlive", "Background residency unavailable", error)
            stopSelf()
            return
        }
        try {
            xserverBound = bindService(
                Intent(this, CmdEntryPointService::class.java),
                xserverConnection, BIND_AUTO_CREATE or BIND_IMPORTANT
            )
        } catch (error: RuntimeException) {
            Log.w("OmarchyKeepAlive", "X server priority binding unavailable", error)
        }
    }

    // The container's ViewModel owns this service. A process restart must not
    // recreate a notification that claims a dead desktop is still running.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w("OmarchyKeepAlive", "Android ended the dataSync residency time budget")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (xserverBound) {
            xserverBound = false
            try { unbindService(xserverConnection) }
            catch (error: RuntimeException) { Log.w("OmarchyKeepAlive", "X server unbind failed", error) }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
