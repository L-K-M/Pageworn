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

    @Test fun aTitleWithoutLettersHasNoInitial() {
        assertThat(coverInitial("")).isNull()
        assertThat(coverInitial("   ")).isNull()
        assertThat(coverInitial("…")).isNull()
    }
}
