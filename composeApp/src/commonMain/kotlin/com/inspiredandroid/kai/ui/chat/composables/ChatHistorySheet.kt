@file:OptIn(ExperimentalMaterial3Api::class)

package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.ui.chat.ChatActions
import com.inspiredandroid.kai.ui.chat.ConversationSummary
import com.inspiredandroid.kai.ui.components.VerticalScrollbarForList
import com.inspiredandroid.kai.ui.components.animatedGradientBorder
import com.inspiredandroid.kai.ui.handCursor
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.chat_history_delete_content_description
import kai.composeapp.generated.resources.chat_history_empty
import kai.composeapp.generated.resources.chat_history_heartbeat_label
import kai.composeapp.generated.resources.chat_history_rename_cancel
import kai.composeapp.generated.resources.chat_history_rename_content_description
import kai.composeapp.generated.resources.chat_history_rename_hint
import kai.composeapp.generated.resources.chat_history_rename_save
import kai.composeapp.generated.resources.chat_history_rename_title
import kai.composeapp.generated.resources.chat_history_title
import kai.composeapp.generated.resources.conversation_untitled
import kai.composeapp.generated.resources.ic_history
import kai.composeapp.generated.resources.snackbar_conversation_deleted
import kai.composeapp.generated.resources.snackbar_undo
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents.Companion.Format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

private val dateFormat = Format {
    day()
    char(' ')
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    year()
}

@Composable
internal fun ChatHistorySheet(
    conversations: ImmutableList<ConversationSummary>,
    currentConversationId: String?,
    pendingConversationDeletion: String?,
    actions: ChatActions,
    onDismiss: () -> Unit,
    onConversationSelected: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val snackbarHostState = remember { SnackbarHostState() }
        val deletedMessage = stringResource(Res.string.snackbar_conversation_deleted)
        val undoLabel = stringResource(Res.string.snackbar_undo)
        val untitled = stringResource(Res.string.conversation_untitled)
        var renaming by remember { mutableStateOf<ConversationSummary?>(null) }

        renaming?.let { conversation ->
            RenameConversationDialog(
                // Untitled chats carry the "Untitled" placeholder; start those with an empty field.
                initialTitle = conversation.title.takeIf { it != untitled }.orEmpty(),
                onDismiss = { renaming = null },
                onSave = { title ->
                    actions.renameConversation(conversation.id, title)
                    renaming = null
                },
            )
        }

        LaunchedEffect(pendingConversationDeletion) {
            if (pendingConversationDeletion == null) return@LaunchedEffect
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = deletedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                actions.undoDeleteConversation()
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    text = stringResource(Res.string.chat_history_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(12.dp))

                if (conversations.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.chat_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                } else {
                    val historyListState = rememberLazyListState()
                    Box {
                        LazyColumn(
                            state = historyListState,
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(conversations, key = { it.id }) { conversation ->
                                val isActive = conversation.id == currentConversationId
                                val borderModifier = if (conversation.isInteractive) {
                                    Modifier.animatedGradientBorder(
                                        cornerRadius = 12.dp,
                                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    )
                                } else {
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(12.dp),
                                        )
                                }
                                Row(
                                    modifier = borderModifier
                                        .fillMaxWidth()
                                        .handCursor()
                                        .clickable {
                                            onConversationSelected()
                                            actions.loadConversation(conversation.id)
                                            onDismiss()
                                        }
                                        .padding(vertical = 8.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        if (conversation.isHeartbeat) {
                                            Row(
                                                modifier = Modifier
                                                    .padding(bottom = 4.dp)
                                                    .background(
                                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                                        shape = RoundedCornerShape(4.dp),
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    imageVector = vectorResource(Res.drawable.ic_history),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    modifier = Modifier.size(12.dp),
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    text = stringResource(Res.string.chat_history_heartbeat_label),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                )
                                            }
                                        }
                                        if (conversation.title.isNotEmpty()) {
                                            Text(
                                                text = conversation.title,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = if (isActive) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onBackground
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            text = formatDate(conversation.updatedAt),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (!conversation.isHeartbeat) {
                                        IconButton(
                                            modifier = Modifier.handCursor(),
                                            onClick = { renaming = conversation },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = stringResource(Res.string.chat_history_rename_content_description),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    IconButton(
                                        modifier = Modifier.handCursor(),
                                        onClick = { actions.deleteConversation(conversation.id) },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(Res.string.chat_history_delete_content_description),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            item {
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                        VerticalScrollbarForList(
                            listState = historyListState,
                            modifier = Modifier.align(CenterEnd).fillMaxHeight(),
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) { data ->
                Snackbar(snackbarData = data)
            }
        }
    }
}

@Composable
private fun RenameConversationDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initialTitle, TextRange(0, initialTitle.length))) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.chat_history_rename_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.chat_history_rename_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(value.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text) }) {
                Text(stringResource(Res.string.chat_history_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.chat_history_rename_cancel))
            }
        },
    )
}

private fun formatDate(epochMillis: Long): String = try {
    kotlin.time.Instant.fromEpochMilliseconds(epochMillis).format(dateFormat)
} catch (_: Exception) {
    ""
}
