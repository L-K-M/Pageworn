package ch.lkmc.pageworn.catalog

import java.text.Collator
import java.util.Locale

/** The orders Library offers. All of them work from the cache, without the server. */
enum class CatalogSort {
    DATE_ADDED_NEWEST,
    DATE_ADDED_OLDEST,
    TITLE,
    AUTHOR;

    /**
     * Orders [rows], which arrive in the server's title order with ties by item ID. The sort is
     * stable, so equal rows keep that order. A book without the value sorted on comes last.
     */
    fun order(rows: List<CatalogRow>): List<CatalogRow> = when (this) {
        DATE_ADDED_NEWEST -> rows.sortedWith(compareBy<CatalogRow, Long?>(nullsLast(reverseOrder<Long>())) { it.addedAt })
        DATE_ADDED_OLDEST -> rows.sortedWith(compareBy<CatalogRow, Long?>(nullsLast(naturalOrder<Long>())) { it.addedAt })
        TITLE -> rows
        AUTHOR -> {
            // Case and accents do not make two authors of one.
            val collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }
            val byName = Comparator<String> { a, b -> collator.compare(a, b) }
            rows.sortedWith(compareBy<CatalogRow, String?>(nullsLast(byName)) { row -> row.authors?.takeIf { it.isNotBlank() } })
        }
    }

    companion object {
        val DEFAULT = DATE_ADDED_NEWEST
    }
}
