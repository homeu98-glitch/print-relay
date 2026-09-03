package com.macau.printhub.model

/**
 * A single print log entry — displayed in the log RecyclerView.
 */
data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val source: String,
    val targetPrinter: String,
    val summary: String,
    val success: Boolean,
    val error: String?,
) {
    companion object {
        @Volatile
        private var seq: Long = 0

        fun nextId(): Long = synchronized(this) { ++seq }
    }
}
