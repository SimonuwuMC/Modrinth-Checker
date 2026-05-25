package com.example.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.ModrinthApiService
import com.example.data.ModrinthRepository

class ModrinthUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val TAG = "ModrinthUpdateWorker"
    private val CHANNEL_ID = "modrinth_updates_channel"

    override suspend fun doWork(): Result {
        Log.d(TAG, "Background update check triggered...")
        
        val database = AppDatabase.getDatabase(context)
        val apiService = ModrinthApiService.create()
        val repository = ModrinthRepository(apiService, database.modrinthDao())

        try {
            createNotificationChannel()
            val trackedProjects = repository.getTrackedProjects()
            
            for (project in trackedProjects) {
                repository.refreshProject(project.slug) { updatedProj, version ->
                    // Triggered only when a REAL new version differs from our DB's saved version
                    triggerSystemNotification(updatedProj.title, version.versionNumber)
                }
            }
            
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for Modrinth updates inside worker", e)
            return Result.retry()
        }
    }

    private fun triggerSystemNotification(projectTitle: String, versionNumber: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 
            0, 
            intent, 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat) // Standard built-in Android asset
            .setContentTitle("¡Nueva versión disponible!")
            .setContentText("Se ha lanzado la versión $versionNumber de $projectTitle en Modrinth.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(projectTitle.hashCode(), builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Modrinth Updates"
            val descriptionText = "Notifica cuando se lanza una nueva versión de tus proyectos seguidos"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
