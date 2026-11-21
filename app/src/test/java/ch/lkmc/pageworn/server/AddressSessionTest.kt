package ch.lkmc.pageworn.server

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import org.junit.Test

class AddressSessionTest {
    private val trust: X509TrustManager = HandshakeCertificates.Builder().build().trustManager

    private fun client(server: MockWebServer): AbsClient {
        val base = (parseBaseUrl(server.url("/abs/").toString(), true) as BaseUrlParse.Valid).baseUrl
        return AbsClient(AddressHttp(OkHttpClient(), AddressPolicy(base, true, null, false), trust))
    }

    private fun refreshResponse(access: String = "new-access", refresh: String = "new-refresh") = MockResponse().setBody(
        """{"user":{"id":"account","username":"listener","permissions":{"download":true},"librariesAccessible":[],"accessToken":"$access","refreshToken":"$refresh"}}""")

    private fun meResponse() = MockResponse().setBody(
        """{"id":"account","username":"listener","permissions":{"download":true},"librariesAccessible":[]}""")

    private fun token(expSeconds: Long): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":$expSeconds}""".toByteArray())
        return "header.$payload.signature"
    }

    @Test fun proactiveRefreshPersistsRotatedPair() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(refreshResponse())
            server.enqueue(meResponse())
            val vault = MemoryTokenVault()
            vault.write("a", TokenPair(token(1_059), "old-refresh"))
            val client = client(server)
            val session = AddressSession("a", client, vault) { 1_000_000 }
            assertThat(session.authorized { client.me(it) }).isInstanceOf(ServerResult.Ok::class.java)
            assertThat(server.takeRequest().getHeader("x-refresh-token")).isEqualTo("old-refresh")
            assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer new-access")
            assertThat(vault.read("a")).isEqualTo(TokenPair("new-access", "new-refresh"))
        }
    }

    @Test fun fiveConcurrentUnauthorizedCallsShareOneRefresh() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val oldCalls = CountDownLatch(5)
            val refreshes = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/abs/auth/refresh") {
                        refreshes.incrementAndGet()
                        assertThat(request.getHeader("x-refresh-token")).isEqualTo("old-refresh")
                        return refreshResponse()
                    }
                    if (request.getHeader("Authorization") == "Bearer old-access") {
                        oldCalls.countDown()
                        oldCalls.await(5, TimeUnit.SECONDS)
                        return MockResponse().setResponseCode(401)
                    }
                    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer new-access")
                    return meResponse()
                }
            }
            val vault = MemoryTokenVault()
            vault.write("a", TokenPair("old-access", "old-refresh"))
            val client = client(server)
            val session = AddressSession("a", client, vault) { 0 }
            val calls = (1..5).map { async(Dispatchers.IO) { session.authorized { client.me(it) } } }
            assertThat(calls.awaitAll()).hasSize(5)
            assertThat(calls.awaitAll().all { it is ServerResult.Ok }).isTrue()
            assertThat(refreshes.get()).isEqualTo(1)
            assertThat(vault.read("a")).isEqualTo(TokenPair("new-access", "new-refresh"))
        }
    }

    @Test fun rejectedRefreshKeepsVaultEntry() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            val vault = MemoryTokenVault()
            val original = TokenPair(token(1_000), "old-refresh")
            vault.write("a", original)
            val client = client(server)
            val session = AddressSession("a", client, vault) { 1_000_000 }
            assertThat(session.authorized { client.me(it) }).isEqualTo(ServerResult.Failed(ServerError.SessionExpired))
            assertThat(vault.read("a")).isEqualTo(original)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun rateLimitedRenewalKeepsTheSession() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "200"))
            val vault = MemoryTokenVault()
            val original = TokenPair(token(1_000), "old-refresh")
            vault.write("a", original)
            val client = client(server)
            val session = AddressSession("a", client, vault) { 1_000_000 }
            assertThat(session.authorized { client.me(it) }).isEqualTo(ServerResult.Failed(ServerError.RateLimited(200)))
            assertThat(vault.read("a")).isEqualTo(original)
        }
    }

    @Test fun twoSessionsKeepTokensOnTheirOwnServers() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start()
            second.start()
            first.enqueue(refreshResponse("new-a", "rotated-a"))
            first.enqueue(meResponse())
            second.enqueue(refreshResponse("new-b", "rotated-b"))
            second.enqueue(meResponse())
            val vault = MemoryTokenVault()
            vault.write("a", TokenPair(token(1_000), "refresh-a"))
            vault.write("b", TokenPair(token(1_000), "refresh-b"))
            val firstClient = client(first)
            val secondClient = client(second)
            val a = AddressSession("a", firstClient, vault) { 1_000_000 }
            val b = AddressSession("b", secondClient, vault) { 1_000_000 }
            assertThat(a.authorized { firstClient.me(it) }).isInstanceOf(ServerResult.Ok::class.java)
            assertThat(b.authorized { secondClient.me(it) }).isInstanceOf(ServerResult.Ok::class.java)
            assertThat(first.takeRequest().getHeader("x-refresh-token")).isEqualTo("refresh-a")
            assertThat(first.takeRequest().getHeader("Authorization")).isEqualTo("Bearer new-a")
            assertThat(second.takeRequest().getHeader("x-refresh-token")).isEqualTo("refresh-b")
            assertThat(second.takeRequest().getHeader("Authorization")).isEqualTo("Bearer new-b")
        } }
    }

    @Test fun signOutDeletesTokensWhenLogoutFails() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            val vault = MemoryTokenVault()
            vault.write("a", TokenPair("access", "refresh"))
            val result = AddressSession("a", client(server), vault) { 0 }.signOut()
            assertThat((result as ServerResult.Failed).error).isInstanceOf(ServerError.Unexpected::class.java)
            assertThat(vault.read("a")).isNull()
            assertThat(server.takeRequest().getHeader("x-refresh-token")).isEqualTo("refresh")
        }
    }
}
