package com.meuplayer.app

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Cache persistente e limitado para miniaturas do catálogo.
 * Fica em cacheDir (dados descartáveis), separado de histórico, preferências e catálogo.
 */
internal class ThumbnailDiskCache(context: Context) {
    private val directory = File(context.cacheDir, "doramax-thumbnail-cache").apply {
        if (!exists()) mkdirs()
    }

    fun intercept(uri: Uri): WebResourceResponse? {
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()
        val extension = path.substringAfterLast('.', "").lowercase()
        if (uri.scheme != "https" || host != IMAGE_HOST ||
            !path.startsWith("/vi/") ||
            extension !in SUPPORTED_EXTENSIONS
        ) return null

        val cacheFile = File(directory, "${sha256(uri.toString())}.$extension")
        if (cacheFile.isFile && cacheFile.length() > 0L) {
            cacheFile.setLastModified(System.currentTimeMillis())
            return response(cacheFile, mimeType(extension))
        }

        val temporaryFile = File(
            directory,
            "${cacheFile.name}.${Thread.currentThread().id}.${System.nanoTime()}.tmp"
        )
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                setRequestProperty("User-Agent", "DORAMAX-GRATIS-Android")
            }

            if (connection.responseCode !in 200..299) return null
            val contentType = connection.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                ?: return null
            if (!contentType.startsWith("image/")) return null
            if (connection.contentLengthLong > MAX_IMAGE_BYTES) return null

            var totalBytes = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > MAX_IMAGE_BYTES) {
                            throw IllegalStateException("Miniatura excede o limite permitido")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }

            if (totalBytes <= 0L) return null
            if (!temporaryFile.renameTo(cacheFile) && !cacheFile.isFile) return null
            cacheFile.setLastModified(System.currentTimeMillis())
            trimToLimit()
            return response(cacheFile, contentType)
        } catch (error: Exception) {
            Log.d(TAG, "Não foi possível armazenar miniatura; WebView fará a solicitação normal", error)
            return null
        } finally {
            temporaryFile.delete()
            connection?.disconnect()
        }
    }

    private fun response(file: File, mime: String): WebResourceResponse? =
        runCatching { WebResourceResponse(mime, null, FileInputStream(file)) }
            .onFailure { Log.w(TAG, "Falha ao abrir miniatura em cache", it) }
            .getOrNull()

    private fun trimToLimit() {
        synchronized(trimLock) {
            val files = directory.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".tmp") }
                ?: return
            var totalBytes = files.sumOf { it.length() }
            if (totalBytes <= MAX_CACHE_BYTES) return

            for (file in files.sortedBy { it.lastModified() }) {
                if (totalBytes <= MAX_CACHE_BYTES) break
                val size = file.length()
                if (file.delete()) totalBytes -= size
            }
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun mimeType(extension: String): String = when (extension) {
        "webp" -> "image/webp"
        "png" -> "image/png"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    companion object {
        private const val TAG = "DORAMAX_CACHE"
        private const val IMAGE_HOST = "i.ytimg.com"
        private const val MAX_CACHE_BYTES = 200L * 1024L * 1024L
        private const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
        private val trimLock = Any()
    }
}
