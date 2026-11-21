package ch.lkmc.pageworn.server

/** An access and refresh token pair for one address. Replaced together, never field by field. */
data class TokenPair(val accessToken: String, val refreshToken: String)

/**
 * Credential storage keyed by address ID. Token material never enters Room; the Android
 * implementation encrypts it with a Keystore key. A pair is written and replaced as a whole.
 */
interface TokenVault {
    fun read(addressId: String): TokenPair?
    fun write(addressId: String, tokens: TokenPair)
    fun delete(addressId: String)
    fun deleteAll()
}
