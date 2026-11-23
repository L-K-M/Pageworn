package ch.lkmc.pageworn.ui.state

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.BuildConfig
import ch.lkmc.pageworn.account.AddressProblem
import ch.lkmc.pageworn.account.Outcome
import ch.lkmc.pageworn.data.db.AddressEntity
import ch.lkmc.pageworn.data.db.CertificateState
import ch.lkmc.pageworn.data.db.SessionState
import ch.lkmc.pageworn.server.BaseUrl
import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.ui.model.AddressRow
import ch.lkmc.pageworn.ui.model.AddressesUiState
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.SettingsUiState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val confirming = MutableStateFlow(false)
    private val _exportFailed = MutableStateFlow(false)
    val exportFailed = _exportFailed.asStateFlow()
    val state = container.database.profiles().observe().flatMapLatest { profile ->
        if (profile == null) flowOf(null to emptyList())
        else container.database.addresses().observe(profile.profileId).flatMapLatest { addresses -> flowOf(profile to addresses) }
    }.combine(confirming) { (profile, addresses), confirm ->
        SettingsUiState(profile?.accountName, addresses.any { it.sessionState == SessionState.SIGNED_IN }, "Pageworn ${BuildConfig.VERSION_NAME}", confirm)
    }.combine(container.sleepSettings.settings) { settings, sleep -> settings.copy(sleepSummary = sleepSummary(sleep)) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(null, false, "Pageworn ${BuildConfig.VERSION_NAME}"))

    fun askSignOut() { confirming.value = true }
    fun dismissSignOut() { confirming.value = false }
    fun exportDiagnostics(destination: Uri) {
        viewModelScope.launch { _exportFailed.value = !container.exportDiagnostics(destination) }
    }

    fun signOut() {
        confirming.value = false
        container.appScope.launch { container.accounts.signOut() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AddressesViewModel(private val container: AppContainer) : ViewModel() {
    private val testResults = MutableStateFlow<Map<String, String>>(emptyMap())
    private val route = MutableStateFlow(container.settings.pinnedAddressId)
    private val addresses = container.database.profiles().observe().flatMapLatest { profile ->
        if (profile == null) flowOf(emptyList()) else container.database.addresses().observe(profile.profileId)
    }
    val state = combine(addresses, testResults, route) { rows, results, pinned ->
        val activeId = addressInUse(rows, pinned)
        AddressesUiState(rows.map { address ->
            val certificateMessage = when (address.certificateState) {
                CertificateState.OK -> null
                CertificateState.UNRECOGNIZED -> Copy.UNRECOGNIZED_CERTIFICATE
                CertificateState.CHANGED -> Copy.certificateChanged(address.label)
            }
            val denied = container.localNetwork.blocks(BaseUrl(address.baseUrl).resolve("").host)
            AddressRow(
                addressId = address.addressId, label = address.label, url = address.baseUrl,
                enabled = address.enabled, isActive = address.addressId == activeId,
                cleartext = address.cleartextOptIn,
                lastSuccessText = address.lastSuccessAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) },
                message = when { denied -> Copy.localNetworkDenied(address.label); certificateMessage != null -> certificateMessage; address.sessionState != SessionState.SIGNED_IN -> Copy.SESSION_EXPIRED; else -> null },
                fingerprint = address.presentedCertSha256,
                canTrustCertificate = !denied && certificateMessage != null,
                canAllowLocalNetwork = denied,
                needsSignIn = !denied && certificateMessage == null && address.sessionState != SessionState.SIGNED_IN,
                testResult = results[address.addressId],
            )
        }, automatic = pinned == null, pinnedAddressId = pinned)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddressesUiState(emptyList(), true, null))

    fun rename(id: String, label: String) = launch { container.accounts.rename(id, label) }
    fun move(id: String, up: Boolean) = launch { container.accounts.move(id, up) }
    fun toggle(id: String) = launch { state.value.rows.firstOrNull { it.addressId == id }?.let { container.accounts.setEnabled(id, !it.enabled) } }
    fun remove(id: String) = launch {
        container.accounts.remove(id)
        if (container.settings.pinnedAddressId == id) pin(null)
    }
    fun trust(id: String) = launch { container.accounts.trustPresentedCertificate(id) }
    fun test(id: String) = launch {
        testResults.value = testResults.value - id
        val result = container.accounts.test(id)
        testResults.value = testResults.value + (id to when (result) {
            is Outcome.Ok -> "Reached just now."
            is Outcome.Err -> when (result.problem) {
                is AddressProblem.InvalidUrl -> Copy.INVALID_ADDRESS
                is AddressProblem.CleartextChoice -> Copy.CLEARTEXT_TRADEOFF
                is AddressProblem.LocalNetworkPermission -> Copy.localNetworkDenied(state.value.rows.firstOrNull { it.addressId == id }?.label.orEmpty())
                is AddressProblem.Certificate -> if (result.problem.kind == ServerError.Certificate.Kind.CHANGED) Copy.certificateChanged(state.value.rows.firstOrNull { it.addressId == id }?.label.orEmpty()) else Copy.UNRECOGNIZED_CERTIFICATE
                AddressProblem.Unreachable -> Copy.UNREACHABLE
                AddressProblem.ServerTooOld -> Copy.SERVER_TOO_OLD
                AddressProblem.NoPasswordSignIn -> Copy.NO_PASSWORD_SIGN_IN
            }
        })
    }
    fun pin(id: String?) {
        container.settings.pinnedAddressId = id
        route.value = id
        launch { container.serverAccess.invalidateRoutes() }
    }
    private fun launch(block: suspend () -> Unit) { container.appScope.launch { block() } }
}

/** The last address that answered, among those the route may take: a pin leaves only that one. */
internal fun addressInUse(addresses: List<AddressEntity>, pinnedId: String?): String? =
    addresses.filter { it.enabled && it.lastSuccessAt != null && (pinnedId == null || it.addressId == pinnedId) }
        .maxByOrNull { checkNotNull(it.lastSuccessAt) }
        ?.addressId
