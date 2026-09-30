package com.inspiredandroid.kai.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.data.InstanceAdvancedSettings
import com.inspiredandroid.kai.data.Service
import com.inspiredandroid.kai.ui.KaiOutlinedTextField
import com.inspiredandroid.kai.ui.handCursor
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.ic_arrow_drop_down
import kai.composeapp.generated.resources.settings_advanced_audio_input
import kai.composeapp.generated.resources.settings_advanced_audio_input_hint
import kai.composeapp.generated.resources.settings_advanced_chars_per_token
import kai.composeapp.generated.resources.settings_advanced_compaction_enabled
import kai.composeapp.generated.resources.settings_advanced_compaction_keep_recent
import kai.composeapp.generated.resources.settings_advanced_compaction_threshold
import kai.composeapp.generated.resources.settings_advanced_connect_timeout
import kai.composeapp.generated.resources.settings_advanced_context_size
import kai.composeapp.generated.resources.settings_advanced_context_size_hint
import kai.composeapp.generated.resources.settings_advanced_default_placeholder
import kai.composeapp.generated.resources.settings_advanced_detect
import kai.composeapp.generated.resources.settings_advanced_detect_failed
import kai.composeapp.generated.resources.settings_advanced_detect_running
import kai.composeapp.generated.resources.settings_advanced_detect_success
import kai.composeapp.generated.resources.settings_advanced_failover_on_timeout
import kai.composeapp.generated.resources.settings_advanced_frequency_penalty
import kai.composeapp.generated.resources.settings_advanced_group_context
import kai.composeapp.generated.resources.settings_advanced_group_fallback
import kai.composeapp.generated.resources.settings_advanced_group_sampling
import kai.composeapp.generated.resources.settings_advanced_group_timeouts
import kai.composeapp.generated.resources.settings_advanced_group_voice
import kai.composeapp.generated.resources.settings_advanced_max_retries
import kai.composeapp.generated.resources.settings_advanced_max_tokens
import kai.composeapp.generated.resources.settings_advanced_min_p
import kai.composeapp.generated.resources.settings_advanced_presence_penalty
import kai.composeapp.generated.resources.settings_advanced_repeat_penalty
import kai.composeapp.generated.resources.settings_advanced_request_timeout
import kai.composeapp.generated.resources.settings_advanced_reset
import kai.composeapp.generated.resources.settings_advanced_retry_delay
import kai.composeapp.generated.resources.settings_advanced_sampling_hint
import kai.composeapp.generated.resources.settings_advanced_seed
import kai.composeapp.generated.resources.settings_advanced_socket_timeout
import kai.composeapp.generated.resources.settings_advanced_stop
import kai.composeapp.generated.resources.settings_advanced_stt_hint
import kai.composeapp.generated.resources.settings_advanced_stt_language
import kai.composeapp.generated.resources.settings_advanced_stt_model
import kai.composeapp.generated.resources.settings_advanced_stt_test
import kai.composeapp.generated.resources.settings_advanced_stt_test_failed
import kai.composeapp.generated.resources.settings_advanced_stt_test_running
import kai.composeapp.generated.resources.settings_advanced_stt_test_success
import kai.composeapp.generated.resources.settings_advanced_temperature
import kai.composeapp.generated.resources.settings_advanced_title
import kai.composeapp.generated.resources.settings_advanced_top_k
import kai.composeapp.generated.resources.settings_advanced_top_p
import kai.composeapp.generated.resources.settings_advanced_tts_hint
import kai.composeapp.generated.resources.settings_advanced_tts_model
import kai.composeapp.generated.resources.settings_advanced_tts_preview
import kai.composeapp.generated.resources.settings_advanced_tts_preview_failed
import kai.composeapp.generated.resources.settings_advanced_tts_preview_running
import kai.composeapp.generated.resources.settings_advanced_tts_speed
import kai.composeapp.generated.resources.settings_advanced_tts_voice
import kai.composeapp.generated.resources.settings_advanced_use_as_fallback
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * Collapsible "Advanced" section of a remote service card: timeouts, retries & fallback,
 * context & compaction, and sampling. Blank fields mean "use the default" (shown as placeholder).
 */
@Composable
internal fun AdvancedServiceSettings(
    service: Service,
    advanced: InstanceAdvancedSettings,
    detectState: ServerDetectState,
    onChange: (InstanceAdvancedSettings) -> Unit,
    onDetect: () -> Unit,
    sttTestState: ServerDetectState = ServerDetectState.Idle,
    onTestSpeechToText: () -> Unit = {},
    ttsPreviewState: ServerDetectState = ServerDetectState.Idle,
    onPreviewTextToSpeech: () -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val isLlamaCppCapable = service == Service.OpenAICompatible

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .handCursor()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.settings_advanced_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(if (expanded) 180f else 0f),
        )
    }

    if (!expanded) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Timeouts
        GroupHeader(Res.string.settings_advanced_group_timeouts)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField(Res.string.settings_advanced_request_timeout, advanced.requestTimeoutSec, InstanceAdvancedSettings.DEFAULT_REQUEST_TIMEOUT_SEC.toString(), Modifier.weight(1f)) {
                onChange(advanced.copy(requestTimeoutSec = it))
            }
            IntField(Res.string.settings_advanced_socket_timeout, advanced.socketTimeoutSec, InstanceAdvancedSettings.DEFAULT_SOCKET_TIMEOUT_SEC.toString(), Modifier.weight(1f)) {
                onChange(advanced.copy(socketTimeoutSec = it))
            }
        }
        IntField(Res.string.settings_advanced_connect_timeout, advanced.connectTimeoutSec, "10") {
            onChange(advanced.copy(connectTimeoutSec = it))
        }

        // Retries & fallback
        GroupHeader(Res.string.settings_advanced_group_fallback)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField(Res.string.settings_advanced_max_retries, advanced.maxRetries, InstanceAdvancedSettings.DEFAULT_MAX_RETRIES.toString(), Modifier.weight(1f)) {
                onChange(advanced.copy(maxRetries = it))
            }
            IntField(Res.string.settings_advanced_retry_delay, advanced.retryDelaySec, "1, 2, …", Modifier.weight(1f)) {
                onChange(advanced.copy(retryDelaySec = it))
            }
        }
        CheckRow(Res.string.settings_advanced_failover_on_timeout, advanced.effectiveFailoverOnTimeout) {
            onChange(advanced.copy(failoverOnTimeout = it.takeIf { v -> v }))
        }
        CheckRow(Res.string.settings_advanced_use_as_fallback, advanced.effectiveUseAsFallback) {
            onChange(advanced.copy(useAsFallback = it.takeUnless { v -> v }))
        }

        // Context & compaction
        GroupHeader(Res.string.settings_advanced_group_context)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField(Res.string.settings_advanced_context_size, advanced.contextWindowTokens, stringResource(Res.string.settings_advanced_default_placeholder), Modifier.weight(2f)) {
                onChange(advanced.copy(contextWindowTokens = it))
            }
            IntField(Res.string.settings_advanced_chars_per_token, advanced.charsPerToken, InstanceAdvancedSettings.DEFAULT_CHARS_PER_TOKEN.toString(), Modifier.weight(1f)) {
                onChange(advanced.copy(charsPerToken = it))
            }
        }
        Hint(Res.string.settings_advanced_context_size_hint)
        if (isLlamaCppCapable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onDetect,
                    enabled = detectState != ServerDetectState.Running,
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_advanced_detect))
                }
                Spacer(Modifier.width(12.dp))
                val statusRes = when (detectState) {
                    ServerDetectState.Idle -> null
                    ServerDetectState.Running -> Res.string.settings_advanced_detect_running
                    ServerDetectState.Success -> Res.string.settings_advanced_detect_success
                    ServerDetectState.Failed -> Res.string.settings_advanced_detect_failed
                }
                if (statusRes != null) {
                    Text(
                        text = stringResource(statusRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (detectState == ServerDetectState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        CheckRow(Res.string.settings_advanced_compaction_enabled, advanced.effectiveCompactionEnabled) {
            onChange(advanced.copy(compactionEnabled = it.takeUnless { v -> v }))
        }
        if (advanced.effectiveCompactionEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntField(Res.string.settings_advanced_compaction_threshold, advanced.compactionThresholdPct, InstanceAdvancedSettings.DEFAULT_COMPACTION_THRESHOLD_PCT.toString(), Modifier.weight(1f)) {
                    onChange(advanced.copy(compactionThresholdPct = it))
                }
                IntField(Res.string.settings_advanced_compaction_keep_recent, advanced.compactionKeepRecent, InstanceAdvancedSettings.DEFAULT_COMPACTION_KEEP_RECENT.toString(), Modifier.weight(1f)) {
                    onChange(advanced.copy(compactionKeepRecent = it))
                }
            }
        }

        // Sampling
        GroupHeader(Res.string.settings_advanced_group_sampling)
        Hint(Res.string.settings_advanced_sampling_hint)
        val serverDefault = stringResource(Res.string.settings_advanced_default_placeholder)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DoubleField(Res.string.settings_advanced_temperature, advanced.temperature, serverDefault, Modifier.weight(1f)) {
                onChange(advanced.copy(temperature = it))
            }
            DoubleField(Res.string.settings_advanced_top_p, advanced.topP, serverDefault, Modifier.weight(1f)) {
                onChange(advanced.copy(topP = it))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // top_k is not part of the OpenAI schema; only providers that accept it get the field.
            if (service == Service.OpenAICompatible || service == Service.Gemini || service == Service.Anthropic) {
                IntField(Res.string.settings_advanced_top_k, advanced.topK, serverDefault, Modifier.weight(1f)) {
                    onChange(advanced.copy(topK = it))
                }
            }
            IntField(Res.string.settings_advanced_max_tokens, advanced.maxTokens, serverDefault, Modifier.weight(1f)) {
                onChange(advanced.copy(maxTokens = it))
            }
        }
        if (isLlamaCppCapable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DoubleField(Res.string.settings_advanced_min_p, advanced.minP, serverDefault, Modifier.weight(1f)) {
                    onChange(advanced.copy(minP = it))
                }
                DoubleField(Res.string.settings_advanced_repeat_penalty, advanced.repeatPenalty, serverDefault, Modifier.weight(1f)) {
                    onChange(advanced.copy(repeatPenalty = it))
                }
            }
        }
        if (service != Service.Anthropic) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DoubleField(Res.string.settings_advanced_presence_penalty, advanced.presencePenalty, serverDefault, Modifier.weight(1f)) {
                    onChange(advanced.copy(presencePenalty = it))
                }
                DoubleField(Res.string.settings_advanced_frequency_penalty, advanced.frequencyPenalty, serverDefault, Modifier.weight(1f)) {
                    onChange(advanced.copy(frequencyPenalty = it))
                }
            }
            LongField(Res.string.settings_advanced_seed, advanced.seed, serverDefault) {
                onChange(advanced.copy(seed = it))
            }
        }
        StopField(advanced.stop) { onChange(advanced.copy(stop = it)) }

        CheckRow(Res.string.settings_advanced_audio_input, advanced.supportsAudio == true) {
            onChange(advanced.copy(supportsAudio = it.takeIf { v -> v }))
        }
        Hint(Res.string.settings_advanced_audio_input_hint)

        // Voice
        GroupHeader(Res.string.settings_advanced_group_voice)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextSettingField(Res.string.settings_advanced_stt_model, advanced.speechToTextModel, Modifier.weight(2f)) {
                onChange(advanced.copy(speechToTextModel = it))
            }
            TextSettingField(Res.string.settings_advanced_stt_language, advanced.speechLanguage, Modifier.weight(1f)) {
                onChange(advanced.copy(speechLanguage = it))
            }
        }
        Hint(Res.string.settings_advanced_stt_hint)
        if (advanced.sttModel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onTestSpeechToText,
                    enabled = sttTestState != ServerDetectState.Running,
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_advanced_stt_test))
                }
                Spacer(Modifier.width(12.dp))
                val statusRes = when (sttTestState) {
                    ServerDetectState.Idle -> null
                    ServerDetectState.Running -> Res.string.settings_advanced_stt_test_running
                    ServerDetectState.Success -> Res.string.settings_advanced_stt_test_success
                    ServerDetectState.Failed -> Res.string.settings_advanced_stt_test_failed
                }
                if (statusRes != null) {
                    Text(
                        text = stringResource(statusRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sttTestState == ServerDetectState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextSettingField(Res.string.settings_advanced_tts_model, advanced.textToSpeechModel, Modifier.weight(2f)) {
                onChange(advanced.copy(textToSpeechModel = it))
            }
            TextSettingField(Res.string.settings_advanced_tts_voice, advanced.ttsVoice, Modifier.weight(1f), placeholder = InstanceAdvancedSettings.DEFAULT_TTS_VOICE) {
                onChange(advanced.copy(ttsVoice = it))
            }
        }
        DoubleField(Res.string.settings_advanced_tts_speed, advanced.ttsSpeed, "1.0") {
            onChange(advanced.copy(ttsSpeed = it))
        }
        Hint(Res.string.settings_advanced_tts_hint)
        if (advanced.ttsModel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onPreviewTextToSpeech,
                    enabled = ttsPreviewState != ServerDetectState.Running,
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_advanced_tts_preview))
                }
                Spacer(Modifier.width(12.dp))
                val statusRes = when (ttsPreviewState) {
                    ServerDetectState.Idle, ServerDetectState.Success -> null
                    ServerDetectState.Running -> Res.string.settings_advanced_tts_preview_running
                    ServerDetectState.Failed -> Res.string.settings_advanced_tts_preview_failed
                }
                if (statusRes != null) {
                    Text(
                        text = stringResource(statusRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ttsPreviewState == ServerDetectState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!advanced.isEmpty) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onChange(InstanceAdvancedSettings()) }, modifier = Modifier.handCursor()) {
                    Text(stringResource(Res.string.settings_advanced_reset))
                }
            }
        }
    }
}

enum class ServerDetectState { Idle, Running, Success, Failed }

@Composable
private fun GroupHeader(res: StringResource) {
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(res),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Hint(res: StringResource) {
    Text(
        text = stringResource(res),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
    )
}

@Composable
private fun CheckRow(res: StringResource, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .handCursor(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(res),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * A text field bound to a nullable number. Blank commits null (default); unparsable input is kept
 * locally without committing, so typing "0." on the way to "0.7" is not rejected mid-edit.
 */
@Composable
private fun <T : Any> NullableNumberField(
    labelRes: StringResource,
    value: T?,
    placeholder: String,
    parse: (String) -> T?,
    keyboardType: KeyboardType,
    modifier: Modifier,
    onCommit: (T?) -> Unit,
) {
    var text by remember { mutableStateOf(value?.toString().orEmpty()) }
    // Follow external changes (reset, detect) without clobbering an in-progress edit of the same value.
    LaunchedEffect(value) {
        if (parse(text) != value) text = value?.toString().orEmpty()
    }
    KaiOutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            val trimmed = input.trim()
            if (trimmed.isEmpty()) {
                onCommit(null)
            } else {
                parse(trimmed)?.let(onCommit)
            }
        },
        modifier = modifier.fillMaxWidth(),
        label = { Text(stringResource(labelRes), color = MaterialTheme.colorScheme.onBackground) },
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@Composable
private fun IntField(labelRes: StringResource, value: Int?, placeholder: String, modifier: Modifier = Modifier, onCommit: (Int?) -> Unit) = NullableNumberField(labelRes, value, placeholder, { it.trim().toIntOrNull()?.takeIf { v -> v >= 0 } }, KeyboardType.Number, modifier, onCommit)

@Composable
private fun LongField(labelRes: StringResource, value: Long?, placeholder: String, modifier: Modifier = Modifier, onCommit: (Long?) -> Unit) = NullableNumberField(labelRes, value, placeholder, { it.trim().toLongOrNull() }, KeyboardType.Number, modifier, onCommit)

@Composable
private fun DoubleField(labelRes: StringResource, value: Double?, placeholder: String, modifier: Modifier = Modifier, onCommit: (Double?) -> Unit) = NullableNumberField(labelRes, value, placeholder, { it.trim().replace(',', '.').toDoubleOrNull() }, KeyboardType.Decimal, modifier, onCommit)

/** Free-text setting; blank commits null (default). */
@Composable
private fun TextSettingField(labelRes: StringResource, value: String?, modifier: Modifier = Modifier, placeholder: String? = null, onCommit: (String?) -> Unit) {
    var text by remember { mutableStateOf(value.orEmpty()) }
    LaunchedEffect(value) {
        if (text.trim().ifEmpty { null } != value) text = value.orEmpty()
    }
    KaiOutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            onCommit(input.trim().ifEmpty { null })
        },
        modifier = modifier.fillMaxWidth(),
        label = { Text(stringResource(labelRes), color = MaterialTheme.colorScheme.onBackground) },
        placeholder = placeholder?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        singleLine = true,
    )
}

/** Comma-separated stop sequences; blank clears them. */
@Composable
private fun StopField(value: List<String>?, onCommit: (List<String>?) -> Unit) {
    var text by remember { mutableStateOf(value.orEmpty().joinToString(", ")) }
    LaunchedEffect(value) {
        val parsed = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null }
        if (parsed != value) text = value.orEmpty().joinToString(", ")
    }
    KaiOutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            onCommit(input.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null })
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(Res.string.settings_advanced_stop), color = MaterialTheme.colorScheme.onBackground) },
        singleLine = true,
    )
}
