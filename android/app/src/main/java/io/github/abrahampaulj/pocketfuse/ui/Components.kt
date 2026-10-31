package io.github.abrahampaulj.pocketfuse.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.abrahampaulj.pocketfuse.media.LocalMedia
import io.github.abrahampaulj.pocketfuse.media.MediaInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Section(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing()
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** What a screen shows before it has anything: an icon, one line, why, and the one thing to do next. */
@Composable
fun EmptyState(icon: Int, title: String, body: String?, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(Brush.linearGradient(listOf(VioletDeep, Violet))),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(30.dp)) }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

/** A tile that is still waiting for the PC. */
@Composable
fun Pending(modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

/** A small dark badge over video or images: fps, status. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** The visible label the challenge rules ask for on every manipulated frame. */
@Composable
fun AiLabel(modifier: Modifier = Modifier) {
    Text(
        "AI GENERATED",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier
            .background(Color(0xAA000000), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun MediaThumb(media: LocalMedia, modifier: Modifier = Modifier, maxSide: Int = 256, circle: Boolean = false) {
    val context = LocalContext.current
    var bitmap by remember(media.uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(media.uri) {
        bitmap = withContext(Dispatchers.IO) {
            MediaInspector.frameJpeg(context, media, 0, maxSide)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    }
    val shape = if (circle) CircleShape else RoundedCornerShape(12.dp)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
    }
}

@Composable
fun BytesImage(bytes: ByteArray?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Box(modifier) { bitmap?.let { Image(it, null, contentScale = contentScale, modifier = Modifier.matchParentSize()) } }
}

@Composable
fun BitmapImage(bitmap: Bitmap?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(modifier) { image?.let { Image(it, null, contentScale = contentScale, modifier = Modifier.matchParentSize()) } }
}

/** A small line chart of 0..100 values (GPU utilisation, VRAM), newest on the right. */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, color: Color = Violet, max: Double = 100.0) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val point = Offset(index * step, size.height * (1f - (value / max).coerceIn(0.0, 1.0).toFloat()))
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
fun Meter(label: String, value: String, fraction: Float?, modifier: Modifier = Modifier, color: Color = Violet) {
    Column(modifier) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (fraction != null) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier)
}

@Composable
fun Gap(height: Dp = 12.dp) = Spacer(Modifier.height(height))

@Composable
fun HGap(width: Dp = 12.dp) = Spacer(Modifier.width(width))
