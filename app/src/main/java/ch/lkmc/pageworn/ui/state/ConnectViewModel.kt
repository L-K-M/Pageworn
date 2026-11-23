package ch.lkmc.pageworn.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.account.AddressProblem
import ch.lkmc.pageworn.account.CheckedAddress
import ch.lkmc.pageworn.account.Outcome
import ch.lkmc.pageworn.account.PendingPairing
import ch.lkmc.pageworn.account.ServerNotice
import ch.lkmc.pageworn.account.SignInResult
import ch.lkmc.pageworn.data.db.CertificateState
import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.ui.model.ConnectStep
import ch.lkmc.pageworn.ui.model.ConnectUiState
import ch.lkmc.pageworn.ui.model.Copy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class ConnectMode { FIRST, SIGN_IN, ADD, LAUNCH_BLOCKED }

class ConnectViewModel(private val container: AppContainer, val mode: ConnectMode, private val addressId: String?) : ViewModel() {
    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state
    private var checked: CheckedAddress? = null
    private var pairing: PendingPairing? = null
    private var trustedSha256: String? = null
    private var onConnected: (() -> Unit)? = null

    init {
        if (mode == ConnectMode.LAUNCH_BLOCKED) {
            when (val notice = container.accounts.notice.value) {
                ServerNotice.Unreachable -> _state.value = _state.value.copy(addressError = Copy.UNREACHABLE, canRetry = true)
                is ServerNotice.LocalNetworkDenied -> _state.value = _state.value.copy(addressError = Copy.localNetworkDenied(notice.label), localNetworkAction = Copy.ALLOW_ACCESS, canRetry = true)
                is ServerNotice.CertificateChanged -> _state.value = _state.value.copy(addressError = Copy.certificateChanged(notice.label), canRetry = true)
                else -> Unit
            }
        }
        if (mode == ConnectMode.SIGN_IN || mode == ConnectMode.LAUNCH_BLOCKED) viewModelScope.launch {
            val chosen = addressId ?: container.database.profiles().current()?.let { container.database.addresses().forProfile(it.profileId).firstOrNull()?.addressId }
            chosen?.let { container.database.addresses().get(it) }?.let { address ->
                _state.value = _state.value.copy(url = address.baseUrl, label = address.label, allowCleartext = address.cleartextOptIn)
                if (mode == ConnectMode.LAUNCH_BLOCKED) {
                    when (address.certificateState) {
                        CertificateState.CHANGED -> _state.value = _state.value.copy(addressError = Copy.certificateChanged(address.label), fingerprint = address.presentedCertSha256)
                        CertificateState.UNRECOGNIZED -> _state.value = _state.value.copy(addressError = Copy.UNRECOGNIZED_CERTIFICATE, fingerprint = address.presentedCertSha256)
                        else -> Unit
                    }
                }
            }
        }
    }

    fun setOnConnected(callback: () -> Unit) { onConnected = callback }
    fun url(value: String) { _state.value = _state.value.copy(url = value, addressError = null, fingerprint = null, cleartextExplanation = null); checked = null; trustedSha256 = null }
    fun label(value: String) { _state.value = _state.value.copy(label = value) }
    fun username(value: String) { _state.value = _state.value.copy(username = value, usernameError = null, credentialsError = null) }
    fun password(value: String) { _state.value = _state.value.copy(password = value, credentialsError = null) }
    fun allowCleartext(value: Boolean) { _state.value = _state.value.copy(allowCleartext = value) }
    fun localNetworkDenied() { _state.value = _state.value.copy(addressError = Copy.localNetworkDenied(_state.value.label.ifBlank { _state.value.url }), localNetworkAction = Copy.ALLOW_ACCESS, busy = false) }

    fun continueAddress() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, addressError = null, cleartextExplanation = null, canRetry = false, localNetworkAction = null)
            when (val result = container.accounts.checkAddress(_state.value.url, _state.value.label, _state.value.allowCleartext, trustedSha256)) {
                is Outcome.Ok -> { checked = result.value; _state.value = _state.value.copy(step = ConnectStep.CREDENTIALS, busy = false) }
                is Outcome.Err -> showProblem(result.problem)
            }
        }
    }

    private fun showProblem(problem: AddressProblem) {
        val label = _state.value.label.ifBlank { _state.value.url }
        _state.value = when (problem) {
            is AddressProblem.InvalidUrl -> _state.value.copy(addressError = Copy.INVALID_ADDRESS)
            is AddressProblem.CleartextChoice -> _state.value.copy(cleartextExplanation = Copy.CLEARTEXT_TRADEOFF)
            is AddressProblem.Certificate -> _state.value.copy(addressError = if (problem.kind == ServerError.Certificate.Kind.CHANGED) Copy.certificateChanged(label) else Copy.UNRECOGNIZED_CERTIFICATE, fingerprint = problem.sha256)
            is AddressProblem.LocalNetworkPermission -> _state.value.copy(
                addressError = if (problem.denied) Copy.localNetworkDenied(label) else Copy.localNetworkNeeded(problem.host),
                localNetworkAction = if (problem.denied) Copy.ALLOW_ACCESS else Copy.CONTINUE,
            )
            AddressProblem.Unreachable -> _state.value.copy(addressError = Copy.UNREACHABLE, canRetry = true)
            AddressProblem.ServerTooOld -> _state.value.copy(addressError = Copy.SERVER_TOO_OLD)
            AddressProblem.NoPasswordSignIn -> _state.value.copy(addressError = Copy.NO_PASSWORD_SIGN_IN)
        }.copy(busy = false)
    }

    fun trust() {
        val fingerprint = _state.value.fingerprint ?: return
        if (mode == ConnectMode.LAUNCH_BLOCKED && addressId != null) {
            viewModelScope.launch {
                container.accounts.trustPresentedCertificate(addressId)
                trustedSha256 = fingerprint
                retry()
            }
        } else {
            trustedSha256 = fingerprint
            continueAddress()
        }
    }

    fun retry() {
        if (mode != ConnectMode.LAUNCH_BLOCKED) { continueAddress(); return }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            container.accounts.refreshLibraryAndCatalog()
            val notice = container.accounts.notice.value
            if (notice == null) onConnected?.invoke()
            else _state.value = _state.value.copy(busy = false, addressError = when (notice) {
                ServerNotice.Unreachable -> Copy.UNREACHABLE
                is ServerNotice.LocalNetworkDenied -> Copy.localNetworkDenied(notice.label)
                is ServerNotice.CertificateChanged -> Copy.certificateChanged(notice.label)
                else -> Copy.UNREACHABLE
            }, canRetry = true)
        }
    }

    fun signIn() {
        val address = checked ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, credentialsError = null)
            when (val result = container.accounts.signIn(address, _state.value.username, _state.value.password)) {
                is SignInResult.Connected -> connected()
                SignInResult.InvalidCredentials -> _state.value = _state.value.copy(credentialsError = Copy.WRONG_CREDENTIALS, busy = false)
                SignInResult.TooManyAttempts -> _state.value = _state.value.copy(credentialsError = Copy.TOO_MANY_SIGN_INS, busy = false)
                is SignInResult.AddressFailed -> { _state.value = _state.value.copy(step = ConnectStep.ADDRESS); showProblem(result.problem) }
                is SignInResult.DifferentAccount -> _state.value = _state.value.copy(usernameError = Copy.differentAccount(result.accountName), busy = false)
                SignInResult.PairingMismatch -> _state.value = _state.value.copy(step = ConnectStep.ADDRESS, addressError = Copy.PAIRING_MISMATCH, busy = false)
                is SignInResult.ConfirmPairing -> { pairing = result.pairing; _state.value = _state.value.copy(step = ConnectStep.CONFIRM_PAIRING, pairingSummary = Copy.confirmPairing(result.username, result.libraryName), busy = false) }
            }
        }
    }

    fun confirmPairing() {
        val pending = pairing ?: return
        viewModelScope.launch { _state.value = _state.value.copy(busy = true); container.accounts.confirmPairing(pending); pairing = null; connected() }
    }

    fun declinePairing() {
        val pending = pairing ?: return
        viewModelScope.launch { _state.value = _state.value.copy(busy = true); container.accounts.declinePairing(pending); pairing = null; _state.value = _state.value.copy(step = ConnectStep.ADDRESS, busy = false) }
    }

    private fun connected() {
        _state.value = _state.value.copy(busy = false, password = "")
        if (refreshesCatalog(mode)) {
            container.appScope.launch {
                container.accounts.refreshLibraryAndCatalog()
            }
        }
        onConnected?.invoke()
    }
}

/**
 * Whether a successful connect of [mode] should refresh the libraries and catalog. Signing in
 * again changes what the account may read, so a cached catalog is stale until it does. Adding an
 * address keeps the existing catalog: pairing has already tied it to this account and library.
 */
internal fun refreshesCatalog(mode: ConnectMode) = when (mode) {
    ConnectMode.FIRST, ConnectMode.LAUNCH_BLOCKED, ConnectMode.SIGN_IN -> true
    ConnectMode.ADD -> false
}
