package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val slug: String,
    val title: String,
    val description: String,
    val iconUrl: String?,
    val downloads: Int,
    val lastChecked: Long = 0L,
    val lastVersionId: String? = null,
    val lastVersionNumber: String? = null,
    val lastVersionName: String? = null,
    val lastVersionDate: String? = null,
    val lastVersionType: String? = null
)

@Entity(tableName = "update_notifications")
data class UpdateNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val projectSlug: String,
    val projectTitle: String,
    val versionId: String,
    val versionNumber: String,
    val versionName: String,
    val releaseType: String, // "release", "beta", "alpha"
    val datePublished: String,
    val changelog: String?,
    val notifiedAt: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
    val gameVersions: String = "", // Comma-separated
    val loaders: String = "" // Comma-separated
)

@Dao
interface ModrinthDao {
    // Project Queries
    @Query("SELECT * FROM projects ORDER BY title ASC")
    fun getAllProjectsFlow(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects")
    suspend fun getAllProjects(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE slug = :slug COLLATE NOCASE")
    suspend fun getProjectBySlug(slug: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProjects(projects: List<ProjectEntity>)

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE slug = :slug COLLATE NOCASE")
    suspend fun deleteProjectBySlug(slug: String)

    // Notification Queries
    @Query("SELECT * FROM update_notifications ORDER BY notifiedAt DESC")
    fun getAllNotificationsFlow(): Flow<List<UpdateNotificationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: UpdateNotificationEntity)

    @Query("UPDATE update_notifications SET isRead = 1 WHERE id = :id")
    suspend fun markNotificationAsRead(id: Int)

    @Query("UPDATE update_notifications SET isRead = 1")
    suspend fun markAllNotificationsAsRead()

    @Query("DELETE FROM update_notifications")
    suspend fun clearAllNotifications()
}

@Database(entities = [ProjectEntity::class, UpdateNotificationEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modrinthDao(): ModrinthDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "modrinth_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
