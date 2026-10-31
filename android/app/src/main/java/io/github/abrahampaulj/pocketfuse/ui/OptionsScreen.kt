package io.github.abrahampaulj.pocketfuse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.abrahampaulj.pocketfuse.R
import io.github.abrahampaulj.pocketfuse.api.Argument
import io.github.abrahampaulj.pocketfuse.api.Capabilities
import io.github.abrahampaulj.pocketfuse.api.asText
import io.github.abrahampaulj.pocketfuse.core.SwapOptions
import io.github.abrahampaulj.pocketfuse.core.Workspace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Every option the server reports in GET /capabilities, except those of processors this app never turns
 * on, grouped by section, with a control chosen from the option's shape: a slider for an expanded numeric
 * range, chips for a list, a menu for a choice, a text field for free input. Nothing here is hard-coded,
 * so a newer FaceFusion shows its new options.
 */
@Composable
fun OptionsScreen(capabilities: Capabilities?, workspace: Workspace) {
    val options by workspace.options.collectAsState()
    if (capabilities == null) {
        Hint(stringResource(R.string.options_loading), Modifier.padding(16.dp))
        return
    }
    val sections = remember(capabilities) {
        // Each processor's options sit in a section named after it; those of a processor the app never turns on change nothing.
        val processors = capabilities.choicesOf("processors")
        capabilities.arguments.values
            .filter { it.section !in HIDDEN_SECTIONS && it.key !in HIDDEN_KEYS }
            .filter { it.section !in processors || it.section in REACHABLE_PROCESSORS }
            .groupBy { it.section }
    }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Gap(4.dp)
            Hint(stringResource(R.string.options_intro))
            if (options.overrides.isNotEmpty()) {
                TextButton(onClick = { workspace.updateOptions { it.copy(overrides = emptyMap()) } }) {
                    Text(stringResource(R.string.options_reset, options.overrides.size))
                }
            }
        }
        items(sections.entries.toList(), key = { it.key }) { (section, arguments) ->
            Section(section.replace('_', ' ')) {
                arguments.forEach { argument ->
                    ArgumentControl(argument, options.overrides[argument.key]) { value -> workspace.updateOptions { it.with(argument.key, value) } }
                }
            }
        }
        item { Gap(24.dp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArgumentControl(argument: Argument, override: JsonElement?, onChange: (JsonElement?) -> Unit) {
    val current = override ?: argument.default
    val label = argument.key.removePrefix(argument.section + "_").replace('_', ' ')
    val changed = override != null
    val choices = argument.choices
    val isMenu = choices != null && !argument.isMulti && !(argument.isNumeric && choices.size > 6)
    if (isMenu) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (changed) Violet else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (changed) TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.options_default)) }
            ChoicePicker(label = null, choices = choices!!.map { it.asText() }, selected = current.asText()) { picked ->
                onChange(choices.first { it.asText() == picked })
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (changed) Violet else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (changed) TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.options_default)) }
        }
        when {
            choices == null -> {
                var text by remember(current) { mutableStateOf(current.asTextOrList()) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(onClick = { onChange(parseFree(text, argument)) }) { Text(stringResource(R.string.options_apply)) }
                    },
                )
            }
            argument.isMulti -> FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val selected = (current as? JsonArray)?.toSet().orEmpty()
                choices.forEach { choice ->
                    FilterChip(
                        selected = choice in selected,
                        onClick = {
                            val next = if (choice in selected) selected - choice else selected + choice
                            onChange(JsonArray(choices.filter { it in next }))
                        },
                        label = { Text(choice.asText()) },
                    )
                }
            }
            else -> {
                val index = choices.indexOf(current).coerceAtLeast(0)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = index.toFloat(),
                        onValueChange = { onChange(choices[it.toInt().coerceIn(0, choices.lastIndex)]) },
                        valueRange = 0f..choices.lastIndex.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(choices[index].asText(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

private fun JsonElement.asTextOrList(): String = if (this is JsonArray) joinToString(" ") { it.asText() } else asText().takeUnless { it == "null" }.orEmpty()

private fun parseFree(text: String, argument: Argument): JsonElement? {
    if (text.isBlank()) return null
    val parts = text.trim().split(Regex("\\s+"))
    fun primitive(value: String): JsonPrimitive = value.toIntOrNull()?.let(::JsonPrimitive) ?: value.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
    return if (argument.isMulti) JsonArray(parts.map(::primitive)) else primitive(text.trim())
}

/** Set by the swap screen itself, or meaningless from a phone (paths are the session's own). */
private val HIDDEN_SECTIONS = setOf("processors")
private val HIDDEN_KEYS = setOf("face_selector_mode", "processors")

/** Every processor [SwapOptions.processors] can return, with all of its switches on. */
private val REACHABLE_PROCESSORS = SwapOptions(enhance = true, restoreExpression = true, lipSync = true).processors(hasFaces = true, hasVoice = true)
