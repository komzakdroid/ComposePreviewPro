package com.composepreviewpro.renderer

import com.composepreviewpro.ipc.ErrorKind
import com.composepreviewpro.ipc.ErrorResponse
import java.io.PrintWriter
import java.io.StringWriter

internal fun Throwable.toErrorResponse(
    requestId: String?,
    kind: ErrorKind,
): ErrorResponse {
    val sw = StringWriter()
    printStackTrace(PrintWriter(sw))
    return ErrorResponse(
        requestId = requestId,
        kind = kind,
        message = message ?: this::class.qualifiedName ?: "Unknown error",
        stackTrace = sw.toString(),
    )
}
