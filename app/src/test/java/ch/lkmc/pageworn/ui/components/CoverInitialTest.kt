package ch.lkmc.pageworn.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoverInitialTest {
    @Test fun aTitleGivesItsFirstLetter() {
        assertThat(coverInitial("Boundary Book")).isEqualTo("B")
        assertThat(coverInitial("  field notes ")).isEqualTo("F")
    }

    @Test fun aLeadingArticleIsSkippedWhenMoreWordsFollow() {
        assertThat(coverInitial("The Night Orchard")).isEqualTo("N")
        assertThat(coverInitial("A Quiet Book")).isEqualTo("Q")
        assertThat(coverInitial("an Orchard")).isEqualTo("O")
        assertThat(coverInitial("The")).isEqualTo("T")
    }

    @Test fun punctuationBeforeTheFirstLetterIsSkipped() {
        assertThat(coverInitial("“Quoted” Tales")).isEqualTo("Q")
        assertThat(coverInitial("1984")).isEqualTo("1")
        assertThat(coverInitial("über alles")).isEqualTo("Ü")
    }

    @Test fun aLetterOutsideTheBasicPlaneIsKeptWhole() {
        // Deseret letters sit outside the Basic Multilingual Plane, so each is two UTF-16 chars.
        assertThat(coverInitial("\uD801\uDC28\uD801\uDC2F")).isEqualTo("\uD801\uDC00")
        assertThat(coverInitial("“\uD835\uDD04lpha”")).isEqualTo("\uD835\uDD04")
    }

    @Test fun aTitleWithoutLettersHasNoInitial() {
        assertThat(coverInitial("")).isNull()
        assertThat(coverInitial("   ")).isNull()
        assertThat(coverInitial("…")).isNull()
    }
}
