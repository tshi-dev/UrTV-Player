package com.urtv.player.core

/**
 * Why a stream, a list or a login did not work, in terms a person can act on (the screens turn each into one clear sentence and a next step).
 * Pure logic, unit-tested. It never blames a provider by name and says «likely» only where the signal is not certain.
 */
object FailureReason {
    enum class Reason { None, Rejected, Missing, Busy, Server, Slow, Offline, Unreachable, Format, Decoder }

    fun of(kind: Diagnosis.ErrorKind, httpStatus: Int, online: Boolean): Reason = when (kind) {
        Diagnosis.ErrorKind.None -> Reason.None
        Diagnosis.ErrorKind.Http -> ofHttp(httpStatus)
        Diagnosis.ErrorKind.Timeout -> if (online) Reason.Slow else Reason.Offline
        Diagnosis.ErrorKind.Connection -> if (online) Reason.Unreachable else Reason.Offline
        Diagnosis.ErrorKind.Format -> Reason.Format
        Diagnosis.ErrorKind.Decoder -> Reason.Decoder
    }

    fun ofHttp(status: Int): Reason = when (status) {
        401, 403, 407 -> Reason.Rejected                 // wrong or expired login, too many devices on the account, blocked
        404, 410 -> Reason.Missing
        429 -> Reason.Busy
        in 500..599 -> Reason.Server
        in 400..499 -> Reason.Unreachable
        else -> Reason.Unreachable
    }

    /** The reason behind an exception of the list loaders («HTTP 503», a timeout, no route to the host…). */
    fun ofError(e: Throwable?, online: Boolean): Reason {
        if (e == null) return Reason.None
        val m = e.message.orEmpty()
        if (m.startsWith("HTTP ")) return ofHttp(m.removePrefix("HTTP ").takeWhile { it.isDigit() }.toIntOrNull() ?: 0)
        if (m == "too large" || m == "response too large") return Reason.Format
        if (e is java.net.SocketTimeoutException) return if (online) Reason.Slow else Reason.Offline
        if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketException) return if (online) Reason.Unreachable else Reason.Offline
        return if (online) Reason.Unreachable else Reason.Offline
    }
}
