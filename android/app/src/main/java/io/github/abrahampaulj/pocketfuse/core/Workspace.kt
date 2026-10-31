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

    private val _batch = MutableStateFlow<List<LocalMedia>>(emptyList())
    /** Targets shared together; each becomes its own job. Empty unless more than one was shared. */
    val batch: StateFlow<List<LocalMedia>> = _batch.asStateFlow()

    suspend fun setTarget(uri: Uri): LocalMedia? = inspect(uri)?.also {
        _target.value = it
        _batch.value = emptyList()
    }

    /** Several shared files: the first becomes the target (for preview and Lab), all of them the batch. */
    suspend fun setBatch(uris: List<Uri>): List<LocalMedia> {
        val media = uris.take(MAX_BATCH).mapNotNull { inspect(it) }
        _target.value = media.firstOrNull()
        _batch.value = if (media.size > 1) media else emptyList()
        return media
    }

    fun clearBatch() {
        _batch.value = emptyList()
    }

    fun clearTarget() {
        _target.value = null
    }

    private val _presets = MutableStateFlow(
        prefs.getString("presets", null)?.let { runCatching { json.decodeFromString<Map<String, SwapOptions>>(it) }.getOrNull() } ?: emptyMap(),
    )
    /** Named sets of options the user saved, applied in one tap. */
    val presets: StateFlow<Map<String, SwapOptions>> = _presets.asStateFlow()

    fun savePreset(name: String) {
        val key = name.trim().take(MAX_PRESET_NAME)
        if (key.isEmpty()) return
        storePresets(_presets.value + (key to _options.value))
    }

    fun applyPreset(name: String) {
        _presets.value[name]?.let { preset -> updateOptions { preset } }
    }

    fun deletePreset(name: String) = storePresets(_presets.value - name)

    private fun storePresets(presets: Map<String, SwapOptions>) {
        _presets.value = presets
        prefs.edit().putString("presets", json.encodeToString(presets)).apply()
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
        const val MAX_BATCH = 50
        const val MAX_PRESET_NAME = 24
    }
}
