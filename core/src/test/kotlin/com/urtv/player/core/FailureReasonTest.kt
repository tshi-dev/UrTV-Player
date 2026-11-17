package com.urtv.player.core

import com.urtv.player.core.FailureReason.Reason
import kotlin.test.Test
import kotlin.test.assertEquals

class FailureReasonTest {
    @Test fun httpStatusesBecomeActionableReasons() {
        assertEquals(Reason.Rejected, FailureReason.ofHttp(401)); assertEquals(Reason.Rejected, FailureReason.ofHttp(403))
        assertEquals(Reason.Missing, FailureReason.ofHttp(404)); assertEquals(Reason.Busy, FailureReason.ofHttp(429))
        assertEquals(Reason.Server, FailureReason.ofHttp(502)); assertEquals(Reason.Server, FailureReason.ofHttp(503))
        assertEquals(Reason.Unreachable, FailureReason.ofHttp(418)); assertEquals(Reason.Unreachable, FailureReason.ofHttp(0))
    }
    @Test fun noNetworkIsSaidAsSuchAndNotAsABrokenServer() {
        assertEquals(Reason.Offline, FailureReason.of(Diagnosis.ErrorKind.Connection, 0, online = false))
        assertEquals(Reason.Unreachable, FailureReason.of(Diagnosis.ErrorKind.Connection, 0, online = true))
        assertEquals(Reason.Slow, FailureReason.of(Diagnosis.ErrorKind.Timeout, 0, online = true))
        assertEquals(Reason.Offline, FailureReason.of(Diagnosis.ErrorKind.Timeout, 0, online = false))
        assertEquals(Reason.Format, FailureReason.of(Diagnosis.ErrorKind.Format, 0, true)); assertEquals(Reason.Decoder, FailureReason.of(Diagnosis.ErrorKind.Decoder, 0, true))
        assertEquals(Reason.None, FailureReason.of(Diagnosis.ErrorKind.None, 0, true))
    }
    @Test fun listLoadErrorsAreClassifiedFromTheirMessageOrType() {
        assertEquals(Reason.Rejected, FailureReason.ofError(java.io.IOException("HTTP 403"), true))
        assertEquals(Reason.Server, FailureReason.ofError(java.io.IOException("HTTP 503"), true))
        assertEquals(Reason.Slow, FailureReason.ofError(java.net.SocketTimeoutException("x"), true))
        assertEquals(Reason.Offline, FailureReason.ofError(java.net.UnknownHostException("h"), false))
        assertEquals(Reason.Unreachable, FailureReason.ofError(java.net.UnknownHostException("h"), true))
        assertEquals(Reason.Format, FailureReason.ofError(java.io.IOException("too large"), true))
    }
}
