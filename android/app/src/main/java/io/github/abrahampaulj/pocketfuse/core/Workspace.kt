package io.github.abrahampaulj.pocketfuse.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.abrahampaulj.pocketfuse.media.LocalMedia
import io.github.abrahampaulj.pocketfuse.media.MediaInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What the user is working with across screens: the source faces, the target, the swap options. The
 * picked files stay on the phone (only content URIs are kept, with a persisted read grant); they reach
 * the server when a job or a preview needs them.
 */
class Workspace(private val context: Context) {
    private val prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _sources = MutableStateFlow<List<LocalMedia>>(emptyList())
    val sources: StateFlow<List<LocalMedia>> = _sources.asStateFlow()

    private val _target = MutableStateFlow<LocalMedia?>(null)
    val target: StateFlow<LocalMedia?> = _target.asStateFlow()

    private val _voice = MutableStateFlow<LocalMedia?>(null)
    /** A recording of the user's voice for lip sync; kept for the session only. */
    val voice: StateFlow<LocalMedia?> = _voice.asStateFlow()

    suspend fun setVoice(uri: Uri?) {
        _voice.value = uri?.let { inspect(it) }
    }

    private val _options = MutableStateFlow(
        prefs.getString("options", null)?.let { runCatching { json.decodeFromString<SwapOptions>(it) }.getOrNull() } ?: SwapOptions(),
    )
    val options: StateFlow<SwapOptions> = _options.asStateFlow()

    /** Called once at start, off the main thread: inspecting a picked file can mean a cloud round trip. */
    suspend fun restore() = withContext(Dispatchers.IO) {
        _sources.value = prefs.getStringSet("sources", emptySet())!!.mapNotNull { value ->
            runCatching { MediaInspector.inspect(context, Uri.parse(value)) }.getOrNull()
        }
    }

    suspend fun addSource(uri: Uri) {
        val media = inspect(uri) ?: return
        if (_sources.value.none { it.uri == uri }) _sources.value = (_sources.value + media).takeLast(MAX_SOURCES)
        prefs.edit().putStringSet("sources", _sources.value.map { it.uri.toString() }.toSet()).apply()
    }

    fun removeSource(uri: Uri) {
        _sources.value = _sources.value.filterNot { it.uri == uri }
        prefs.edit().putStringSet("sources", _sources.value.map { it.uri.toString() }.toSet()).apply()
    }

    suspend fun setTarget(uri: Uri): LocalMedia? = inspect(uri)?.also { _target.value = it }

    fun clearTarget() {
        _target.value = null
    }

    fun updateOptions(transform: (SwapOptions) -> SwapOptions) {
        _options.value = transform(_options.value)
        prefs.edit().putString("options", json.encodeToString(_options.value)).apply()
    }

    private suspend fun inspect(uri: Uri): LocalMedia? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        runCatching { MediaInspector.inspect(context, uri) }.getOrNull()
    }

    private companion object {
        const val MAX_SOURCES = 8
    }
}
