package net.horizonsend.ion.server.features.multiblock.type.misc

import net.horizonsend.ion.common.extensions.information
import net.horizonsend.ion.common.extensions.userError
import net.horizonsend.ion.server.features.multiblock.Multiblock
import net.horizonsend.ion.server.features.multiblock.entity.MultiblockEntity
import net.horizonsend.ion.server.features.multiblock.entity.PersistentMultiblockData
import net.horizonsend.ion.server.features.multiblock.manager.MultiblockManager
import net.horizonsend.ion.server.features.multiblock.manager.ShipMultiblockManager
import net.horizonsend.ion.server.features.multiblock.shape.MultiblockShape
import net.horizonsend.ion.server.features.multiblock.type.DisplayNameMultilblock
import net.horizonsend.ion.server.features.multiblock.type.EntityMultiblock
import net.horizonsend.ion.server.features.nations.region.Regions
import net.horizonsend.ion.server.features.nations.region.types.RegionDominionTerritory
import net.horizonsend.ion.server.features.nations.region.types.RegionSpaceStation
import net.horizonsend.ion.server.features.transport.inputs.IOData
import net.horizonsend.ion.server.features.world.IonWorld.Companion.hasFlag
import net.horizonsend.ion.server.features.world.IonWorld.Companion.ion
import net.horizonsend.ion.server.features.world.WorldFlag
import net.horizonsend.ion.server.miscellaneous.utils.Tasks
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

object AIDefender : Multiblock(), EntityMultiblock<AIDefender.AIDefenderEntity>, DisplayNameMultilblock {
	override val name = "aidefender"

	override val signText = createSignText(
		line1 = "&cAI Ship",
		line2 = "&cInterdiction",
		line3 = "&7Control",
		line4 = "AIDefender Co"
	)

	const val radius = 500.0

	private data class ActiveDefender(val world: World, val x: Int, val z: Int)

	private val trackedDefenders = mutableListOf<AIDefenderEntity>()
	private val activeDefenders = ConcurrentHashMap<AIDefenderEntity, ActiveDefender>()
	private var nextDefenderToRefresh = 0

	override val displayName: Component get() = text("AI Defender")
	override val description: Component get() = text(
		"Prevents AI Loci from spawning nearby and despawns AI ships entering its ${radius.toInt()}-block horizontal radius. Only functions in claimed space stations or claimed deep space."
	)

	override fun MultiblockShape.buildStructure() {
		z(-1) {
			y(-1) {
				x(-1).anyStairs()
				x(+0).ironBlock()
				x(+1).anyStairs()
			}

			y(+0) {
				x(-1).anyGlassPane()
				x(+1).anyGlassPane()
			}
		}

		z(+0) {
			y(-1) {
				x(-1).ironBlock()
				x(+0).emeraldBlock()
				x(+1).ironBlock()
			}

			y(+0) {
				x(-1).anyGlassPane()
				x(+0).redstoneBlock()
				x(+1).anyGlassPane()
			}

			y(+1) {
				x(+0).anyGlass()
			}
		}

		z(+1) {
			y(-1) {
				x(-1).anyStairs()
				x(+0).ironBlock()
				x(+1).anyStairs()
			}

			y(+0) {
				x(-1).anyGlassPane()
				x(+0).anyGlassPane()
				x(+1).anyGlassPane()
			}
		}
	}

	override fun onTransformSign(player: Player, sign: Sign) {
		player.information("Created AI Defender (radius: ${radius.toInt()} blocks).")
		if (!isValidClaimedLocation(sign.location)) {
			player.userError("Warning: AI Defenders only function inside claimed Space Stations or claimed Deep Space territories.")
		}
	}

	fun isValidClaimedLocation(location: Location): Boolean {
		if (Regions.findFirstOf<RegionSpaceStation<*, *>>(location) != null) return true

		val dominion = Regions.findFirstOf<RegionDominionTerritory>(location)
		if (dominion != null && dominion.isClaimed) return true

		return false
	}

	fun startTracking() {
		Tasks.syncRepeat(1L, 1L, ::refreshDefenders)
	}

	private fun refreshDefenders() {
		Tasks.checkMainThread()
		if (trackedDefenders.isEmpty()) {
			nextDefenderToRefresh = 0
			return
		}

		repeat(minOf(8, trackedDefenders.size)) {
			if (nextDefenderToRefresh >= trackedDefenders.size) nextDefenderToRefresh = 0
			refreshDefender(trackedDefenders[nextDefenderToRefresh++])
		}
	}

	private fun refreshDefender(defender: AIDefenderEntity) {
		Tasks.checkMainThread()
		if (!defender.isActive()) {
			activeDefenders.remove(defender)
			return
		}

		val position = defender.globalVec3i
		activeDefenders[defender] = ActiveDefender(defender.world, position.x, position.z)
	}

	fun isNearActiveDefender(world: World, x: Double, z: Double, extraRadius: Double = 0.0): Boolean {
		val maxDist = radius + extraRadius
		val maxDistSquared = maxDist * maxDist

		return activeDefenders.values.asSequence()
			.filter { it.world === world }
			.filter {
				val dx = x - it.x
				val dz = z - it.z
				(dx * dx + dz * dz) <= maxDistSquared
			}
			.any()
	}

	override fun createEntity(
		manager: MultiblockManager,
		data: PersistentMultiblockData,
		world: World,
		x: Int,
		y: Int,
		z: Int,
		structureDirection: BlockFace
	): AIDefenderEntity {
		return AIDefenderEntity(manager, x, y, z, world, structureDirection)
	}

	class AIDefenderEntity(
		manager: MultiblockManager,
		x: Int,
		y: Int,
		z: Int,
		world: World,
		signDirection: BlockFace
	) : MultiblockEntity(manager, AIDefender, world, x, y, z, signDirection) {
		override val ioData: IOData = none()

		private var registeredInWorld: Boolean = false

		fun isActive(): Boolean {
			if (removed) return false
			if (manager is ShipMultiblockManager) return false
			if (!isIntact(false)) return false
			return isValidClaimedLocation(location)
		}

		override fun onLoad() {
			if (manager !is ShipMultiblockManager && !registeredInWorld) {
				world.ion.multiblockManager.register(this)
				registeredInWorld = true
				trackedDefenders.add(this)
				refreshDefender(this)
			}
		}

		override fun handleRemoval() {
			if (registeredInWorld) {
				world.ion.multiblockManager.deregister(this)
				registeredInWorld = false
				trackedDefenders.remove(this)
				activeDefenders.remove(this)
				if (nextDefenderToRefresh > trackedDefenders.size) nextDefenderToRefresh = 0
			}
		}

		override fun onUnload() {
			if (registeredInWorld) {
				world.ion.multiblockManager.deregister(this)
				registeredInWorld = false
				trackedDefenders.remove(this)
				activeDefenders.remove(this)
				if (nextDefenderToRefresh > trackedDefenders.size) nextDefenderToRefresh = 0
			}
		}
	}
}
