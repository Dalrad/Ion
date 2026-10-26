package net.horizonsend.ion.server.features.multiblock.type.inventorymanager

import net.horizonsend.ion.server.command.GlobalCompletions.fromItemString
import net.horizonsend.ion.server.command.GlobalCompletions.toItemString
import net.horizonsend.ion.server.core.registration.registries.CustomItemRegistry.Companion.customItem
import org.bukkit.Location
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import kotlin.math.min

/**
 * Identifies the exact item an inventory manager request is for.
 *
 * Vanilla items must be identical to a plain stack of that material, so renamed, enchanted, damaged or
 * container items with contents are never matched. Custom items are matched by their bazaar item string.
 **/
class InventoryManagerItemMatcher private constructor(val itemString: String, private val template: ItemStack) {
	val maxStackSize: Int get() = template.maxStackSize

	fun matches(stack: ItemStack?): Boolean {
		if (stack == null || stack.type.isAir || stack.amount <= 0) return false

		if (stack.customItem != null) return toItemString(stack) == itemString

		return template.isSimilar(stack)
	}

	companion object {
		fun create(itemString: String): InventoryManagerItemMatcher? {
			val template = runCatching { fromItemString(itemString) }.getOrNull() ?: return null
			if (template.type.isAir) return null

			return InventoryManagerItemMatcher(itemString, template.asOne())
		}
	}
}

object InventoryTransfer {
	/** How many more of the matching item the destination could accept right now. Does not modify anything. */
	fun freeCapacity(destination: Inventory, matcher: InventoryManagerItemMatcher): Int {
		var total = 0

		for (stack in destination.storageContents) {
			if (stack == null || stack.type.isAir) {
				total += matcher.maxStackSize
				continue
			}

			if (!matcher.matches(stack)) continue

			total += maxOf(0, stack.maxStackSize - stack.amount)
		}

		return total
	}

	sealed interface MoveResult {
		/** Nothing matching was found in the slot. */
		data object NoMatch : MoveResult

		/** The destination has no more room. */
		data object DestinationFull : MoveResult

		/** [moved] items were removed from the source and inserted into the destination. */
		data class Moved(val moved: Int) : MoveResult
	}

	/**
	 * Moves up to [limit] matching items from a single source slot to the destination.
	 *
	 * Must be called on the main thread. The slot is re-read live, the items are removed from the source first and
	 * only then inserted, so a failure can only ever lose items, never duplicate them. Anything the destination does
	 * not accept is returned to the source, and if the source can not take it back it is dropped at [dropLocation].
	 **/
	fun moveFromSlot(
		source: Inventory,
		slot: Int,
		destination: Inventory,
		matcher: InventoryManagerItemMatcher,
		limit: Int,
		dropLocation: Location
	): MoveResult {
		if (limit <= 0) return MoveResult.NoMatch

		val live = source.getItem(slot)
		if (live == null || !matcher.matches(live)) return MoveResult.NoMatch

		val space = freeCapacity(destination, matcher)
		if (space <= 0) return MoveResult.DestinationFull

		val take = min(min(live.amount, limit), space)

		val moving = live.clone()
		moving.amount = take

		// Remove from the source first
		if (take >= live.amount) {
			source.setItem(slot, null)
		} else {
			val remainder = live.clone()
			remainder.amount = live.amount - take
			source.setItem(slot, remainder)
		}

		var notInserted = 0

		for (leftover in destination.addItem(moving).values) {
			notInserted += leftover.amount

			for (dropped in source.addItem(leftover).values) {
				dropLocation.world.dropItemNaturally(dropLocation, dropped)
			}
		}

		return MoveResult.Moved(take - notInserted)
	}
}
