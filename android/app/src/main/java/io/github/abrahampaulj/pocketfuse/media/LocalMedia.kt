package io.github.abrahampaulj.pocketfuse.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import io.github.abrahampaulj.pocketfuse.api.AssetType
import io.github.abrahampaulj.pocketfuse.api.FaceFusionClient
import io.github.abrahampaulj.pocketfuse.api.UploadPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** A photo or video the user picked or shared, inspected on the phone before anything is sent. */
data class LocalMedia(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** Why a file cannot go to the server as it is, in words for the user. Checked before uploading. */
sealed interface MediaProblem {
    val message: String

    data class TooLarge(val bytes: Long) : MediaProblem {
        override val message = "This file is ${bytes / (1024 * 1024)} MB. The server accepts at most 512 MB: trim it or lower its quality first."
    }

    data class TooManyPixels(val width: Int, val height: Int) : MediaProblem {
        override val message = "This video is ${width}x$height. The server accepts at most 4096 pixels on each side."
    }

    data class Unsupported(val extension: String) : MediaProblem {
        override val message = "The server does not accept .$extension files."
    }
}

object MediaInspector {
    /** Long side a source photo is sent at: the swap works on a face crop of 112 to 256 px, so this is ample. */
    const val MAX_SOURCE_SIDE = 2048

    fun inspect(context: Context, uri: Uri): LocalMedia {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.let { name = it }
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        val mimeType = resolver.getType(uri) ?: guessMime(name)
        if (!name.contains('.')) name += "." + extensionFor(mimeType)
        var width = 0
        var height = 0
        var duration = 0L
        // A voice recording has no frame size but has a duration; BitmapFactory has nothing to read in it.
        if (mimeType.startsWith("video/") || mimeType.startsWith("audio/")) {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) width = height.also { height = width }
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            width = bounds.outWidth
            height = bounds.outHeight
        }
        return LocalMedia(uri, name, mimeType, size, width, height, duration)
    }

    /** Problems the server would answer with 400 / 413 / 415, found before a byte is uploaded. */
    fun problems(media: LocalMedia, acceptedExtensions: (String) -> Boolean): MediaProblem? = when {
        // HEIC / HEIF / AVIF photos are turned into JPEG on the phone (prepareUpload).
        !acceptedExtensions(media.extension) && !needsJpeg(media) -> MediaProblem.Unsupported(media.extension)
        media.size > FaceFusionClient.MAX_UPLOAD_BYTES -> MediaProblem.TooLarge(media.size)
        // Oversized PHOTOS are scaled down on the phone instead (prepareUpload), videos cannot be.
        media.isVideo && maxOf(media.width, media.height) > FaceFusionClient.MAX_RESOLUTION -> MediaProblem.TooManyPixels(media.width, media.height)
        else -> null
    }

    /** Phone photo formats the server does not read but Android decodes: sent as JPEG. */
    fun needsJpeg(media: LocalMedia): Boolean = !media.isVideo && media.extension in setOf("heic", "heif", "avif")

    /**
     * Turns [media] into what the server accepts: photos above 4096 px are scaled down, HEIC / HEIF / AVIF
     * photos become JPEG, MP4 / MOV / M4A get
     * their `moov` atom moved to the front ([Faststart]). Everything else streams straight from the picker.
     * A source photo is capped at [MAX_SOURCE_SIDE]: the server hashes the whole source image on every
     * stream frame, and the face it uses is far smaller. Copies made here come back as [UploadPart.temporary].
     */
    suspend fun prepareUpload(context: Context, media: LocalMedia, type: AssetType): UploadPart = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "uploads").apply { mkdirs() }
        val maxSide = if (type == AssetType.Source) MAX_SOURCE_SIDE else FaceFusionClient.MAX_RESOLUTION
        when {
            !media.isVideo && (maxOf(media.width, media.height) > maxSide || needsJpeg(media)) -> {
                val scaled = File.createTempFile("photo", ".jpg", cache)
                (decodeUpright(context, media.uri, maxSide) ?: decodeScaled(context, media.uri, maxSide))?.let { bitmap ->
                    scaled.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, if (type == AssetType.Source) 92 else 95, it) }
                }
                fileUpload(scaled, "image/jpeg", media.name.substringBeforeLast('.') + ".jpg")
            }
            media.extension in setOf("mp4", "mov", "m4a", "m4v") -> {
                val copy = File.createTempFile("original", "." + media.extension, cache)
                context.contentResolver.openInputStream(media.uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
                val fast = File.createTempFile("faststart", "." + media.extension, cache)
                when (Faststart.rewrite(copy, fast)) {
                    is Faststart.Result.Rewritten -> fileUpload(fast, media.mimeType, media.name).also { copy.delete() }
                    else -> fileUpload(copy, media.mimeType, media.name).also { fast.delete() }
                }
            }
            else -> UploadPart(media.name, media.mimeType, media.size) { context.contentResolver.openInputStream(media.uri)!! }
        }
    }

    /** A frame to preview or stream: a photo itself, or a video frame at [atMs], as JPEG with the long side at most [maxSide]. */
    suspend fun frameJpeg(context: Context, media: LocalMedia, atMs: Long = 0, maxSide: Int = 720): ByteArray? = withContext(Dispatchers.IO) {
        val bitmap = if (media.isVideo) {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, media.uri)
                retriever.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, maxSide, maxSide)
            }
        } else {
            decodeScaled(context, media.uri, maxSide)
        } ?: return@withContext null
        bitmap.toJpeg()
    }

    fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale >= 1f) return decoded
        return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
    }

    /**
     * Like [decodeScaled], but upright: ImageDecoder applies the EXIF orientation BitmapFactory ignores, and a
     * re-encoded JPEG carries no EXIF, so a sideways portrait would reach the face detector sideways.
     */
    private fun decodeUpright(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = maxSide.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
    } catch (_: IOException) {
        null
    }

    /** Upload copies left behind by an upload the app was killed in; each finished upload deletes its own. */
    fun clearUploads(context: Context, olderThan: Long) {
        File(context.cacheDir, "uploads").listFiles()?.filter { it.lastModified() < olderThan }?.forEach { it.delete() }
    }

    private fun fileUpload(file: File, mimeType: String, name: String) =
        UploadPart(name, mimeType, file.length(), temporary = file) { file.inputStream() }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        else -> if (mimeType.startsWith("video/")) "mp4" else "jpg"
    }
}

fun Bitmap.toJpeg(quality: Int = 88): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

/** Splits a capture strip (tiles laid left to right) into tiles of [tileWidth]. */
fun splitStrip(strip: Bitmap, tileWidth: Int): List<Bitmap> {
    if (tileWidth <= 0 || strip.width < tileWidth) return listOf(strip)
    return (0 until strip.width / tileWidth).map { Bitmap.createBitmap(strip, it * tileWidth, 0, tileWidth, strip.height) }
}
