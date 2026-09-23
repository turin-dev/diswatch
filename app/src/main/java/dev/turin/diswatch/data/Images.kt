package dev.turin.diswatch.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.turin.diswatch.protocol.await
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

class Images(private val context: Context, private val client: OkHttpClient) {
    enum class Kind(val bytes: Int, val maxSide: Int) {
        Avatar(512 * 1024, 64), Icon(512 * 1024, 80), Thumbnail(2 * 1024 * 1024, 180), Full(3 * 1024 * 1024, 640)
    }
    private val caches = Kind.entries.associateWith { kind -> object : LruCache<String, Bitmap>(kind.bytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    } }
    private val workers = Semaphore(2)
    suspend fun load(url: String, kind: Kind): Bitmap? = withContext(Dispatchers.IO) {
        val parsed = url.toHttpUrlOrNull() ?: return@withContext null
        if (parsed.scheme != "https" || parsed.host !in setOf("cdn.discordapp.com", "media.discordapp.net")) return@withContext null
        val sized = parsed.newBuilder().host("media.discordapp.net").setQueryParameter("width", kind.maxSide.toString())
            .setQueryParameter("height", kind.maxSide.toString()).setQueryParameter("format", "webp").build()
        val key = sized.toString()
        caches.getValue(kind).get(key)?.let { return@withContext it }
        workers.withPermit {
            caches.getValue(kind).get(key)?.let { return@withPermit it }
            val file = File.createTempFile("image-", ".tmp", context.cacheDir)
            try {
                client.newCall(Request.Builder().url(sized).build()).await().use { response ->
                    if (!response.isSuccessful) return@withPermit null
                    val body = response.body ?: return@withPermit null
                    if (body.contentLength() > 6 * 1024 * 1024) return@withPermit null
                    body.byteStream().use { input -> file.outputStream().use { out ->
                        val buffer = ByteArray(8192); var total = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            total += count; if (total > 6 * 1024 * 1024) return@withPermit null
                            out.write(buffer, 0, count)
                        }
                    } }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withPermit null
                var sample = 1
                while (bounds.outWidth / sample > kind.maxSide || bounds.outHeight / sample > kind.maxSide) sample *= 2
                currentCoroutineContext().ensureActive()
                val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                    inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
                }) ?: return@withPermit null
                currentCoroutineContext().ensureActive()
                caches.getValue(kind).put(key, bitmap)
                bitmap
            } finally { file.delete() }
        }
    }
    fun closeViewer() { caches.getValue(Kind.Full).evictAll() }
    fun trim(level: Int) {
        closeViewer()
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level in 10..15) clear()
        else { caches.getValue(Kind.Thumbnail).evictAll(); caches.getValue(Kind.Avatar).evictAll() }
    }
    fun clear() { caches.values.forEach { it.evictAll() } }
}
