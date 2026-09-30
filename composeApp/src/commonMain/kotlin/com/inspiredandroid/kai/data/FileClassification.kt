package com.inspiredandroid.kai.data

enum class FileCategory {
    IMAGE,
    TEXT,
    PDF,
    AUDIO,
    UNSUPPORTED,
}

const val MAX_TEXT_FILE_BYTES = 200_000
const val MAX_PDF_BYTES = 20_000_000
const val MAX_IMAGE_BYTES = 15_000_000
const val MAX_AUDIO_BYTES = 20_000_000

// Raw image input cap before compression — images typically shrink after compression,
// so we allow larger raw files than MAX_IMAGE_BYTES while still preventing an OOM
// from reading a multi-gigabyte file into memory.
const val MAX_RAW_IMAGE_BYTES = 50_000_000

private val textMimeTypes = setOf(
    "application/json",
    "application/xml",
    "application/javascript",
    "application/x-yaml",
    "application/yaml",
    "application/x-sh",
    "application/sql",
    "application/graphql",
    "application/toml",
)

private val textExtensions = setOf(
    "txt", "md", "json", "csv", "xml", "yaml", "yml",
    "html", "css", "js", "ts", "kt", "kts", "java",
    "py", "rb", "rs", "go", "c", "h", "cpp", "hpp",
    "swift", "sh", "bash", "zsh", "sql", "graphql",
    "toml", "ini", "cfg", "conf", "log", "properties",
    "gradle", "tsx", "jsx", "gsc",
)

internal val imageExtensions = setOf(
    "jpg",
    "jpeg",
    "png",
    "gif",
    "webp",
    "bmp",
    "svg",
)

/** Audio formats accepted as attachments. OpenAI-compatible servers (llama.cpp) take wav and mp3. */
internal val audioExtensions = setOf("wav", "mp3", "m4a", "ogg", "flac", "aac", "opus")

val supportedFileExtensions = (imageExtensions + textExtensions).toList()

/** The `input_audio.format` value for an audio attachment, or null if the format isn't wav/mp3. */
fun openAIAudioFormat(mimeType: String, fileName: String? = null): String? {
    val mime = mimeType.lowercase()
    val ext = fileName?.substringAfterLast('.', "")?.lowercase()
    return when {
        mime == "audio/wav" || mime == "audio/x-wav" || mime == "audio/wave" || mime == "audio/vnd.wave" || ext == "wav" -> "wav"
        mime == "audio/mpeg" || mime == "audio/mp3" || mime == "audio/mpeg3" || ext == "mp3" -> "mp3"
        else -> null
    }
}

/** Canonical mime type for an audio file, falling back to the extension when the platform reports none. */
fun audioMimeType(mimeType: String?, fileName: String?): String {
    val mime = mimeType?.lowercase()
    if (mime != null && mime.startsWith("audio/")) return mime
    return when (fileName?.substringAfterLast('.', "")?.lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        else -> mime ?: "audio/wav"
    }
}

fun classifyFile(mimeType: String?, fileName: String?): FileCategory {
    if (mimeType != null) {
        if (mimeType.startsWith("image/")) return FileCategory.IMAGE
        if (mimeType == "application/pdf") return FileCategory.PDF
        if (mimeType.startsWith("audio/")) return FileCategory.AUDIO
        if (mimeType.startsWith("text/") || mimeType in textMimeTypes) return FileCategory.TEXT
    }
    // Fall back to extension
    val ext = fileName?.substringAfterLast('.', "")?.lowercase()
    if (ext != null && ext in imageExtensions) return FileCategory.IMAGE
    if (ext != null && ext in textExtensions) return FileCategory.TEXT
    if (ext == "pdf") return FileCategory.PDF
    if (ext != null && ext in audioExtensions) return FileCategory.AUDIO

    // If mimeType is null and no recognized extension, unsupported
    if (mimeType == null) return FileCategory.UNSUPPORTED

    return FileCategory.UNSUPPORTED
}
