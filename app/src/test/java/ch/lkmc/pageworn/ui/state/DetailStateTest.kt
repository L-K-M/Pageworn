package ch.lkmc.pageworn.ui.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DetailStateTest {
    @Test
    fun aBlankNarratorReadsAsNone() {
        assertThat(shownNarrator(editionNarrators = "", bookNarrators = null)).isNull()
        assertThat(shownNarrator(editionNarrators = "   ", bookNarrators = "")).isNull()
        assertThat(shownNarrator(editionNarrators = null, bookNarrators = "\t\n")).isNull()
    }

    @Test
    fun theEditionsNarratorWinsAndTheCatalogFillsABlankOne() {
        assertThat(shownNarrator(editionNarrators = "Ray Porter", bookNarrators = "Someone Else")).isEqualTo("Ray Porter")
        assertThat(shownNarrator(editionNarrators = "  Ray Porter  ", bookNarrators = null)).isEqualTo("Ray Porter")
        assertThat(shownNarrator(editionNarrators = null, bookNarrators = "Ray Porter")).isEqualTo("Ray Porter")
        assertThat(shownNarrator(editionNarrators = "", bookNarrators = "Ray Porter")).isEqualTo("Ray Porter")
    }
}
