package ch.lkmc.pageworn.server

import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AddressSession(
    private val addressId: String,
    private val client: AbsClient,
    private val vault: TokenVault,
    private val nowMs: () -> Long,
) {
    private val refreshMutex = Mutex()

    suspend fun <T> authorized(call: suspend (accessToken: String) -> ServerResult<T>): ServerResult<T> {
        var tokens = vault.read(addressId) ?: return ServerResult.Failed(ServerError.SessionExpired)
        if (expiresSoon(tokens.accessToken)) {
            when (val refreshed = refreshIfCurrent(tokens)) {
                is ServerResult.Failed -> return refreshed
                is ServerResult.Ok -> tokens = refreshed.value
            }
        }
        val first = call(tokens.accessToken)
        if (first !is ServerResult.Failed || first.error != ServerError.SessionExpired) return first

        val refreshed = refreshIfCurrent(tokens)
        if (refreshed is ServerResult.Failed) return refreshed
        return call((refreshed as ServerResult.Ok).value.accessToken)
    }

    suspend fun signOut(): ServerResult<Unit> {
        val tokens = vault.read(addressId)
        return try {
            if (tokens == null) ServerResult.Ok(Unit) else client.logout(tokens.refreshToken)
        } finally {
            vault.delete(addressId)
        }
    }

    private suspend fun refreshIfCurrent(observed: TokenPair): ServerResult<TokenPair> = refreshMutex.withLock {
        val current = vault.read(addressId) ?: return@withLock ServerResult.Failed(ServerError.SessionExpired)
        if (current != observed) return@withLock ServerResult.Ok(current)
        when (val result = client.refresh(current.refreshToken)) {
            is ServerResult.Failed -> result
            is ServerResult.Ok -> {
                vault.write(addressId, result.value.tokens)
                ServerResult.Ok(result.value.tokens)
            }
        }
    }

    private fun expiresSoon(token: String): Boolean = try {
        val payload = token.split('.')[1]
        val json = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload))).jsonObject
        val expSeconds = json["exp"]?.jsonPrimitive?.content?.toLongOrNull()
        expSeconds != null && expSeconds <= nowMs() / 1000 + 60
    } catch (_: Exception) {
        false
    }
}
