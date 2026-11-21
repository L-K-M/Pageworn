package ch.lkmc.pageworn.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BookKeyTest {
    @Test
    fun mediaIdRoundTripsWithOpaqueItemIds() {
        val key = BookKey("6f1c7a4e-1b2d-4c3e-9f00-1234567890ab", "li_odd:id/with:colons")
        assertThat(BookKey.fromMediaId(key.mediaId)).isEqualTo(key)
    }

    @Test
    fun sameItemInAnotherProfileIsAnotherBook() {
        assertThat(BookKey("p1", "item")).isNotEqualTo(BookKey("p2", "item"))
    }

    @Test
    fun foreignMediaIdsAreRejected() {
        assertThat(BookKey.fromMediaId("root")).isNull()
        assertThat(BookKey.fromMediaId("book:")).isNull()
        assertThat(BookKey.fromMediaId("book:p1:")).isNull()
        assertThat(BookKey.fromMediaId(null)).isNull()
    }
}
