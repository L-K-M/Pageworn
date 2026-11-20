# Audiobookshelf connection and downloads

## A profile can have several addresses

The listener enters addresses explicitly. A Home address might be `https://books.home.example/audiobookshelf`. A Tailscale address might be `https://books.example-tailnet.ts.net/audiobookshelf`. Accept a port and a path prefix. When the scheme is omitted, default to `https`; require an explicit choice before using `http`. Normalize once: lowercase the scheme and host, drop a default port and keep a single trailing slash. Preserve the prefix when constructing requests. Reject embedded credentials, fragments and query strings in base URLs.

**Pairing.** On first sign-in, store the account ID and available stable server/library identifiers. Pair each additional address through its own sign-in and compare the authenticated account and server identity. If the target release exposes no stable server instance ID, compare the account ID and a known accessible library ID, display the match and ask the listener once to confirm the alias. A mismatch cannot become an automatic route. Keep each address’s authenticated session scoped to its exact origin and base path. An alternate address must never receive another address’s refresh token.

**Automatic mode.** After a network change, select the first reachable, paired address in the listener’s priority order. Probe only when network work is requested. Use a short bounded timeout per address (initial target 1.5 seconds), reuse the working address on that network, and clear the cached choice when the network changes. A reachable Tailscale address works like any other configured route. This design does not need to read Wi-Fi names or manage the VPN. Manual mode pins a chosen route and names it in a connection error.

**Address changes during a transfer.** Removing, disabling or reordering an alias never cancels or fails an in-flight transfer. When its serving address disappears, the job keeps its staged files and moves to Waiting for connection. It resumes through the next reachable paired address only after the source-identity reconciliation under [Safe continuation across routes](#safe-continuation-across-routes). Complete verified files are reused when the refreshed manifest still identifies the same source. A partial file continues only on the same URL and revision with a saved strong tag and known length. Otherwise it restarts from byte zero unless byte identity is proven. The job waits rather than fails while no enabled address remains. Removing every alias preserves the profile, books and progress.

**Transport and failures.** Prefer HTTPS with normal certificate validation. Support an explicitly enabled HTTP endpoint for installations that need it. Explain the cleartext tradeoff when pairing and store the opt-in per origin rather than per network, so it persists across network changes. Enforce the exact opt-in origin in the HTTP client even if Android needs an application-level cleartext allowance for runtime hosts. Restrict redirects to the approved origin and path. Never follow a TLS downgrade or send credentials to an unpaired destination.

A timeout or disconnected network can trigger failover for a safe read or transfer. A certificate or identity mismatch disables that address and surfaces a specific error. Refresh a 401 once using the credentials for that origin. A 403 download denial is a permission decision and ends the request. On Android versions and target SDKs that require local-network permission, request it in context and explain which address needs it. Permission denial leaves Downloaded usable.

## Server contract to pin in M0

Record the actual target server release and capture sanitized fixtures, including its file identifier and authorization behavior. The server repository includes an OpenAPI document. The older API reference is useful for payload examples, and the maintainers’ JWT notes describe the mobile headers. Favor the contract observed on the pinned server when they differ.

| Operation | Request shape to verify | Client responsibility |
|---|---|---|
| Probe and sign in | `GET /status`, `POST /login` with mobile header `x-return-tokens: true` | Use status for reachability, authenticated data for pairing. Read the user ID and current access/refresh token pair. |
| Renew session | `POST /auth/refresh` with `x-refresh-token` | Serialize refresh per address, persist rotated credentials together and retry an eligible read once. |
| List libraries | `GET /api/libraries` | Filter to accessible book libraries. Persist the chosen library per profile and restore it on relaunch. Handle zero accessible book libraries with an explicit empty state, and several with a selector defaulting to the last choice. |
| List catalog | `GET /api/libraries/{libraryId}/items` with `page` and `limit` | Walk pages in a stable order, deduplicate IDs, and refresh atomically as described under [Publishing a library refresh](#publishing-a-library-refresh). Cached search by title/author covers all fetched pages. Explicitly indicate when the initial catalog is still filling. |
| Fetch book and cover | `GET /api/items/{itemId}?expanded=1` and `GET /api/items/{itemId}/cover` | Read ordered playable tracks, corresponding file IDs and sizes, chapters and descriptive metadata. Skip ebook-only entries. Cover failure is recoverable. |
| Download an original file | `GET /api/items/{itemId}/file/{fileId}/download` | Map the track to the actual file identifier on the target release (often `ino`). Treat it as a string. Check download permission and response integrity. |
| End a session | `POST /logout` with the origin’s refresh token | Revoke that session when reachable and clear the local credential. Address removal keeps the profile and local books. |

Use a non-admin test account with and without download permission. A Play tap on a completed download makes zero playback-session or audio HTTP requests. The app stores per-book progress locally. It does not send listening sessions or progress updates to the server in v1. If a server requires OIDC-only login, explain the required login method. Native OIDC is a separate integration from the proposed username/password baseline.

### Publishing a library refresh

Scope each refresh to its profile and library. Give the attempt a refresh generation ID, separate from any edition’s generation, and stage its pages under that ID. While it runs, browsing and search continue to use the last complete refresh generation. On first load, the UI may show the staged pages while it indicates that the catalog is still filling. If that first attempt fails while the server is reachable, say that the catalog is incomplete and offer Retry.

Once the final page succeeds, use one Room transaction to check that this attempt is still the newest refresh for its profile and library, publish its membership and metadata, and advance the last-successful-refresh time. A successful empty result clears that library’s remote listing. A failed page or a superseded attempt leaves the published listing and timestamp intact. Discard only that attempt’s staging rows. Removing a remote listing preserves the local edition and saved position.

## Download lifecycle

| State | Visible actions | Transition |
|---|---|---|
| Available | Download | Read a fresh manifest, check permissions and space, enqueue |
| Queued or transferring | Pause, cancel | Write temporary files and journal progress. User pause persists across relaunch. |
| Waiting for connection | Retry, pause, cancel | Resume on a usable paired route after source-identity reconciliation, unless the user paused. Complete verified files that the refreshed manifest still identifies are reused, never restarted. |
| Paused or recoverable failure | Resume or retry, cancel | Reopen staging and verify source identity before continuing. |
| Verifying | Show status | Verify sizes and manifest, build local timeline, publish one generation. |
| Ready | Play or resume, remove | A local session may start. Removal keeps the progress record. |
| Repair needed | Repair download, remove | Stage a fresh generation. Preserve the saved book position. |

Transient failures retry with capped exponential backoff and a per-book attempt count, shown in the Paused or recoverable failure state. Permission denials, unsupported manifests and the persistent mismatch of step 4 end the job immediately instead of retrying. Paused work stays paused across retries, process death and address changes.

A Cancel command names the job ID it was issued for and affects only that job. If cancellation commits first, the transfer remains cancelled across relaunch and its unfinished generation is reclaimed. If publication commits first, the book is Ready and the listener can remove it explicitly. A delayed Cancel command leaves the published edition and any later job intact. A repair attempt that fails must preserve any usable previously committed edition.

Keep one book transferring at a time by default and queue additional books. A per-book uniqueness constraint attaches duplicate requests for the same book to its existing job. A user-paused book yields the slot to the next queued book and resumes when the listener resumes it. Raising concurrency is an explicit later decision.

### Safe continuation across routes

1. Capture the ordered playable source list, lengths and revision evidence (for example file ID, size and modification time). Join audio files to the server’s play order. Disabled tracks, artwork and ebooks cannot silently become audio files. An ambiguous join fails with a refresh action.
2. Preflight remaining bytes plus working headroom. When the total is unknown, show bytes received and handle a write failure by preserving safe partials. Check free space again before any optional conversion or replacement that requires a second copy.
3. Bind each partial to a fingerprint of the effective download URL, the source revision and any strong entity tag. Send `Accept-Encoding: identity` so local file offsets map directly to HTTP byte offsets. For the same URL and revision with a saved strong tag and known length, send one byte range and that tag in `If-Range`. Otherwise start from zero. Accept a single-part 206 only when the strong tag matches, no content coding was applied, and `Content-Range: bytes start-end/total` satisfies `start == localLength`, `start <= end < total` and `total == expectedLength`. Check that the body delivers exactly `end - start + 1` bytes before accepting the response as complete. A valid range ending before the last byte leaves the file partial. A malformed response leaves the preceding usable prefix intact. A full 200 replaces the partial from byte zero.

   When the URL changes, refresh the manifest on the new route. Restart the partial track by default, even if the two URLs report equal ETags and sizes. A cross-route resume requires independent byte-identity evidence, such as a trusted whole-file digest for the same revision that the assembled file must match before publication. Reuse complete validated files when the refreshed manifest still identifies the same source.
4. On a 416, reconcile the expected size and source version. Treat a fully verified file as complete, otherwise restart it. On a 404 for a track, refetch item details once. If the manifest changed, stage that revision coherently. A persistent mismatch becomes a repairable error.
5. Check expected length or a trusted digest when supplied. For unknown lengths require a successful, complete transport and validate the local media through the selected parser before committing. Reject unexpected HTML/login responses, empty audio, truncated transfers and out-of-scope redirects. Probe each local track’s actual container and codec before marking it playable. Complete any required local preparation and verify the resulting timeline before publication.
6. After a process restart, compare persisted offsets with disk and run publication recovery. A notification, UI double-tap, OS retry or route switch must still result in one completed book. See the [storage transaction](03-android-and-data.md).

## References

- [HTTP validators and their scope](https://www.rfc-editor.org/rfc/rfc9110.html#section-8.8.1)
- [HTTP Content-Range validation](https://www.rfc-editor.org/rfc/rfc9110.html#section-14.4)
- [server OpenAPI document](https://github.com/advplyr/audiobookshelf/blob/master/docs/openapi.json)
- [API response examples](https://api.audiobookshelf.org/)
- [mobile JWT authentication](https://github.com/advplyr/audiobookshelf/discussions/4460)
- [file ID behavior seen in server downloads](https://github.com/advplyr/audiobookshelf/issues/5568)
- [Android local-network permissions](https://developer.android.com/privacy-and-security/local-network-permission)
