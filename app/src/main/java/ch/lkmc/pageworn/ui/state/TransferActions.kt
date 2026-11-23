package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.server.NoRoute
import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.transfer.DownloadStart
import ch.lkmc.pageworn.transfer.Refusal
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.DetailAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The listener's transfer intents from one screen. They run in the application scope, so leaving
 * the screen never cancels a download, a pause or a cancel (docs/03-android-and-data.md).
 */
class TransferActions(private val container: AppContainer) {
    private val _refusal = MutableStateFlow<DownloadRefusal?>(null)

    /** Why the last Download tap on this screen started nothing, until the next tap. */
    val refusal: StateFlow<DownloadRefusal?> = _refusal.asStateFlow()

    fun download(key: BookKey) {
        _refusal.value = null
        container.appScope.launch {
            val start = container.transfers.download(key)
            if (start is DownloadStart.Enqueued) container.onDownloadEnqueued()
            when (val reason = (start as? DownloadStart.Refused)?.reason) {
                is Refusal.NoRoute -> container.accounts.reportUnavailable(reason.reason)
                is Refusal.Server -> if (reason.error != ServerError.Forbidden) container.accounts.reportFailure(reason.error)
                else -> Unit
            }
            _refusal.value = refusalOf(key, start)
        }
    }

    fun pause(jobId: String) {
        container.appScope.launch { container.transfers.pause(jobId) }
    }

    /** Resume a paused transfer, or Retry a stopped or waiting one. */
    fun resume(jobId: String) {
        container.appScope.launch { container.transfers.resume(jobId) }
    }

    fun cancel(jobId: String) {
        container.appScope.launch { container.transfers.cancel(jobId) }
    }

}

/**
 * A Download tap that started no transfer. A problem of the book itself shows on its row (A24b);
 * Library shows a problem of the server or its routes through the account's notice (A17), and
 * details shows either under its primary action.
 */
data class DownloadRefusal(
    val key: BookKey,
    val text: String,
    val onRow: Boolean,
    /** Replaces a detail page's enabled Download action while this refusal lasts, when the action must be disabled. */
    val disabledAction: DetailAction? = null,
)

/** The copy for a Download tap that started no transfer, from the product brief's table. */
fun refusalOf(key: BookKey, start: DownloadStart): DownloadRefusal? {
    if (start !is DownloadStart.Refused) return null
    fun row(text: String, action: DetailAction? = null) = DownloadRefusal(key, text, onRow = true, disabledAction = action)
    fun status(text: String) = DownloadRefusal(key, text, onRow = false)
    return when (val reason = start.reason) {
        Refusal.DownloadsDisabled -> row(Copy.DOWNLOAD_DISABLED, DetailAction.DownloadDisabled)
        Refusal.UnsupportedFormat -> row(Copy.UNSUPPORTED_FORMAT, DetailAction.Unsupported)
        Refusal.NoAudio -> row(Copy.NO_AUDIO)
        is Refusal.NoRoute -> when (val route = reason.reason) {
            is NoRoute.LocalNetworkDenied -> status(Copy.localNetworkDenied(route.label))
            is NoRoute.CertificateChanged -> status(Copy.certificateChanged(route.label))
            is NoRoute.CertificateUnrecognized -> status(Copy.UNRECOGNIZED_CERTIFICATE)
            NoRoute.SignedOut -> status(Copy.SESSION_EXPIRED)
            is NoRoute.Unreachable -> status(Copy.UNREACHABLE)
        }
        is Refusal.Server -> if (reason.error == ServerError.Forbidden) {
            row(Copy.DOWNLOAD_DISABLED, DetailAction.DownloadDisabled)
        } else {
            status(Copy.UNREACHABLE)
        }
        Refusal.RemovalPending, Refusal.AlreadyDownloaded -> null
    }
}
