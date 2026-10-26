package net.horizonsend.ion.server.features.multiblock.type.inventorymanager

import net.horizonsend.ion.server.command.GlobalCompletions.fromItemString
import net.horizonsend.ion.server.features.economy.bazaar.Bazaars
import net.horizonsend.ion.server.gui.invui.misc.util.input.validator.InputValidator
import net.horizonsend.ion.server.gui.invui.misc.util.input.validator.ValidatorResult
import net.horizonsend.ion.server.miscellaneous.utils.displayNameString
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor.RED

data class CatalogEntry(val itemString: String, val displayName: String)

data class SearchResults(val query: String, val entries: List<CatalogEntry>)

/** A static list of every item that can be requested. Built once; searching never touches any inventory. */
object InventoryManagerCatalog {
	const val MIN_QUERY_LENGTH = 2
	const val MAX_QUERY_LENGTH = 32

	private val entries: List<CatalogEntry> by lazy {
		Bazaars.strings.distinct().mapNotNull { itemString ->
			val name = runCatching { fromItemString(itemString).displayNameString }.getOrNull() ?: return@mapNotNull null
			CatalogEntry(itemString, name)
		}.sortedBy { it.displayName.lowercase() }
	}

	/** Case insensitive partial matching. Every space separated term must appear in the display name or item string. */
	fun search(query: String): List<CatalogEntry> {
		val terms = query.trim().split(' ').filter { it.isNotEmpty() }
		if (terms.isEmpty()) return listOf()

		return entries.filter { entry ->
			terms.all { term ->
				entry.displayName.contains(term, ignoreCase = true) || entry.itemString.contains(term, ignoreCase = true)
			}
		}
	}

	class SearchValidator : InputValidator<SearchResults> {
		override fun isValid(input: String): ValidatorResult<SearchResults> {
			val query = input.trim()

			if (query.length < MIN_QUERY_LENGTH) {
				return ValidatorResult.FailureResult(text("Enter at least $MIN_QUERY_LENGTH characters.", RED))
			}

			if (query.length > MAX_QUERY_LENGTH) {
				return ValidatorResult.FailureResult(text("Search must be at most $MAX_QUERY_LENGTH characters.", RED))
			}

			val results = search(query)
			if (results.isEmpty()) return ValidatorResult.FailureResult(text("No items found for \"$query\".", RED))

			return ValidatorResult.ValidatorSuccessSingleEntry(SearchResults(query, results))
		}
	}
}
