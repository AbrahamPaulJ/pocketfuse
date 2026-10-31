package io.github.abrahampaulj.pocketfuse.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.abrahampaulj.pocketfuse.R
import io.github.abrahampaulj.pocketfuse.ui.AI_LABEL
import java.io.File

object Gallery {
    private val videoExtensions = setOf("mp4", "mov", "m4v", "webm", "mkv", "avi", "mpeg", "mxf", "wmv")

    fun isVideo(file: File) = file.extension.lowercase() in videoExtensions

    /** Copies a result, label burned in, into Pictures/Pocketfuse or Movies/Pocketfuse. Returns the new item's URI. */
    fun save(context: Context, file: File): Uri? {
        val source = labelled(file) ?: return null
        val video = isVideo(source)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "pocketfuse-ai-" + source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(source))
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Pocketfuse")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** Shares a file from [labelled]. A video carries the label in the share text, since its frames are not relabelled. */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mimeOf(file)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (isVideo(file)) send.putExtra(Intent.EXTRA_TEXT, context.getString(R.string.results_share_text))
        return Intent.createChooser(send, null)
    }

    /**
     * The copy of a result that leaves the phone, made off the main thread. An image gets the label
     * burned into its pixels, written to outputs/labelled/ so the Results list (top level files only)
     * never shows it; bmp is written as png because Android has no bmp encoder. A video is returned
     * as it is. Null when the image cannot be decoded (tiff) or does not fit in memory, so the caller
     * fails visibly instead of sending an unlabelled copy.
     */
    fun labelled(file: File): File? {
        if (isVideo(file)) return file
        val bitmap = try {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inMutable = true })
        } catch (error: OutOfMemoryError) {
            null
        } ?: return null
        return try {
            drawLabel(bitmap)
            val (format, extension) = encodingOf(file.extension)
            val output = File(file.parentFile, "labelled/" + file.nameWithoutExtension + "." + extension)
            output.parentFile?.mkdirs()
            // Written beside it, then renamed over it: a Save and a Share at once, or an app still
            // reading the last share, never sees half a file.
            val partial = File.createTempFile("label", ".part", output.parentFile)
            try {
                val written = partial.outputStream().use { bitmap.compress(format, 95, it) }
                if (written && partial.renameTo(output)) output else null
            } finally {
                partial.delete()
            }
        } catch (error: OutOfMemoryError) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** A copy of [bitmap] with the label drawn top left; [bitmap] itself is left clean. */
    fun burnLabel(bitmap: Bitmap): Bitmap {
        val labelled = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: throw OutOfMemoryError("label copy")
        drawLabel(labelled)
        return labelled
    }

    /** The encoder and extension for a labelled copy: jpeg and webp keep their format, everything else becomes png. */
    @Suppress("DEPRECATION")
    internal fun encodingOf(extension: String): Pair<Bitmap.CompressFormat, String> = when (val lower = extension.lowercase()) {
        "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG to lower
        "webp" -> (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP) to lower
        else -> Bitmap.CompressFormat.PNG to "png"
    }

    /** The AiLabel pill (ui/Components.kt) in pixels: white bold text on 0xAA000000, a 0x66FFFFFF border. */
    private fun drawLabel(bitmap: Bitmap) {
        val unit = maxOf(12f, minOf(bitmap.width, bitmap.height) * 0.035f)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit
            typeface = Typeface.DEFAULT_BOLD
        }
        val metrics = text.fontMetrics
        val inset = unit * 0.6f
        val padding = unit * 0.6f
        val paddingVertical = unit * 0.35f
        val box = RectF(inset, inset, inset + text.measureText(AI_LABEL) + padding * 2, inset + metrics.descent - metrics.ascent + paddingVertical * 2)
        val radius = unit * 0.4f
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(box, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt() })
        canvas.drawRoundRect(
            box,
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x66FFFFFF
                style = Paint.Style.STROKE
                strokeWidth = maxOf(1f, unit / 12f)
            },
        )
        canvas.drawText(AI_LABEL, box.left + padding, box.top + paddingVertical - metrics.ascent, text)
    }

    fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> if (isVideo(file)) "video/*" else "image/jpeg"
    }
}
