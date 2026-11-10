package com.urtv.player.core

import com.urtv.player.core.Diagnosis.Cause
import com.urtv.player.core.Diagnosis.ErrorKind
import com.urtv.player.core.Diagnosis.Sample
import kotlin.test.*

class DiagnosisTest {
    private fun run(vararg events: Pair<Long, String>, base: Sample = Sample()): Cause {
        // scripted network trace: "stall:<channel>" and "buf" (a second spent buffering) at the given times
        val t = Diagnosis.Tracker(); var now = 0L
        for ((at, e) in events) { now = at; if (e.startsWith("stall:")) t.stall(at, e.removePrefix("stall:")) else if (e == "buf") t.bufferingSecond(at) }
        return Diagnosis.classify(base.copy(stallsInWindow = t.stallsIn(now), stallMsInWindow = t.stallMs(now), stalledChannels = t.stalledChannels(now), buffering = true))
    }

    @Test fun slowSourceWhileTheLinkIsFine() {
        val c = run(1000L to "stall:a", 9000L to "stall:a", 15000L to "stall:a", 16000L to "buf", base = Sample(receivedKbps = 800, streamKbps = 3000, linkKbps = 100_000, signalDbm = -50))
        assertEquals(Cause.Source, c)
    }

    @Test fun homeNetworkWhenSeveralChannelsStall() {
        val c = run(1000L to "stall:a", 60_000L to "stall:b", 100_000L to "stall:c", 101_000L to "stall:c", base = Sample(receivedKbps = 900, streamKbps = 3000))
        assertEquals(Cause.HomeNetwork, c)
    }

    @Test fun homeNetworkWhenWifiIsWeakOrSlowerThanTheStreamNeeds() {
        val stalls = arrayOf(1000L to "stall:a", 5000L to "stall:a")
        assertEquals(Cause.HomeNetwork, run(*stalls, base = Sample(signalDbm = -82, streamKbps = 3000, receivedKbps = 5000)))
        assertEquals(Cause.HomeNetwork, run(*stalls, base = Sample(linkKbps = 4000, streamKbps = 3000, receivedKbps = 1000)))
    }

    @Test fun decoderWhenFramesDropWithAFullBuffer() {
        assertEquals(Cause.Decoder, Diagnosis.classify(Sample(droppedPerSec = 6f, bufferedMs = 8000, streamKbps = 12000, receivedKbps = 20000)))
        assertEquals(Cause.Decoder, Diagnosis.classify(Sample(error = ErrorKind.Decoder)))
    }

    @Test fun deadChannelOrOverloadedSourceFromTheHttpClass() {
        assertEquals(Cause.DeadChannel, Diagnosis.classify(Sample(error = ErrorKind.Http, httpStatus = 404)))
        assertEquals(Cause.DeadChannel, Diagnosis.classify(Sample(error = ErrorKind.Http, httpStatus = 403)))
        assertEquals(Cause.Source, Diagnosis.classify(Sample(error = ErrorKind.Http, httpStatus = 503)))
        assertEquals(Cause.DeadChannel, Diagnosis.classify(Sample(error = ErrorKind.Format)))
        assertEquals(Cause.Source, Diagnosis.classify(Sample(error = ErrorKind.Timeout, attempts = 1)))
        assertEquals(Cause.DeadChannel, Diagnosis.classify(Sample(error = ErrorKind.Connection, attempts = 3)))
        assertEquals(Cause.HomeNetwork, Diagnosis.classify(Sample(error = ErrorKind.Timeout, attempts = 3, signalDbm = -80)))
    }

    @Test fun healthyOrOneBlipSaysNothing() {
        assertEquals(Cause.None, Diagnosis.classify(Sample(bufferedMs = 6000, droppedPerSec = 0.2f, receivedKbps = 9000, streamKbps = 3000)))
        assertEquals(Cause.None, run(1000L to "stall:a", base = Sample(receivedKbps = 100, streamKbps = 3000)))
    }

    @Test fun unknownWhenItStallsButTheNumbersDoNotSayWhy() {
        assertEquals(Cause.Unknown, run(1000L to "stall:a", 4000L to "stall:a", base = Sample()))
    }

    @Test fun windowsExpire() {
        val t = Diagnosis.Tracker()
        t.stall(0, "a"); t.stall(1000, "b"); t.stall(2000, "c")
        assertEquals(3, t.stalledChannels(2000)); assertEquals(3, t.stallsIn(2000))
        assertEquals(0, t.stallsIn(40_000), "30 s window"); assertEquals(3, t.stalledChannels(40_000), "10 min window for channels")
        assertEquals(0, t.stalledChannels(700_000))
    }

    @Test fun alternatesAreSameNameOtherEntriesSameQualityFirst() {
        fun c(id: String, n: String) = Channel(id, n, "http://x/$id")
        val all = listOf(c("1", "beIN Sports 1 HD"), c("2", "BEIN SPORTS 1 SD"), c("3", "beIN Sports 1 HD (backup)"), c("4", "beIN Sports 10"), c("5", "الجزيرة"), c("6", "الجزيرة HD"))
        assertEquals(listOf("3", "2"), Alternates.find(all[0], all).map { it.id })
        assertEquals(listOf("2"), Alternates.find(all[0], all, exclude = setOf("http://x/3")).map { it.id })
        assertEquals(listOf("6"), Alternates.find(all[4], all).map { it.id })
        assertTrue(Alternates.find(all[3], all).isEmpty())
    }
}
