package com.example.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

object FileDownloader {
    private const val TAG = "FileDownloader"

    suspend fun download(
        context: Context,
        url: String,
        filename: String,
        folderUriString: String?, // Persistent SAF folder URI string
        onProgress: (Int) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        var input: InputStream? = null
        var output: OutputStream? = null
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Error de conexión: ${response.code}"))
            }
            
            val responseBody = response.body ?: return@withContext Result.failure(Exception("La respuesta está vacía"))
            val contentLength = responseBody.contentLength()
            input = responseBody.byteStream()

            val targetUri: Uri?
            if (folderUriString != null) {
                val treeUri = Uri.parse(folderUriString)
                
                val extension = filename.substringAfterLast('.', "")
                val mimeType = when (extension.lowercase()) {
                    "jar" -> "application/java-archive"
                    "mrpack" -> "application/octet-stream"
                    "zip" -> "application/zip"
                    "json" -> "application/json"
                    else -> "application/octet-stream"
                }

                val documentId = DocumentsContract.getTreeDocumentId(treeUri)
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                val createdUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    mimeType,
                    filename
                ) ?: throw Exception("No se pudo crear el archivo en la carpeta seleccionada")
                
                targetUri = createdUri
                output = context.contentResolver.openOutputStream(createdUri)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val extension = filename.substringAfterLast('.', "")
                    val mimeType = when (extension.lowercase()) {
                        "jar" -> "application/java-archive"
                        "mrpack" -> "application/octet-stream"
                        "zip" -> "application/zip"
                        "json" -> "application/json"
                        else -> "application/octet-stream"
                    }
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
                    }
                    val resolver = context.contentResolver
                    val insertedUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        ?: throw Exception("No se pudo registrar el archivo en Descargas")
                    
                    targetUri = insertedUri
                    output = resolver.openOutputStream(insertedUri)
                } else {
                    val downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadFolder.exists()) {
                        downloadFolder.mkdirs()
                    }
                    val targetFile = File(downloadFolder, filename)
                    targetUri = Uri.fromFile(targetFile)
                    output = FileOutputStream(targetFile)
                }
            }

            if (output == null) {
                throw Exception("No se puede abrir el flujo de escritura")
            }

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L

            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (contentLength > 0) {
                    val progress = ((totalBytesRead * 100) / contentLength).toInt()
                    onProgress(progress)
                }
            }
            output.flush()
            
            Result.success(targetUri)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(e)
        } finally {
            try { input?.close() } catch (e: Exception) {}
            try { output?.close() } catch (e: Exception) {}
        }
    }
}
