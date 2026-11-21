package ch.lkmc.pageworn.core

/**
 * Identity of one book on this phone: one Audiobookshelf library item within one local profile.
 * Titles never identify a book. Server IDs are opaque strings.
 */
data class BookKey(val profileId: String, val itemId: String) {
    init {
        require(profileId.isNotEmpty() && ':' !in profileId) { "profileId must be a non-empty local UUID" }
        require(itemId.isNotEmpty()) { "itemId must not be empty" }
    }

    /** Media ID used by the playback session, the car list and the resumption control. */
    val mediaId: String get() = "$MEDIA_ID_PREFIX$profileId:$itemId"

    override fun toString(): String = "BookKey($profileId, $itemId)"

    companion object {
        const val MEDIA_ID_PREFIX = "book:"

        /** Parses [mediaId]; returns null for anything that is not a book media ID. */
        fun fromMediaId(mediaId: String?): BookKey? {
            if (mediaId == null || !mediaId.startsWith(MEDIA_ID_PREFIX)) return null
            val rest = mediaId.substring(MEDIA_ID_PREFIX.length)
            val colon = rest.indexOf(':')
            if (colon <= 0 || colon == rest.length - 1) return null
            return BookKey(rest.substring(0, colon), rest.substring(colon + 1))
        }
    }
}
