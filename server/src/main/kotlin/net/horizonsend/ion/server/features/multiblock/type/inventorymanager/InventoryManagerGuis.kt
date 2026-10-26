package net.horizonsend.ion.server.features.multiblock.type.inventorymanager

import net.horizonsend.ion.common.extensions.information
import net.horizonsend.ion.common.extensions.userError
import net.horizonsend.ion.common.utils.text.colors.HEColorScheme.Companion.HE_MEDIUM_GRAY
import net.horizonsend.ion.common.utils.text.ofChildren
import net.horizonsend.ion.server.command.GlobalCompletions.fromItemString
import net.horizonsend.ion.server.features.gui.GuiItem
import net.horizonsend.ion.server.features.gui.GuiItems
import net.horizonsend.ion.server.features.gui.GuiText
import net.horizonsend.ion.server.features.multiblock.type.inventorymanager.InventoryManagerMultiblock.InventoryManagerEntity
import net.horizonsend.ion.server.gui.invui.InvUIWindowWrapper
import net.horizonsend.ion.server.gui.invui.ListInvUIWindow
import net.horizonsend.ion.server.gui.invui.bazaar.stripAttributes
import net.horizonsend.ion.server.gui.invui.misc.util.input.TextInputMenu
import net.horizonsend.ion.server.gui.invui.misc.util.input.validator.RangeIntegerValidator
import net.horizonsend.ion.server.miscellaneous.utils.text.itemName
import net.horizonsend.ion.server.miscellaneous.utils.updateDisplayName
import net.horizonsend.ion.server.miscellaneous.utils.updateLore
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor.GRAY
import net.kyori.adventure.text.format.NamedTextColor.GREEN
import net.kyori.adventure.text.format.NamedTextColor.RED
import net.kyori.adventure.text.format.NamedTextColor.WHITE
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.gui.PagedGui
import xyz.xenondevs.invui.gui.structure.Markers
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.invui.item.impl.AbstractItem
import xyz.xenondevs.invui.window.Window

/**
 * The menu flow of the inventory manager: search -> results -> quantity, or status if a request is already running.
 * All of these run on the main thread.
 **/
object InventoryManagerGuis {
	fun openSearch(player: Player, entity: InventoryManagerEntity) {
		TextInputMenu(
			viewer = player,
			title = text("Search for an Item"),
			description = text("Partial names work, e.g. \"ron\""),
			backButtonHandler = null,
			inputValidator = InventoryManagerCatalog.SearchValidator(),
			componentTransformer = { results: SearchResults -> text("${results.entries.size} matching item${if (results.entries.size == 1) "" else "s"}") },
			successfulInputHandler = { _, result -> openResults(player, entity, result.result) }
		).openGui()
	}

	fun openResults(player: Player, entity: InventoryManagerEntity, results: SearchResults) {
		InventoryManagerResultsMenu(player, entity, results).openGui()
	}

	fun openQuantity(player: Player, entity: InventoryManagerEntity, entry: CatalogEntry, results: SearchResults) {
		InventoryManagerQuantityMenu(player, entity, entry, results).openGui()
	}

	fun openStatus(player: Player, entity: InventoryManagerEntity) {
		InventoryManagerStatusMenu(player, entity).openGui()
	}

	private fun itemStackFor(itemString: String): ItemStack? = runCatching { fromItemString(itemString).stripAttributes() }.getOrNull()

	//<editor-fold desc="Results">
	class InventoryManagerResultsMenu(
		viewer: Player,
		private val entity: InventoryManagerEntity,
		private val results: SearchResults
	) : ListInvUIWindow<CatalogEntry>(viewer, 0, async = false) {
		override val listingsPerPage: Int = 36

		// The blank spacer row, so it does not sit over the control buttons
		override val pageNumberLine: Int = 8

		override fun generateEntries(): List<CatalogEntry> = results.entries

		override fun createItem(entry: CatalogEntry): Item = CatalogItem(entry) { player -> openQuantity(player, entity, entry, results) }

		override fun buildWindow(): Window {
			val gui = PagedGui.items()
				.setStructure(
					"# # # # # # # # #",
					"# # # # # # # # #",
					"# # # # # # # # #",
					"# # # # # # # # #",
					". . . . . . . . .",
					"l . . . c . . . r",
				)
				.addIngredient('#', Markers.CONTENT_LIST_SLOT_HORIZONTAL)
				.addIngredient('l', GuiItems.PageLeftItem())
				.addIngredient('r', GuiItems.PageRightItem())
				.addIngredient('c', researchButton)
				.setContent(items)
				.handlePageChange()
				.build()

			return normalWindow(gui)
		}

		override fun buildTitle(): Component {
			val overlay = GuiText("")
				.setSlotOverlay(
					". . . . . . . . .",
					". . . . . . . . .",
					". . . . . . . . .",
					". . . . . . . . .",
					". . . . . . . . .",
					"# # # # # # # # #",
				)
				.build()

			val query = if (results.query.length > 20) results.query.take(20) + "..." else results.query
			val titleText = GuiText("")
				.add(text("Results for \"$query\""), line = -1)
				.build()

			return ofChildren(overlay, getPageNumberText(), titleText)
		}

		private val researchButton = GuiItems.CustomControlItem(
			text("Cancel and Search Again").itemName,
			GuiItem.CANCEL,
			callback = { _: ClickType, player: Player, _: InventoryClickEvent ->
				player.closeInventory()
				openSearch(player, entity)
			}
		)
	}

	/** A search result. The item is only built when the slot is first displayed. */
	private class CatalogItem(val entry: CatalogEntry, val onSelect: (Player) -> Unit) : AbstractItem() {
		private val provider: ItemProvider by lazy {
			val stack = itemStackFor(entry.itemString)
				?.updateLore(listOf(text("Click to select", HE_MEDIUM_GRAY)))
				?: ItemStack(Material.BARRIER).updateDisplayName(text(entry.displayName, RED))

			ItemProvider { stack }
		}

		override fun getItemProvider(): ItemProvider = provider

		override fun handleClick(clickType: ClickType, player: Player, event: InventoryClickEvent) {
			onSelect(player)
		}
	}
	//</editor-fold>

	//<editor-fold desc="Quantity">
	class InventoryManagerQuantityMenu(
		viewer: Player,
		private val entity: InventoryManagerEntity,
		private val entry: CatalogEntry,
		private val results: SearchResults
	) : InvUIWindowWrapper(viewer) {
		private val capacity: Int get() = entity.getFreeCapacity(entry.itemString)

		override fun buildWindow(): Window {
			val room = capacity

			val gui = Gui.normal()
				.setStructure(
					". . . . i . . . .",
					". a b c d e . m .",
					"x . . . . . . . .",
				)
				.addIngredient('i', infoItem(room))
				.addIngredient('a', presetButton(PRESETS[0], room))
				.addIngredient('b', presetButton(PRESETS[1], room))
				.addIngredient('c', presetButton(PRESETS[2], room))
				.addIngredient('d', presetButton(PRESETS[3], room))
				.addIngredient('e', presetButton(PRESETS[4], room))
				.addIngredient('m', customButton(room))
				.addIngredient('x', backButton)
				.build()

			return normalWindow(gui)
		}

		override fun buildTitle(): Component = GuiText("")
			.add(text("Select Quantity"), line = -1)
			.build()

		private fun infoItem(capacity: Int): Item {
			val stack = (itemStackFor(entry.itemString) ?: ItemStack(Material.BARRIER))
				.updateLore(listOf(
					text("Choose how many to retrieve.", GRAY),
					text("Room in the output chest: $capacity", GRAY)
				))

			return GuiItems.createButton(stack) { _, _, _ -> }
		}

		private fun presetButton(amount: Int, capacity: Int): Item {
			val available = amount <= capacity

			val stack = if (available) {
				(itemStackFor(entry.itemString) ?: ItemStack(Material.BARRIER))
					.updateDisplayName(text("Retrieve $amount", GREEN).itemName)
					.updateLore(listOf(text("Click to start", HE_MEDIUM_GRAY)))
			} else {
				GuiItem.CANCEL.makeItem(text("Retrieve $amount", RED).itemName)
					.updateLore(listOf(text("The output chest only has room for $capacity.", HE_MEDIUM_GRAY)))
			}

			return GuiItems.createButton(stack) { _, player, _ ->
				if (available) confirm(player, amount)
			}
		}

		private fun customButton(capacity: Int): Item {
			val stack = GuiItem.PLUS.makeItem(text("Custom Amount", WHITE).itemName)
				.updateLore(listOf(text(if (capacity > 0) "Between 1 and $capacity" else "The output chest is full", HE_MEDIUM_GRAY)))

			return GuiItems.createButton(stack) { _, player, _ ->
				val currentCapacity = this.capacity

				if (currentCapacity <= 0) {
					player.userError("The output chest is full.")
					return@createButton
				}

				TextInputMenu(
					viewer = player,
					title = text("Enter Quantity"),
					description = text("Between 1 and $currentCapacity"),
					backButtonHandler = { openGui() },
					inputValidator = RangeIntegerValidator(1..currentCapacity),
					componentTransformer = { amount: Int -> text("Retrieve $amount ${entry.displayName}") },
					successfulInputHandler = { _, result -> confirm(player, result.result) }
				).openGui()
			}
		}

		private fun confirm(player: Player, amount: Int) {
			val error = entity.startRequest(player, entry.itemString, amount)

			if (error != null) {
				player.userError(error)
				return
			}

			player.closeInventory()
			player.information("Retrieving {0} {1} from the connected inventories.", amount, entry.displayName)
		}

		private val backButton = GuiItems.CustomControlItem(
			text("Back to Results").itemName,
			GuiItem.CANCEL,
			callback = { _: ClickType, player: Player, _: InventoryClickEvent ->
				player.closeInventory()
				openResults(player, entity, results)
			}
		)

		companion object {
			private val PRESETS = intArrayOf(1, 16, 64, 256, 1024)
		}
	}
	//</editor-fold>

	//<editor-fold desc="Status">
	class InventoryManagerStatusMenu(
		viewer: Player,
		private val entity: InventoryManagerEntity
	) : InvUIWindowWrapper(viewer) {
		override fun buildWindow(): Window {
			val gui = Gui.normal()
				.setStructure(
					". . . . i . . . .",
					". . . . . . . . .",
					"x . . . . . . . c",
				)
				.addIngredient('i', infoItem)
				.addIngredient('x', GuiItems.closeMenuItem(viewer))
				.addIngredient('c', cancelButton)
				.build()

			return normalWindow(gui)
		}

		override fun buildTitle(): Component = GuiText("")
			.add(text("Inventory Manager"), line = -1)
			.build()

		// Reads the request live. Clicking the item refreshes it.
		private val infoItem = object : AbstractItem() {
			override fun getItemProvider(): ItemProvider {
				val request = entity.request
					?: return ItemProvider { ItemStack(Material.BARRIER).updateDisplayName(text("No active request", GRAY).itemName) }

				val stack = (itemStackFor(request.itemString) ?: ItemStack(Material.BARRIER))
					.updateLore(listOf(
						text("Delivered ${request.delivered} of ${request.total}", GRAY),
						text("Click to refresh", HE_MEDIUM_GRAY)
					))

				return ItemProvider { stack }
			}

			override fun handleClick(clickType: ClickType, player: Player, event: InventoryClickEvent) {
				notifyWindows()
			}
		}

		private val cancelButton = GuiItems.createButton(GuiItem.CANCEL.makeItem(text("Cancel Request", RED).itemName)) { _, player, _ ->
			if (entity.hasAccess(player)) {
				entity.cancelRequest()
				player.closeInventory()
			}
		}
	}
	//</editor-fold>
}
