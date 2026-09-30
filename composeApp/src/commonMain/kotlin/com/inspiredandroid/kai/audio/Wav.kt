package com.inspiredandroid.kai.audio

/** Sample rate of recorded voice messages — what speech models expect. */
const val VOICE_SAMPLE_RATE = 16_000

/** 44-byte RIFF/WAVE header for 16-bit mono PCM. */
fun wavHeader(pcmBytes: Int, sampleRate: Int = VOICE_SAMPLE_RATE): ByteArray {
    val byteRate = sampleRate * 2
    fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    return "RIFF".encodeToByteArray() + le32(36 + pcmBytes) + "WAVE".encodeToByteArray() +
        "fmt ".encodeToByteArray() + le32(16) + le16(1) + le16(1) + le32(sampleRate) + le32(byteRate) + le16(2) + le16(16) +
        "data".encodeToByteArray() + le32(pcmBytes)
}

/** A WAV file of [durationMs] silence, used to test speech-to-text endpoints without a microphone. */
fun silentWav(durationMs: Int = 500, sampleRate: Int = VOICE_SAMPLE_RATE): ByteArray {
    val pcmBytes = sampleRate * durationMs / 1000 * 2
    return wavHeader(pcmBytes, sampleRate) + ByteArray(pcmBytes)
}
