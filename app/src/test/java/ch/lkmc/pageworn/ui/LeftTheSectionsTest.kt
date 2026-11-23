package ch.lkmc.pageworn.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LeftTheSectionsTest {
    @Test fun openingAScreenOverTheSectionsLeavesThem() {
        assertThat(leftTheSections(Routes.MAIN, Routes.PLAYER)).isTrue()
        assertThat(leftTheSections(Routes.MAIN, Routes.SETTINGS)).isTrue()
    }

    @Test fun aLaunchStraightOntoThePlayerHasNotLeftThem() {
        // A tap on the media notification or a widget opens the player before any section shows.
        assertThat(leftTheSections(null, Routes.PLAYER)).isFalse()
        assertThat(leftTheSections(null, Routes.MAIN)).isFalse()
    }

    @Test fun movingBetweenOverlaysOrBackToTheSectionsDoesNotCount() {
        assertThat(leftTheSections(Routes.PLAYER, Routes.SLEEP)).isFalse()
        assertThat(leftTheSections(Routes.PLAYER, Routes.MAIN)).isFalse()
    }
}
