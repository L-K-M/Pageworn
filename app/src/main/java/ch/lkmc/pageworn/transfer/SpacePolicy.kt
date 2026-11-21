package ch.lkmc.pageworn.transfer

object SpacePolicy {
    const val MARGIN_BYTES = 1_000_000_000L

    fun shortfall(remainingBytes: Long?, usableBytes: Long): Long? {
        val needed = (remainingBytes ?: 0L).coerceAtLeast(0L) + MARGIN_BYTES
        return if (usableBytes >= needed) null else needed - usableBytes
    }
}
