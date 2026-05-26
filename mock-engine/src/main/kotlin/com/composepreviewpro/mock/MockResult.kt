package com.composepreviewpro.mock

import kotlin.reflect.KType

/**
 * Outcome of mocking a single value.
 *
 * Modelled as a sealed hierarchy (not nullable Any?) because `null` is itself
 * a legal mock value (for `String?` parameters) and must be distinguishable
 * from "we could not mock this".
 */
sealed interface MockResult {
    /** Value successfully fabricated. Note: [value] may legally be `null`. */
    data class Success(val value: Any?) : MockResult

    /**
     * The mock engine has no strategy for this type at the current depth.
     * Carries [reason] so the renderer can show the user *why* the preview
     * cannot be produced (e.g. "data class User has no no-arg constructor").
     */
    data class Unsupported(val type: KType, val reason: String) : MockResult
}
