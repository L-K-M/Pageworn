package ch.lkmc.pageworn.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Credentials round-trip through Keystore encryption and never reach disk in clear text. */
@RunWith(AndroidJUnit4::class)
class KeystoreTokenVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = KeystoreTokenVault(context)

    @After
    fun tearDown() = vault.deleteAll()

    @Test
    fun pairsAreStoredPerAddressAndReplacedWhole() {
        vault.write("a1", TokenPair("access-one", "refresh-one"))
        vault.write("a2", TokenPair("access-two", "refresh-two"))
        vault.write("a1", TokenPair("access-three", "refresh-three"))

        assertThat(vault.read("a1")).isEqualTo(TokenPair("access-three", "refresh-three"))
        assertThat(vault.read("a2")).isEqualTo(TokenPair("access-two", "refresh-two"))
        assertThat(vault.read("missing")).isNull()

        val stored = File(context.noBackupFilesDir, "credentials").listFiles()!!.joinToString("") { it.readText() }
        assertThat(stored).doesNotContain("access-")
        assertThat(stored).doesNotContain("refresh-")

        vault.delete("a1")
        assertThat(vault.read("a1")).isNull()
        assertThat(vault.read("a2")).isNotNull()
        vault.deleteAll()
        assertThat(vault.read("a2")).isNull()
    }
}
