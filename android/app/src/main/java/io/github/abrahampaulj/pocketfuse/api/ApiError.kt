package io.github.abrahampaulj.pocketfuse.api

/**
 * A failed call, with what the user should be told. The server's own message is kept for logs, but
 * the UI shows [userMessage]: a sentence and a next step for every status the guide documents.
 */
class ApiException(
    val status: Int,
    val serverMessage: String?,
    val operation: Operation,
) : Exception("$operation failed with $status${serverMessage?.let { ": $it" }.orEmpty()}") {

    /** The session is gone (expired, rotated or closed): open a new one, never retry this call. */
    val sessionLost: Boolean get() = status == 401 || status == 426

    val userMessage: String get() = describe(status, operation)
}

/** No answer at all: wrong address, server down, phone off the network. */
class UnreachableException(val host: String, cause: Throwable) :
    Exception("Could not reach $host", cause) {
    val userMessage: String
        get() = "Could not reach your FaceFusion server at $host. Check that it is running and that the phone is on the same Wi-Fi or tailnet."
}

enum class Operation { OpenSession, RefreshSession, CloseSession, Capabilities, State, SelectAssets, Upload, Assets, Capture, Download, Jobs, Stream, Metrics }

fun describe(status: Int, operation: Operation): String = when (status) {
    400 -> when (operation) {
        Operation.Upload -> "The server could not read that file. Images and videos must be at most 4096 pixels on each side."
        Operation.Capture -> "Could not take frames from that file."
        Operation.State -> "The server refused one of the settings. Set it back to Default under All options."
        Operation.Jobs -> "The server could not start this job. Pick a source face and a target first."
        else -> "The server refused the request."
    }
    401 -> when (operation) {
        Operation.OpenSession -> "The server refused the API key. Scan the pairing code again."
        else -> "Your session ended. Reconnecting will start a fresh one."
    }
    426 -> "Your session expired without a refresh. Reconnecting will start a fresh one."
    404 -> when (operation) {
        Operation.Jobs -> "That job no longer exists on the server."
        Operation.Stream -> "The server could not start a live stream."
        Operation.CloseSession -> "The server could not clean up the session's files. It ends on its own a few minutes later."
        else -> "That file is no longer on the server. Sessions keep files only while they are open."
    }
    409 -> when (operation) {
        Operation.Stream -> "A live stream is already open for this session."
        else -> "Another job is still queued. Wait for it to finish first."
    }
    413 -> "That file is larger than the server accepts (512 MiB)."
    415 -> "The server could not clean that file. MP4 and MOV files need their index at the front (re-save or share it again) and the content must match the extension."
    422 -> "Nothing to change."
    429 -> "The server is full: it allows a limited number of sessions at once. Close Pocketfuse on another device or raise --api-session-limit."
    in 500..599 -> "The server hit an error. Its console has the details."
    else -> "Unexpected answer from the server ($status)."
}
