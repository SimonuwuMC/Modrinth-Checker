package com.example.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ModrinthRepository(
    private val apiService: ModrinthApiService,
    private val modrinthDao: ModrinthDao
) {
    private val TAG = "ModrinthRepository"

    val allProjects: Flow<List<ProjectEntity>> = modrinthDao.getAllProjectsFlow()
    val allNotifications: Flow<List<UpdateNotificationEntity>> = modrinthDao.getAllNotificationsFlow()

    suspend fun getTrackedProjects(): List<ProjectEntity> {
        return modrinthDao.getAllProjects()
    }

    suspend fun addProjectBySlug(slug: String): Result<ProjectEntity> {
        return try {
            val projectResponse = apiService.getProject(slug)
            val versions = apiService.getProjectVersions(slug)
            val latestVersion = versions.firstOrNull()

            val entity = ProjectEntity(
                id = projectResponse.id,
                slug = projectResponse.slug,
                title = projectResponse.title,
                description = projectResponse.description,
                iconUrl = projectResponse.iconUrl,
                downloads = projectResponse.downloads,
                lastChecked = System.currentTimeMillis(),
                lastVersionId = latestVersion?.id,
                lastVersionNumber = latestVersion?.versionNumber,
                lastVersionName = latestVersion?.name,
                lastVersionDate = latestVersion?.datePublished,
                lastVersionType = latestVersion?.versionType
            )
            modrinthDao.insertProject(entity)

            // If we have a latest version, save it in the history log so the feed has content
            if (latestVersion != null) {
                val gameVersions = latestVersion.gameVersions.joinToString(", ")
                val loaders = latestVersion.loaders.joinToString(", ")
                modrinthDao.insertNotification(
                    UpdateNotificationEntity(
                        projectSlug = entity.slug,
                        projectTitle = entity.title,
                        versionId = latestVersion.id,
                        versionNumber = latestVersion.versionNumber,
                        versionName = latestVersion.name,
                        releaseType = latestVersion.versionType,
                        datePublished = latestVersion.datePublished,
                        changelog = latestVersion.changelog,
                        notifiedAt = System.currentTimeMillis(),
                        isRead = false,
                        gameVersions = gameVersions,
                        loaders = loaders
                    )
                )
            }

            Result.success(entity)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding project $slug", e)
            Result.failure(e)
        }
    }

    // Ensures the two core custom projects requested by the user are automatically tracked on launch
    suspend fun ensureDefaultProjectsTracked() {
        val defaults = listOf(
            "simonuwu-fabric-project",
            "simonuwu-fabric-project-for-pojav"
        )
        for (slug in defaults) {
            val existing = modrinthDao.getProjectBySlug(slug)
            if (existing == null) {
                Log.d(TAG, "Default project $slug not found, tracking it...")
                addProjectBySlug(slug)
            }
        }
    }

    // Refresh version info. Returns true if a NEW version was detected and notified.
    suspend fun refreshProject(slug: String, onNewVersion: suspend (ProjectEntity, VersionResponse) -> Unit = { _, _ -> }): Result<Boolean> {
        return try {
            val existing = modrinthDao.getProjectBySlug(slug) ?: return Result.failure(Exception("Project not tracked"))
            
            val projectResponse = apiService.getProject(slug)
            val versions = apiService.getProjectVersions(slug)
            val latestVersion = versions.firstOrNull()

            if (latestVersion == null) {
                // Update checked timestamp
                val updated = existing.copy(
                    lastChecked = System.currentTimeMillis(),
                    downloads = projectResponse.downloads
                )
                modrinthDao.insertProject(updated)
                return Result.success(false)
            }

            val isNewVersion = existing.lastVersionId != null && existing.lastVersionId != latestVersion.id
            val isFirstFetch = existing.lastVersionId == null

            val updatedEntity = existing.copy(
                title = projectResponse.title,
                description = projectResponse.description,
                iconUrl = projectResponse.iconUrl,
                downloads = projectResponse.downloads,
                lastChecked = System.currentTimeMillis(),
                lastVersionId = latestVersion.id,
                lastVersionNumber = latestVersion.versionNumber,
                lastVersionName = latestVersion.name,
                lastVersionDate = latestVersion.datePublished,
                lastVersionType = latestVersion.versionType
            )
            modrinthDao.insertProject(updatedEntity)

            if (isNewVersion || isFirstFetch) {
                val gameVersions = latestVersion.gameVersions.joinToString(", ")
                val loaders = latestVersion.loaders.joinToString(", ")
                
                modrinthDao.insertNotification(
                    UpdateNotificationEntity(
                        projectSlug = updatedEntity.slug,
                        projectTitle = updatedEntity.title,
                        versionId = latestVersion.id,
                        versionNumber = latestVersion.versionNumber,
                        versionName = latestVersion.name,
                        releaseType = latestVersion.versionType,
                        datePublished = latestVersion.datePublished,
                        changelog = latestVersion.changelog,
                        notifiedAt = System.currentTimeMillis(),
                        isRead = false,
                        gameVersions = gameVersions,
                        loaders = loaders
                    )
                )

                // Trigger callback for real notification on new version updates (exclude first-time discovery list loads)
                if (isNewVersion) {
                    onNewVersion(updatedEntity, latestVersion)
                }
            }

            Result.success(isNewVersion)
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing project $slug", e)
            Result.failure(e)
        }
    }

    suspend fun markAsRead(id: Int) {
        modrinthDao.markNotificationAsRead(id)
    }

    suspend fun markAllAsRead() {
        modrinthDao.markAllNotificationsAsRead()
    }

    suspend fun clearHistory() {
        modrinthDao.clearAllNotifications()
    }

    suspend fun deleteProject(slug: String) {
        modrinthDao.deleteProjectBySlug(slug)
    }

    // Helper for manual developer simulations
    suspend fun simulateMockUpdate(slug: String, mockVersionNumber: String, mockChangelog: String) {
        val existing = modrinthDao.getProjectBySlug(slug) ?: return
        
        val newVersionId = "sim-${System.currentTimeMillis()}"
        val formattedDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

        val updatedEntity = existing.copy(
            lastVersionId = newVersionId,
            lastVersionNumber = mockVersionNumber,
            lastVersionName = "${existing.title} $mockVersionNumber",
            lastVersionDate = formattedDate,
            lastChecked = System.currentTimeMillis()
        )
        modrinthDao.insertProject(updatedEntity)

        modrinthDao.insertNotification(
            UpdateNotificationEntity(
                projectSlug = existing.slug,
                projectTitle = existing.title,
                versionId = newVersionId,
                versionNumber = mockVersionNumber,
                versionName = "${existing.title} $mockVersionNumber",
                releaseType = listOf("release", "beta", "alpha").random(),
                datePublished = formattedDate,
                changelog = mockChangelog,
                notifiedAt = System.currentTimeMillis(),
                isRead = false,
                gameVersions = "1.20.1, 1.20.4",
                loaders = "fabric"
            )
        )
    }
}
