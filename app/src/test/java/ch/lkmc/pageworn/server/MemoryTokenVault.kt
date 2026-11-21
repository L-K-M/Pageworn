package ch.lkmc.pageworn.server

import java.util.concurrent.ConcurrentHashMap

/** In-memory [TokenVault] for JVM tests. */
class MemoryTokenVault : TokenVault {
    private val tokens = ConcurrentHashMap<String, TokenPair>()
    override fun read(addressId: String): TokenPair? = tokens[addressId]
    override fun write(addressId: String, tokens: TokenPair) {
        this.tokens[addressId] = tokens
    }
    override fun delete(addressId: String) {
        tokens.remove(addressId)
    }
    override fun deleteAll() = tokens.clear()
    fun addresses(): Set<String> = tokens.keys.toSet()
}
