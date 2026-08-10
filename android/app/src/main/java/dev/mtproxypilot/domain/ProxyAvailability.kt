package dev.mtproxypilot.domain

enum class Availability { AVAILABLE, UNSTABLE, UNAVAILABLE }

data class ProxyAvailability(
    val proxy: MtProtoProxy,
    val attempts: Int,
    val successfulAttempts: Int,
    val medianLatencyMs: Long?,
    val availability: Availability,
)

object ProxyAvailabilityPolicy {
    /**
     * Keep every proxy that answered at least once. Two or more successful
     * samples mark it available; one sample remains in the pool as unstable.
     */
    fun evaluate(proxy: MtProtoProxy, samplesMs: List<Long?>): ProxyAvailability {
        val successful = samplesMs.filterNotNull().filter { it >= 0 }.sorted()
        val state = when {
            successful.size >= 2 -> Availability.AVAILABLE
            successful.size == 1 -> Availability.UNSTABLE
            else -> Availability.UNAVAILABLE
        }
        return ProxyAvailability(
            proxy = proxy,
            attempts = samplesMs.size,
            successfulAttempts = successful.size,
            medianLatencyMs = successful.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] },
            availability = state,
        )
    }
}
