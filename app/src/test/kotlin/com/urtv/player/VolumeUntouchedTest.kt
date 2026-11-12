package com.urtv.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Player never changes the TV's mute state or volume: no code path may call the stream-volume or mute APIs (the remote's volume keys belong to the TV). */
class VolumeUntouchedTest {
    @Test fun noSourceFileCallsAVolumeOrMuteApi() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val banned = listOf("setStreamVolume", "adjustStreamVolume", "adjustVolume", "adjustSuggestedStreamVolume", "setStreamMute", "setMasterMute", "setStreamSolo", "AudioManager")
        val hits = File(root, "app/src/main").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> banned.firstOrNull { l.contains(it) }?.let { "${f.name}:${i + 1} $it" } } }.toList()
        assertTrue(hits.isEmpty(), "volume/mute APIs are off limits: $hits")
    }
}
