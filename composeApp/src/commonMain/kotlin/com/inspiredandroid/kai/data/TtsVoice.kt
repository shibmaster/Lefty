package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable

/** A voice offered by a text-to-speech server: the name to send, and the server's note about it. */
@Immutable
data class TtsVoice(val id: String, val description: String? = null)
