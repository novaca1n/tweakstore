package dev.novacain.tweakstore

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Icons and screenshots: memory LRU + files in cacheDir/img, decoded off the main thread. */
object Images {
    private val memory = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun peek(url: String): ImageBitmap? = memory.get(url)

    suspend fun load(ctx: Context, url: String, maxPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        memory.get(url)?.let { return@withContext it }
        val dir = File(ctx.cacheDir, "img").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val f = File(dir, name)
        val bytes = runCatching { if (f.exists()) f.readBytes() else Net.bytes(url).also { f.writeBytes(it) } }.getOrNull()
            ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        bmp.asImageBitmap().also { memory.put(url, it) }
    }
}

/** Remote image with a tinted placeholder while it loads (or when there is none). */
@Composable
fun RemoteImage(url: String?, modifier: Modifier, shape: Shape, placeholder: Color, maxPx: Int = 512, crop: Boolean = true) {
    val ctx = LocalContext.current
    var img by remember(url) { mutableStateOf(url?.let { Images.peek(it) }) }
    LaunchedEffect(url) {
        if (url != null && img == null) img = Images.load(ctx, url, maxPx)
    }
    Box(modifier.clip(shape).background(placeholder)) {
        img?.let {
            Image(it, contentDescription = null, modifier = Modifier.matchParentSize(),
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
        }
    }
}
