package com.inspiredandroid.kai.audio

actual fun createVoiceRecorder(): VoiceRecorder = UnsupportedVoiceRecorder()

actual fun createAudioPlayer(): AudioPlayer = UnsupportedAudioPlayer()

actual fun createSystemStt(): SystemStt = UnsupportedSystemStt()
