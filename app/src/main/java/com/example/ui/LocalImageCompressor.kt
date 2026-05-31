package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

object LocalImageCompressor {
    private const val TAG = "LocalImageCompressor"
    private const val FOLDER_NAME = "compressed_images"
    private val client by lazy { OkHttpClient() }

    private fun hashUrl(url: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val hash = digest.digest(url.toByteArray(Charsets.UTF_8))
            val hexString = StringBuilder()
            for (b in hash) {
                val hex = Integer.toHexString(0xff and b.toInt())
                if (hex.length == 1) hexString.append('0')
                hexString.append(hex)
            }
            hexString.toString()
        } catch (e: Exception) {
            url.replace(Regex("[^a-zA-Z0-9]"), "_")
        }
    }

    /**
     * Gets or downloads and compresses an image from the given URL.
     * Returns the local compressed [File] if successful, or null on error.
     */
    suspend fun getCompressedImage(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        if (url.isEmpty()) return@withContext null

        val dir = File(context.filesDir, FOLDER_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val name = hashUrl(url) + ".jpg"
        val localFile = File(dir, name)

        if (localFile.exists() && localFile.length() > 0) {
            Log.d(TAG, "Serving image from compressed local store: ${localFile.absolutePath}")
            return@withContext localFile
        }

        // Need to download and compress it
        try {
            Log.d(TAG, "Downloading and compressing image: $url")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download image. HTTP ${response.code}")
                    return@withContext null
                }
                
                val body = response.body ?: return@withContext null
                val bytes = body.bytes()
                
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                
                // Smart resize: limit dimensions to 800px to avoid caching unnecessarily huge images
                val maxDimension = 800
                val processedBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                    val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                    val (newWidth, newHeight) = if (bitmap.width > bitmap.height) {
                        maxDimension to (maxDimension / aspectRatio).toInt()
                    } else {
                        (maxDimension * aspectRatio).toInt() to maxDimension
                    }
                    Log.d(TAG, "Resizing image from ${bitmap.width}x${bitmap.height} to ${newWidth}x${newHeight}")
                    val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                    if (scaled != bitmap) {
                        bitmap.recycle() // release memory of original
                    }
                    scaled
                } else {
                    bitmap
                }
                
                // Compress and save as JPEG to save device storage space (75% quality is a perfect balance)
                FileOutputStream(localFile).use { fos ->
                    processedBitmap.compress(Bitmap.CompressFormat.JPEG, 75, fos)
                }
                
                // Clean up bitmap memory
                processedBitmap.recycle()
                
                Log.d(TAG, "Saved compressed image to device: ${localFile.absolutePath} (${localFile.length()} bytes)")
                return@withContext localFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache and compress image from URL: $url", e)
            // Cleanup incomplete file if any
            if (localFile.exists()) {
                localFile.delete()
            }
            return@withContext null
        }
    }

    /**
     * Calculates the total size of the compressed images folder in MB or KB.
     */
    fun getCacheSizeString(context: Context): String {
        val dir = File(context.filesDir, FOLDER_NAME)
        if (!dir.exists()) return "0 KB"
        
        var totalBytes = 0L
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                totalBytes += file.length()
            }
        }
        
        return when {
            totalBytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f MB", totalBytes.toFloat() / (1024 * 1024))
            totalBytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", totalBytes.toFloat() / 1024)
            else -> "$totalBytes bytes"
        }
    }

    /**
     * Clears all compressed cached images from the device.
     */
    fun clearCache(context: Context): Boolean {
        val dir = File(context.filesDir, FOLDER_NAME)
        if (!dir.exists()) return true
        
        var success = true
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                if (!file.delete()) {
                    success = false
                }
            }
        }
        Log.d(TAG, "Cleared local compressed images cache. Success: $success")
        return success
    }
}

@androidx.compose.runtime.Composable
fun rememberLocalCompressedImage(url: String?): Any? {
    if (url.isNullOrEmpty()) return null
    val context = androidx.compose.ui.platform.LocalContext.current
    val imageState = androidx.compose.runtime.remember(url) { 
        androidx.compose.runtime.mutableStateOf<Any>(url) 
    }

    androidx.compose.runtime.LaunchedEffect(url) {
        val cachedFile = LocalImageCompressor.getCompressedImage(context, url)
        if (cachedFile != null) {
            imageState.value = cachedFile
        }
    }

    return imageState.value
}

