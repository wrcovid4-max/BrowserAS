package com.thebrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Foreground service so a running agent task isn't killed when you switch apps. Started only while a task runs; the notification has a Stop button. */
class AgentService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == "STOP") { ((application as TheBrowserApp).container.hubStopper)?.invoke(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("agents", "Agent tasks", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, "agents").setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Hydrogen agent is working")
            .setContentText(i?.getStringExtra("text") ?: "A task is running").setContentIntent(open).addAction(0, "Stop", stop).setOngoing(true).build()
        startForeground(42, n)
        return START_NOT_STICKY
    }
}
