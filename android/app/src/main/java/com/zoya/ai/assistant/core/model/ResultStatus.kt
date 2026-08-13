package com.zoya.ai.assistant.core.model

/**
 * Structured result states returned for every automation command.
 *
 * These states are always returned to the caller. A command that was blocked
 * (e.g. missing permission, ambiguous target) is NEVER reported as SUCCESS.
 */
enum class ResultStatus {
    SUCCESS,
    FAILURE,
    PERMISSION_DENIED,
    TIMEOUT,
    UNSUPPORTED,
    CANCELLED,
    BLOCKED
}
