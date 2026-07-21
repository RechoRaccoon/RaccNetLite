package com.mediaviewer.worker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.work.*
import com.mediaviewer.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

class DownloadWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_URL       = "url"
        const val KEY_FILENAME  = "filename"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_POST_ID   = "post_id"
        // ALL downloads (images + videos) go to the same DCIM folder
        const val FOLDER_NAME   = "SimpleOSFeed"

        fun enqueue(context: Context, url: String, filename: String, mimeType: String, postId: String = "") {
            if (isAlreadyDownloaded(context, postId)) return
            val data = workDataOf(KEY_URL to url, KEY_FILENAME to filename, KEY_MIME_TYPE to mimeType, KEY_POST_ID to postId)
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("dl_$postId")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("dl_$postId", ExistingWorkPolicy.KEEP, request)
        }

        fun isAlreadyDownloaded(context: Context, postId: String): Boolean {
            if (postId.isBlank()) return false
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("simpleOSFeed_${postId}_%")
            // Check both image and video stores
            return listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).any { uri ->
                context.contentResolver
                    .query(uri, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
                    ?.use { it.count > 0 } ?: false
            }
        }
    }

    override suspend fun doWork(): Result {
        val url      = inputData.getString(KEY_URL)      ?: return Result.failure()
        val filename = inputData.getString(KEY_FILENAME)  ?: return Result.failure()
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "image/jpeg"
        val postId   = inputData.getString(KEY_POST_ID)   ?: ""
        if (isAlreadyDownloaded(context, postId)) return Result.success()
        return withContext(Dispatchers.IO) {
            try { downloadFile(url, filename, mimeType); Result.success() }
            catch (e: Exception) { e.printStackTrace(); if (runAttemptCount < 3) Result.retry() else Result.failure() }
        }
    }

    private fun downloadFile(url: String, filename: String, mimeType: String) {
        val response = NetworkClient.downloadClient.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) error("HTTP ${response.code}")
        val body = response.body ?: error("Empty body")

        val isVideo = mimeType.startsWith("video")

        // Both images AND videos go to DCIM/SimpleOSFeed — same folder in gallery
        val (collection, relPath) = if (isVideo)
            Pair(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DCIM + "/$FOLDER_NAME")
        else
            Pair(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DCIM + "/$FOLDER_NAME")

        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val itemUri: Uri = resolver.insert(collection, cv) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(itemUri)?.use { out -> body.byteStream().copyTo(out) }
            cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, cv, null, null)
        } catch (e: Exception) { resolver.delete(itemUri, null, null); throw e }
    }
}

fun urlToDownloadInfo(url: String, postId: String): Triple<String, String, String> {
    val ext = url.substringAfterLast('.', "jpg").lowercase().substringBefore('?')
    val mimeType = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"
        "webp" -> "image/webp"; "mp4" -> "video/mp4"; "webm" -> "video/webm"
        else -> "image/jpeg"
    }
    return Triple(url, "simpleOSFeed_${postId}_${System.currentTimeMillis()}.$ext", mimeType)
}
