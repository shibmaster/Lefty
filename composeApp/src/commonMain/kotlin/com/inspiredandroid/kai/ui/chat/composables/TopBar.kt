package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.audio.Speaker
import com.inspiredandroid.kai.ui.chat.ChatActions
import com.inspiredandroid.kai.ui.handCursor
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.chat_history_content_description
import kai.composeapp.generated.resources.ic_add
import kai.composeapp.generated.resources.ic_history
import kai.composeapp.generated.resources.ic_settings
import kai.composeapp.generated.resources.ic_volume_off
import kai.composeapp.generated.resources.ic_volume_up
import kai.composeapp.generated.resources.new_chat_content_description
import kai.composeapp.generated.resources.sandbox_content_description
import kai.composeapp.generated.resources.settings_content_description
import kai.composeapp.generated.resources.speech_read_thinking_aloud
import kai.composeapp.generated.resources.toggle_speech_output_content_description
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

@Composable
internal fun TopBar(
    textToSpeech: Speaker? = null,
    isSpeechOutputEnabled: Boolean,
    isSpeaking: Boolean,
    actions: ChatActions,
    readThinkingAloud: Boolean = false,
    isChatHistoryEmpty: Boolean,
    hasSavedConversations: Boolean,
    onNavigateToSettings: () -> Unit,
    isSandboxAvailable: Boolean,
    isSandboxOpen: Boolean,
    isShellExecuting: Boolean,
    onToggleSandbox: () -> Unit,
    onShowHistory: () -> Unit,
    navigationTabBar: (@Composable () -> Unit)? = null,
) {
    if (navigationTabBar != null) {
        Box(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp),
        ) {
            Row(modifier = Modifier.align(Alignment.CenterStart)) {
                LeadingButtons(textToSpeech, isSpeechOutputEnabled, isSpeaking, actions, isChatHistoryEmpty, hasSavedConversations, onShowHistory, isSandboxAvailable, isSandboxOpen, isShellExecuting, onToggleSandbox)
            }
            Box(modifier = Modifier.align(Alignment.Center)) {
                navigationTabBar()
            }
            Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                if (textToSpeech != null) {
                    SpeechToggleButton(textToSpeech, isSpeechOutputEnabled, isSpeaking, actions, readThinkingAloud)
                }
            }
        }
    } else {
        Row {
            LeadingButtons(textToSpeech, isSpeechOutputEnabled, isSpeaking, actions, isChatHistoryEmpty, hasSavedConversations, onShowHistory, isSandboxAvailable, isSandboxOpen, isShellExecuting, onToggleSandbox)
            Spacer(Modifier.weight(1f))
            if (textToSpeech != null) {
                SpeechToggleButton(textToSpeech, isSpeechOutputEnabled, isSpeaking, actions, readThinkingAloud)
            }
            IconButton(
                modifier = Modifier.handCursor(),
                onClick = onNavigateToSettings,
            ) {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_settings),
                    contentDescription = stringResource(Res.string.settings_content_description),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Composable
private fun LeadingButtons(
    textToSpeech: Speaker?,
    isSpeechOutputEnabled: Boolean,
    isSpeaking: Boolean,
    actions: ChatActions,
    isChatHistoryEmpty: Boolean,
    hasSavedConversations: Boolean,
    onShowHistory: () -> Unit,
    isSandboxAvailable: Boolean,
    isSandboxOpen: Boolean,
    isShellExecuting: Boolean,
    onToggleSandbox: () -> Unit,
) {
    if (hasSavedConversations) {
        IconButton(
            modifier = Modifier.handCursor(),
            onClick = onShowHistory,
        ) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_history),
                contentDescription = stringResource(Res.string.chat_history_content_description),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
    if (!isChatHistoryEmpty) {
        IconButton(
            modifier = Modifier.handCursor(),
            onClick = {
                if (isSpeechOutputEnabled && isSpeaking) {
                    actions.setIsSpeaking(false, "")
                    textToSpeech?.stop()
                }
                actions.startNewChat()
            },
        ) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_add),
                contentDescription = stringResource(Res.string.new_chat_content_description),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
    if (isSandboxAvailable) {
        val flashAlpha = remember { Animatable(0f) }
        LaunchedEffect(isShellExecuting) {
            if (isShellExecuting) {
                flashAlpha.snapTo(0.4f)
                flashAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                )
            }
        }
        val primary = MaterialTheme.colorScheme.primary
        val checkedContainer = primary.copy(alpha = 0.2f)
        val flashContainer = primary.copy(alpha = flashAlpha.value)
        IconToggleButton(
            checked = isSandboxOpen,
            onCheckedChange = { onToggleSandbox() },
            modifier = Modifier.handCursor(),
            colors = IconButtonDefaults.iconToggleButtonColors(
                containerColor = flashContainer,
                checkedContainerColor = if (flashAlpha.value > 0f) flashContainer else checkedContainer,
                checkedContentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Dns,
                contentDescription = stringResource(Res.string.sandbox_content_description),
                tint = if (isSandboxOpen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            )
        }
    }
}

/** Tap toggles speech output; long-press opens speech options (read the thinking aloud). */
@Composable
private fun SpeechToggleButton(
    textToSpeech: Speaker,
    isSpeechOutputEnabled: Boolean,
    isSpeaking: Boolean,
    actions: ChatActions,
    readThinkingAloud: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .handCursor()
                .combinedClickable(
                    onClick = {
                        if (isSpeechOutputEnabled && isSpeaking) {
                            actions.setIsSpeaking(false, "")
                            textToSpeech.stop()
                        }
                        actions.toggleSpeechOutput()
                    },
                    onLongClick = { menuOpen = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isSpeechOutputEnabled) {
                    vectorResource(Res.drawable.ic_volume_up)
                } else {
                    vectorResource(Res.drawable.ic_volume_off)
                },
                contentDescription = stringResource(Res.string.toggle_speech_output_content_description),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.speech_read_thinking_aloud)) },
                leadingIcon = { Checkbox(checked = readThinkingAloud, onCheckedChange = null) },
                onClick = { actions.toggleReadThinkingAloud() },
            )
        }
    }
}
