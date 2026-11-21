package ch.lkmc.pageworn.server

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.net.InetAddress
import org.junit.Test

class LocalNetworkClassTest {
    private fun local(ip: String) = LocalNetworkClass.isLocalAddress(InetAddress.getByName(ip))

    @Test
    fun privateLinkLocalAndCarrierGradeNatAreLocal() {
        listOf("10.0.2.2", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.3.4", "100.64.0.1", "100.101.102.103", "100.127.255.254", "fd7a:115c:a1e0::1", "fe80::1", "224.0.0.251")
            .forEach { assertWithMessage(it).that(local(it)).isTrue() }
    }

    @Test
    fun publicAndLoopbackAddressesAreNot() {
        listOf("8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "127.0.0.1", "::1", "2001:4860:4860::8888")
            .forEach { assertWithMessage(it).that(local(it)).isFalse() }
    }

    @Test
    fun multicastDnsNamesAreLocal() {
        assertThat(LocalNetworkClass.isLocalHost("nas.local")).isTrue()
        assertThat(LocalNetworkClass.isLocalHost("NAS.LOCAL.")).isTrue()
        assertThat(LocalNetworkClass.isLocalHost("books.example.ts.net")).isFalse()
    }
}
