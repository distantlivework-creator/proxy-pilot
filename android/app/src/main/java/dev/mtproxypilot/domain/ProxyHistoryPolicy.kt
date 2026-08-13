package dev.mtproxypilot.domain

data class ProxyHistory(
    val proxy: MtProtoProxy,
    val successes: Int = 0,
    val failures: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastCheckedAt: Long = 0,
    val lastSuccessfulAt: Long = 0,
    val lastLatencyMs: Long? = null,
)

object ProxyHistoryPolicy {
    const val HIDE_AFTER_FAILED_ROUNDS = 3
    const val KEEP_RECENT_MS = 7L * 24 * 60 * 60 * 1_000

    fun record(old: ProxyHistory, result: ProxyAvailability, checkedAt: Long): ProxyHistory {
        val success = result.successfulAttempts > 0
        return old.copy(
            successes = old.successes + result.successfulAttempts,
            failures = old.failures + (result.attempts - result.successfulAttempts),
            consecutiveFailures = if (success) 0 else old.consecutiveFailures + 1,
            lastCheckedAt = checkedAt,
            lastSuccessfulAt = if (success) checkedAt else old.lastSuccessfulAt,
            lastLatencyMs = result.medianLatencyMs ?: old.lastLatencyMs,
        )
    }

    fun visibleResult(current: ProxyAvailability, history: ProxyHistory, now: Long): ProxyAvailability? {
        if (current.availability != Availability.UNAVAILABLE) return current
        val recentlyWorked = history.lastSuccessfulAt > 0 && now - history.lastSuccessfulAt <= KEEP_RECENT_MS
        if (!recentlyWorked || history.consecutiveFailures >= HIDE_AFTER_FAILED_ROUNDS) return null
        return current.copy(
            medianLatencyMs = history.lastLatencyMs,
            availability = Availability.UNSTABLE,
            retainedFromHistory = true,
        )
    }
}
