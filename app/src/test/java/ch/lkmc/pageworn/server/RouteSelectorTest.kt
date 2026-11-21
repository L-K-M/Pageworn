package ch.lkmc.pageworn.server

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RouteSelectorTest {
    private val unreachable = ServerResult.Failed(ServerError.Unreachable("timeout"))
    private val changed = ServerError.Certificate("AA:BB", ServerError.Certificate.Kind.CHANGED)

    private fun candidate(id: String, priority: Int, credentials: Boolean = true, enabled: Boolean = true, paired: Boolean = true, cert: ServerError.Certificate? = null) =
        RouteCandidate(id, "label-$id", priority, enabled, paired, cert, credentials)

    private class Probe(private val answers: Map<String, ServerResult<Unit>>) {
        val probed = mutableListOf<String>()
        suspend fun invoke(id: String): ServerResult<Unit> {
            probed += id
            return answers.getValue(id)
        }
    }

    private fun selector(list: List<RouteCandidate>, probe: Probe, localBlocked: Set<String> = emptySet()) =
        RouteSelector({ list }, { it in localBlocked }, probe::invoke)

    @Test
    fun firstReachableInPriorityOrderIsUsedAndReusedOnTheSameNetwork() = runTest {
        val probe = Probe(mapOf("home" to unreachable, "tail" to ServerResult.Ok(Unit)))
        val s = selector(listOf(candidate("tail", 2), candidate("home", 1)), probe)

        assertThat(s.select("wifi")).isEqualTo(RouteDecision.Use("tail"))
        assertThat(s.select("wifi")).isEqualTo(RouteDecision.Use("tail"))
        assertThat(probe.probed).containsExactly("home", "tail").inOrder()

        // A network change clears the cached choice and probes again.
        s.select("cell")
        assertThat(probe.probed).containsExactly("home", "tail", "home", "tail").inOrder()
    }

    @Test
    fun invalidatingTheWorkingAddressProbesAgain() = runTest {
        val probe = Probe(mapOf("home" to ServerResult.Ok(Unit)))
        val s = selector(listOf(candidate("home", 1)), probe)
        s.select("wifi")
        s.invalidate("home")
        s.select("wifi")
        assertThat(probe.probed).containsExactly("home", "home")
    }

    @Test
    fun blockedAddressesAreSkippedWithoutContact() = runTest {
        val probe = Probe(mapOf("tail" to ServerResult.Ok(Unit)))
        val s = selector(listOf(candidate("home", 1, cert = changed), candidate("lan", 2), candidate("tail", 3)), probe, localBlocked = setOf("lan"))
        assertThat(s.select(null)).isEqualTo(RouteDecision.Use("tail"))
        assertThat(probe.probed).containsExactly("tail")
    }

    @Test
    fun theFirstBlockedAddressDecidesTheCopyWhenNoneAnswers() = runTest {
        val probe = Probe(mapOf("tail" to unreachable))
        val lanFirst = selector(listOf(candidate("lan", 1), candidate("home", 2, cert = changed), candidate("tail", 3)), probe, localBlocked = setOf("lan"))
        assertThat(lanFirst.select(null)).isEqualTo(RouteDecision.None(NoRoute.LocalNetworkDenied("lan", "label-lan")))

        val certFirst = selector(listOf(candidate("home", 1, cert = changed), candidate("lan", 2), candidate("tail", 3)), probe, localBlocked = setOf("lan"))
        assertThat(certFirst.select(null)).isEqualTo(RouteDecision.None(NoRoute.CertificateChanged("home", "label-home")))
    }

    @Test
    fun aCertificateProblemFoundByTheProbeIsReported() = runTest {
        val probe = Probe(mapOf("home" to ServerResult.Failed(changed)))
        val s = selector(listOf(candidate("home", 1)), probe)
        assertThat(s.select(null)).isEqualTo(RouteDecision.None(NoRoute.CertificateChanged("home", "label-home")))
    }

    @Test
    fun disabledAndUnpairedAddressesAreNeverUsed() = runTest {
        val probe = Probe(emptyMap())
        val s = selector(listOf(candidate("off", 1, enabled = false), candidate("mismatch", 2, paired = false)), probe)
        assertThat(s.select(null)).isEqualTo(RouteDecision.None(NoRoute.Unreachable(null)))
        assertThat(probe.probed).isEmpty()
    }

    @Test
    fun aReachableAddressWithoutASessionMeansSignIn() = runTest {
        val probe = Probe(mapOf("home" to ServerResult.Ok(Unit), "tail" to unreachable))
        val s = selector(listOf(candidate("home", 1, credentials = false), candidate("tail", 2)), probe)
        assertThat(s.select(null)).isEqualTo(RouteDecision.None(NoRoute.SignedOut))
    }

    @Test
    fun manualModeUsesOnlyThePinnedAddressAndNamesIt() = runTest {
        val probe = Probe(mapOf("home" to ServerResult.Ok(Unit), "tail" to unreachable))
        val s = selector(listOf(candidate("home", 1), candidate("tail", 2)), probe)
        assertThat(s.select(null, RouteMode.MANUAL, "tail")).isEqualTo(RouteDecision.None(NoRoute.Unreachable("label-tail")))
        assertThat(probe.probed).containsExactly("tail")
    }
}
