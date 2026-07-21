package com.agent.voiceassistant.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.LinkedHashMap

class MultimodalImageEncoder(private val workspaceRoot: File) {
    private data class CacheKey(val path: String, val modifiedAt: Long, val size: Long)

    private val cache = object : LinkedHashMap<CacheKey, CloudSpeechClient.ImageInput>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CloudSpeechClient.ImageInput>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    suspend fun encode(virtualPath: String): CloudSpeechClient.ImageInput? = withContext(Dispatchers.IO) {
        val relative = virtualPath.removePrefix("/workspace/")
        val file = File(workspaceRoot, relative).canonicalFile
        require(file.toPath().startsWith(workspaceRoot.canonicalFile.toPath())) { "图片路径越界" }
        if (!file.isFile || file.length() > MAX_SOURCE_BYTES) return@withContext null
        val key = CacheKey(file.path, file.lastModified(), file.length())
        synchronized(cache) { cache[key] }?.let { return@withContext it }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION * 2 || bounds.outHeight / sample > MAX_DIMENSION * 2) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        val scaled = scale(bitmap)
        if (scaled !== bitmap) bitmap.recycle()
        val bytes = ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            output.toByteArray()
        }
        scaled.recycle()
        val result = CloudSpeechClient.ImageInput("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
        synchronized(cache) { cache[key] = result }
        result
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        private const val MAX_DIMENSION = 2_048
        private const val JPEG_QUALITY = 85
        private const val MAX_SOURCE_BYTES = 20L * 1_024 * 1_024
        private const val MAX_CACHE_ENTRIES = 8
    }
}
