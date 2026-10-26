package net.horizonsend.ion.server.features.multiblock.type.inventorymanager

import com.manya.pdc.base.UuidDataType
import net.horizonsend.ion.common.extensions.information
import net.horizonsend.ion.common.extensions.success
import net.horizonsend.ion.common.extensions.userError
import net.horizonsend.ion.server.features.multiblock.Multiblock
import net.horizonsend.ion.server.features.multiblock.entity.MultiblockEntity
import net.horizonsend.ion.server.features.multiblock.entity.PersistentMultiblockData
import net.horizonsend.ion.server.features.multiblock.entity.type.ticked.SyncTickingMultiblockEntity
import net.horizonsend.ion.server.features.multiblock.entity.type.ticked.TickedMultiblockEntityParent
import net.horizonsend.ion.server.features.multiblock.manager.MultiblockManager
import net.horizonsend.ion.server.features.multiblock.shape.MultiblockShape
import net.horizonsend.ion.server.features.multiblock.type.DisplayNameMultilblock
import net.horizonsend.ion.server.features.multiblock.type.EntityMultiblock
import net.horizonsend.ion.server.features.multiblock.type.InteractableMultiblock
import net.horizonsend.ion.server.features.multiblock.type.economy.RemotePipeMultiblock
import net.horizonsend.ion.server.features.transport.inputs.IOData
import net.horizonsend.ion.server.features.transport.nodes.util.Path
import net.horizonsend.ion.server.listener.misc.ProtectionListener
import net.horizonsend.ion.server.miscellaneous.registrations.persistence.NamespacedKeys
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.BlockKey
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.Vec3i
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.toVec3i
import net.horizonsend.ion.server.miscellaneous.utils.isBlockLoaded
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.minecraft.world.CompoundContainer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.block.Sign
import org.bukkit.craftbukkit.inventory.CraftInventory
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.Inventory
import org.bukkit.persistence.PersistentDataAdapterContext
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * Pulls a requested item out of the item pipe network and into the chest of the structure.
 *
 * The structure is the Box Shield layout, with the iron block to the right of the sign (top row) replaced by a chest.
 **/
object InventoryManagerMultiblock : Multiblock(), InteractableMultiblock, DisplayNameMultilblock, EntityMultiblock<InventoryManagerMultiblock.InventoryManagerEntity> {
	override val name = "inventorymanager"

	override val displayName: Component get() = text("Inventory Manager")
	override val description: Component get() = text("Pulls a chosen item and quantity out of the connected item pipe network and into its chest.")

	override val signText = createSignText(
		line1 = "&6Inventory",
		line2 = "&6Manager",
		line3 = null,
		line4 = null
	)

	override fun MultiblockShape.buildStructure() {
		z(+0) {
			y(-1) {
				x(-1).ironBlock()
				x(+0).anyGlassPane()
				x(+1).ironBlock()
			}

			y(+0) {
				x(-1).ironBlock()
				x(+0).anyGlassPane()
				x(+1).type(Material.CHEST)
			}
		}

		z(+1) {
			y(-1) {
				x(-1).ironBlock()
				x(+0).titaniumBlock()
				x(+1).ironBlock()
			}

			y(+0) {
				x(-1).ironBlock()
				x(+0).anyGlass()
				x(+1).ironBlock()
			}
		}
	}

	override fun createEntity(
		manager: MultiblockManager,
		data: PersistentMultiblockData,
		world: World,
		x: Int,
		y: Int,
		z: Int,
		structureDirection: BlockFace
	): InventoryManagerEntity = InventoryManagerEntity(data, manager, x, y, z, world, structureDirection)

	override fun onSignInteract(sign: Sign, player: Player, event: PlayerInteractEvent) {
		getMultiblockEntity(sign)?.openMenu(player)
	}

	class InventoryManagerEntity(
		data: PersistentMultiblockData,
		manager: MultiblockManager,
		x: Int,
		y: Int,
		z: Int,
		world: World,
		structureDirection: BlockFace
	) : MultiblockEntity(manager, InventoryManagerMultiblock, world, x, y, z, structureDirection), SyncTickingMultiblockEntity, RemotePipeMultiblock {
		override val ioData: IOData = none()
		override val tickingManager = TickedMultiblockEntityParent.TickingManager(interval = TICK_INTERVAL)

		// preTick runs off the main thread. Integrity is checked on the main thread while a request is active instead.
		override fun shouldCheckIntegrity(): Boolean = false

		class Request(val itemString: String, val total: Int, var remaining: Int, val requester: UUID?) {
			val delivered: Int get() = total - remaining
		}

		private class SourceRef(val key: BlockKey, val path: Path)

		var request: Request? = null; private set

		init {
			val itemString = data.getAdditionalData(NamespacedKeys.INVENTORY_MANAGER_ITEM, PersistentDataType.STRING)
			val remaining = data.getAdditionalData(NamespacedKeys.INVENTORY_MANAGER_REMAINING, PersistentDataType.INTEGER)
			val total = data.getAdditionalData(NamespacedKeys.INVENTORY_MANAGER_TOTAL, PersistentDataType.INTEGER)

			if (itemString != null && remaining != null && total != null && remaining > 0) {
				request = Request(itemString, total, remaining, data.getAdditionalData(NamespacedKeys.INVENTORY_MANAGER_REQUESTER, UuidDataType()))
			}
		}

		override fun storeAdditionalData(store: PersistentMultiblockData, adapterContext: PersistentDataAdapterContext) {
			val current = request ?: return

			store.addAdditionalData(NamespacedKeys.INVENTORY_MANAGER_ITEM, PersistentDataType.STRING, current.itemString)
			store.addAdditionalData(NamespacedKeys.INVENTORY_MANAGER_REMAINING, PersistentDataType.INTEGER, current.remaining)
			store.addAdditionalData(NamespacedKeys.INVENTORY_MANAGER_TOTAL, PersistentDataType.INTEGER, current.total)
			current.requester?.let { store.addAdditionalData(NamespacedKeys.INVENTORY_MANAGER_REQUESTER, UuidDataType(), it) }
		}

		//<editor-fold desc="Menus">
		fun hasAccess(player: Player): Boolean {
			if (ProtectionListener.denyBlockAccess(player, getSignLocation(), null)) {
				player.userError("You don't have access to this inventory manager!")
				return false
			}

			return true
		}

		fun openMenu(player: Player) {
			if (removed) return
			if (!hasAccess(player)) return

			if (request != null) InventoryManagerGuis.openStatus(player, this)
			else InventoryManagerGuis.openSearch(player, this)
		}
		//</editor-fold>

		//<editor-fold desc="Output chest">
		/** The live, single-chest inventory of the output chest. Must be called on the main thread. */
		fun getOutputInventory(): Inventory? {
			val state = getBlockRelative(CHEST_OFFSET.x, CHEST_OFFSET.y, CHEST_OFFSET.z).getState(false) as? Chest ?: return null

			// A double chest would extend the output beyond the structure
			if (state.inventory.holder is DoubleChest) return null

			return state.blockInventory
		}

		/** The number of the matching item the output chest can currently accept. Must be called on the main thread. */
		fun getFreeCapacity(itemString: String): Int {
			val matcher = InventoryManagerItemMatcher.create(itemString) ?: return 0
			val output = getOutputInventory() ?: return 0

			return InventoryTransfer.freeCapacity(output, matcher)
		}
		//</editor-fold>

		//<editor-fold desc="Requests">
		private var nextRequestAllowed = 0L

		/**
		 * Attempts to start a request. Returns an error message, or null if the request was started.
		 * Must be called on the main thread.
		 **/
		fun startRequest(player: Player, itemString: String, amount: Int): String? {
			if (removed) return "This inventory manager no longer exists."
			if (!hasAccess(player)) return "You don't have access to this inventory manager."
			if (request != null) return "This inventory manager is already fulfilling a request."
			if (System.currentTimeMillis() < nextRequestAllowed) return "Please wait a moment before starting another request."
			if (!isIntact(false)) return "The multiblock is incomplete."

			val matcher = InventoryManagerItemMatcher.create(itemString) ?: return "That item can't be requested."
			val output = getOutputInventory() ?: return "The output chest is missing, or is part of a double chest."

			val capacity = InventoryTransfer.freeCapacity(output, matcher)
			if (capacity <= 0) return "The output chest is full."
			if (amount !in 1..capacity) return "The output chest only has room for $capacity of that item."

			request = Request(itemString, amount, amount, player.uniqueId)

			sources = listOf()
			lastSourceRefresh = 0L
			sourceCursor = 0
			slotCursor = 0
			movedThisPass = 0
			refreshedThisPass = false
			tickingManager.clearSleep()

			return null
		}

		fun cancelRequest() {
			val current = request ?: return
			finish(current, "Request cancelled", false)
		}

		private fun finish(current: Request, reason: String, error: Boolean) {
			request = null
			sources = listOf()
			nextRequestAllowed = System.currentTimeMillis() + REQUEST_COOLDOWN_MS

			val player = current.requester?.let(Bukkit::getPlayer) ?: return
			val summary = "$reason. Delivered {0} of {1}."

			if (error) player.userError(summary, current.delivered, current.total)
			else if (current.remaining == 0) player.success(summary, current.delivered, current.total)
			else player.information(summary, current.delivered, current.total)
		}
		//</editor-fold>

		//<editor-fold desc="Ticking">
		private var sources: List<SourceRef> = listOf()
		private var lastSourceRefresh = 0L
		private var sourceCursor = 0
		private var slotCursor = 0
		private var movedThisPass = 0
		private var refreshedThisPass = false

		override fun tick() {
			val current = request

			if (current == null) {
				tickingManager.sleepForTicks(IDLE_SLEEP_TICKS)
				return
			}

			if (removed) return

			if (!isIntact(false)) return finish(current, "The multiblock was broken", true)

			val matcher = InventoryManagerItemMatcher.create(current.itemString) ?: return finish(current, "That item can no longer be requested", true)
			val output = getOutputInventory() ?: return finish(current, "The output chest is missing", true)

			val now = System.currentTimeMillis()
			val refreshDue = now - lastSourceRefresh >= SOURCE_REFRESH_MS || (sources.isEmpty() && now - lastSourceRefresh >= EMPTY_SOURCE_REFRESH_MS)
			if (refreshDue) refreshSources(now)

			transfer(current, matcher, output)
		}

		private fun transfer(current: Request, matcher: InventoryManagerItemMatcher, output: Inventory) {
			var slotsScanned = 0
			var moves = 0

			val outputContainer = (output as? CraftInventory)?.inventory
			val dropLocation = getBlockRelative(CHEST_OFFSET.x, CHEST_OFFSET.y, CHEST_OFFSET.z).location.add(0.5, 1.0, 0.5)

			while (slotsScanned < MAX_SLOTS_PER_TICK && moves < MAX_MOVES_PER_TICK) {
				if (sourceCursor >= sources.size) {
					// A full pass over every source finished without finding anything, and the sources were up to date
					if (movedThisPass == 0 && refreshedThisPass) return finish(current, "No more of that item is available in the connected inventories", false)

					sourceCursor = 0
					slotCursor = 0

					// Nothing was found using possibly stale sources, so re-discover them before concluding there is nothing left
					if (movedThisPass == 0) lastSourceRefresh = 0L

					movedThisPass = 0
					refreshedThisPass = false
					return
				}

				val source = sources[sourceCursor]
				val inventory = resolveSource(source, outputContainer)

				if (inventory == null || slotCursor >= inventory.size) {
					sourceCursor++
					slotCursor = 0
					continue
				}

				val slot = slotCursor++
				slotsScanned++

				val result = InventoryTransfer.moveFromSlot(inventory, slot, output, matcher, current.remaining, dropLocation)

				when (result) {
					InventoryTransfer.MoveResult.NoMatch -> continue
					InventoryTransfer.MoveResult.DestinationFull -> return finish(current, "The output chest is full", true)
					is InventoryTransfer.MoveResult.Moved -> {
						moves++
						movedThisPass += result.moved
						current.remaining -= result.moved

						if (current.remaining <= 0) return finish(current, "Request complete", false)

						// Revisit the slot, it may still hold more of the item
						if (result.moved == 0) return finish(current, "The output chest is full", true)
						slotCursor = slot
					}
				}
			}
		}

		/** Re-resolves the live inventory of the source and checks that it is still connected. Returns null if it can't be used. */
		private fun resolveSource(source: SourceRef, outputContainer: Any?): Inventory? {
			val transportManager = manager.getTransportManager()
			val cacheHolder = transportManager.itemPipeManager

			val global = transportManager.getGlobalCoordinate(toVec3i(source.key))
			if (!isBlockLoaded(world, global.x, global.y, global.z)) return null

			if (!source.path.isValid(cacheHolder)) return null

			val inventory = cacheHolder.cache.getInventory(source.key) ?: return null

			// Never pull from the output chest
			val container = inventory.inventory
			if (container === outputContainer) return null
			if (container is CompoundContainer && outputContainer is net.minecraft.world.Container && container.contains(outputContainer)) return null

			return inventory
		}

		private fun refreshSources(now: Long) {
			lastSourceRefresh = now
			refreshedThisPass = true

			val cache = manager.getTransportManager().itemPipeManager.cache
			val found = LinkedHashMap<BlockKey, SourceRef>()

			for ((_, results) in getNetworkedExtractors(PIPE_SEARCH_POINTS)) {
				for (result in results) {
					for ((_, key) in cache.getSources(result.destinationPosition)) {
						if (found.size >= MAX_SOURCES) break

						found.putIfAbsent(key, SourceRef(key, result.trackedPath))
					}
				}
			}

			sources = found.values.toList()
			sourceCursor = 0
			slotCursor = 0
		}
		//</editor-fold>

		companion object {
			private const val TICK_INTERVAL = 10
			private const val IDLE_SLEEP_TICKS = 40

			// Per-tick work limits that keep an active request from causing lag
			private const val MAX_SLOTS_PER_TICK = 256
			private const val MAX_MOVES_PER_TICK = 8

			private const val MAX_SOURCES = 64
			private const val SOURCE_REFRESH_MS = 10_000L
			private const val EMPTY_SOURCE_REFRESH_MS = 2_000L
			private const val REQUEST_COOLDOWN_MS = 1_000L

			/** The chest, relative to the multiblock origin (right, up, forward). */
			private val CHEST_OFFSET = Vec3i(1, 0, 0)

			/** The glass blocks of the structure, which can be connected to item pipes. */
			private val PIPE_SEARCH_POINTS = arrayOf(
				Vec3i(0, 0, 0),
				Vec3i(0, -1, 0),
				Vec3i(0, 0, 1)
			)
		}
	}
}
