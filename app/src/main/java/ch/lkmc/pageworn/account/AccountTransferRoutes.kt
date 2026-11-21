package ch.lkmc.pageworn.account

import ch.lkmc.pageworn.data.db.PagewornDatabase
import ch.lkmc.pageworn.data.db.PairingState
import ch.lkmc.pageworn.data.db.SessionState
import ch.lkmc.pageworn.server.ItemManifest
import ch.lkmc.pageworn.server.ResumeRequest
import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.server.ServerResult
import ch.lkmc.pageworn.transfer.DownloadChannel
import ch.lkmc.pageworn.transfer.TransferRoutes
import okhttp3.Response

/**
 * Manifest acquisition and download channels for the transfer coordinator, through the routes
 * and sessions of [ServerAccess]. A download keeps to the address that served its manifest; when
 * that address fails, the next manifest read chooses the route again.
 */
class AccountTransferRoutes(private val db: PagewornDatabase, private val access: ServerAccess) : TransferRoutes {
    override suspend fun manifest(itemId: String): Routed<ItemManifest> =
        access.route { connection -> connection.session.authorized { token -> connection.client.item(token, itemId) } }

    override suspend fun channel(addressId: String): DownloadChannel? {
        val address = db.addresses().get(addressId) ?: return null
        if (!address.enabled || address.pairingState != PairingState.PAIRED || address.sessionState != SessionState.SIGNED_IN) return null
        val connection = access.connection(address)
        return object : DownloadChannel {
            override fun url(itemId: String, fileId: String): String = connection.client.downloadUrl(itemId, fileId)

            override suspend fun open(itemId: String, fileId: String, resume: ResumeRequest?): ServerResult<Response> {
                val result = connection.session.authorized { token -> connection.client.download(token, itemId, fileId, resume) }
                if (result is ServerResult.Failed) {
                    // Teach the route choice what this address did, as a routed request would.
                    access.record(address, result.error)
                    if (result.error is ServerError.Unreachable || result.error is ServerError.Certificate) access.invalidateRoutes()
                }
                return result
            }

            override suspend fun cover(itemId: String): ByteArray? = (connection.client.coverOriginal(itemId) as? ServerResult.Ok)?.value
        }
    }
}
