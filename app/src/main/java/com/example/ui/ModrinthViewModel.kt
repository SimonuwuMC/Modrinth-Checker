package com.example.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.MainActivity
import com.example.data.*
import com.example.worker.ModrinthUpdateWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

data class ModrinthUiState(
    val projects: List<ProjectEntity> = emptyList(),
    val notifications: List<UpdateNotificationEntity> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val username: String = "Usuario"
)

class ModrinthViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "ModrinthViewModel"
    private val CHANNEL_ID = "modrinth_updates_channel"
    private val PREFS_NAME = "modrinth_prefs"
    private val KEY_USERNAME = "username"

    private val database = AppDatabase.getDatabase(application)
    private val apiService = ModrinthApiService.create()
    private val repository = ModrinthRepository(apiService, database.modrinthDao())

    private val sharedPrefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(ModrinthUiState())
    val uiState: StateFlow<ModrinthUiState> = _uiState.asStateFlow()

    init {
        // Load initial username from preferences
        val initialUsername = sharedPrefs.getString(KEY_USERNAME, "Usuario") ?: "Usuario"
        _uiState.update { it.copy(username = initialUsername) }

        // Collect local database flows
        viewModelScope.launch {
            repository.allProjects.collect { projectsList ->
                _uiState.update { it.copy(projects = projectsList) }
            }
        }

        viewModelScope.launch {
            repository.allNotifications.collect { notificationList ->
                _uiState.update { it.copy(notifications = notificationList) }
            }
        }

        // Initialize default custom projects on startup
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                repository.ensureDefaultProjectsTracked()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepopulate databases", e)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }

        // Register Periodic Background checking in WorkManager
        setupBackgroundSync()
    }

    private fun setupBackgroundSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Poll every 1 hour
        val periodicWorkRequest = PeriodicWorkRequestBuilder<ModrinthUpdateWorker>(
            1, TimeUnit.HOURS
        ).setConstraints(constraints).build()

        try {
            WorkManager.getInstance(getApplication())
                .enqueueUniquePeriodicWork(
                    "ModrinthBackgroundSync",
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    periodicWorkRequest
                )
        } catch (e: Throwable) {
            Log.e(TAG, "WorkManager setup or initialization failed, attempting manual configuration", e)
            try {
                val config = androidx.work.Configuration.Builder()
                    .setMinimumLoggingLevel(Log.INFO)
                    .build()
                WorkManager.initialize(getApplication(), config)
                WorkManager.getInstance(getApplication())
                    .enqueueUniquePeriodicWork(
                        "ModrinthBackgroundSync",
                        androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                        periodicWorkRequest
                    )
            } catch (ex: Throwable) {
                Log.e(TAG, "Complete fail-safe: WorkManager couldn't be initialized", ex)
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null, successMessage = null) }
            var newTotal = 0
            var failedCount = 0

            val currentProjects = _uiState.value.projects
            if (currentProjects.isEmpty()) {
                // If DB is empty, try to fetch defaults
                try {
                    repository.ensureDefaultProjectsTracked()
                } catch (e: Exception) {
                    failedCount++
                }
            } else {
                for (proj in currentProjects) {
                    val result = repository.refreshProject(proj.slug) { updatedProj, version ->
                        triggerSystemNotification(updatedProj.title, version.versionNumber)
                        newTotal++
                    }
                    if (result.isFailure) {
                        failedCount++
                    } else if (result.getOrDefault(false)) {
                        newTotal++
                    }
                }
            }

            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    successMessage = if (newTotal > 0) "¡Se encontraron $newTotal nuevas versiones!" else "Datos actualizados, no hay versiones nuevas.",
                    errorMessage = if (failedCount > 0) "Error al actualizar $failedCount proyectos" else null
                )
            }
        }
    }

    fun addProject(slugInput: String) {
        val trimmed = slugInput.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "El nombre del proyecto no puede estar vacío") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null, successMessage = null) }
            val result = repository.addProjectBySlug(trimmed)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        successMessage = "¡Proyecto '${result.getOrNull()?.title}' añadido con éxito!"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = "No se pudo encontrar el proyecto '$trimmed' en Modrinth. Revisa el slug."
                    )
                }
            }
        }
    }

    fun deleteProject(slug: String) {
        viewModelScope.launch {
            repository.deleteProject(slug)
            _uiState.update { it.copy(successMessage = "Proyecto eliminado de la lista.") }
        }
    }

    fun markAsRead(id: Int) {
        viewModelScope.launch {
            repository.markAsRead(id)
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            repository.markAllAsRead()
            _uiState.update { it.copy(successMessage = "Todas las notificaciones marcadas como leídas") }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            _uiState.update { it.copy(successMessage = "Historial de notificaciones borrado") }
        }
    }

    fun dismissSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }

    fun dismissErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // Developer Test: simulate version update to verify notification flow
    fun simulateVersionUpdate(slug: String) {
        viewModelScope.launch {
            val project = _uiState.value.projects.firstOrNull { it.slug == slug } ?: return@launch
            
            val currentVersionNumber = project.lastVersionNumber ?: "1.0.0"
            // Increment the version number for simulation
            val nextVersion = try {
                val parts = currentVersionNumber.split(".")
                if (parts.size >= 3) {
                    val patch = parts[2].toIntOrNull() ?: 0
                    "${parts[0]}.${parts[1]}.${patch + 1}"
                } else {
                    "$currentVersionNumber.1"
                }
            } catch (e: Exception) {
                "$currentVersionNumber-sim"
            }

            val mockChangelog = """
                # Simulación de Actualización para ${project.title}
                
                ¡Esta es una actualización simulada en vivo para probar el sistema de notificaciones!
                
                ## Novedades:
                * Agregada compatibilidad de ejemplo con la última actualización.
                * Corrección automática de renderizado de texturas de prueba.
                * Optimización de rendimiento y FPS en Pojav.
                
                *Disparado por simulación local de prueba.*
            """.trimIndent()

            repository.simulateMockUpdate(slug, nextVersion, mockChangelog)
            triggerSystemNotification(project.title, nextVersion)
            
            _uiState.update { 
                it.copy(successMessage = "¡Actualización simulada para ${project.title}! Revisa las notificaciones del sistema.")
            }
        }
    }

    fun updateUsername(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) {
            sharedPrefs.edit().putString(KEY_USERNAME, trimmed).apply()
            _uiState.update { it.copy(username = trimmed) }
        }
    }

    private fun triggerSystemNotification(projectTitle: String, versionNumber: String) {
        val context = getApplication<Application>()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Modrinth Updates"
            val descriptionText = "Notifica cuando se lanza una nueva versión de tus proyectos seguidos"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Intent to launch our main app
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
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Nueva versión de $projectTitle")
            .setContentText("¡La versión $versionNumber ya está disponible en Modrinth!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(projectTitle.hashCode(), builder.build())
    }
}
