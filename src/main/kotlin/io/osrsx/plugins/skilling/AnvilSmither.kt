package io.osrsx.plugins.skilling

import io.osrsx.api.ItemRef
import io.osrsx.api.PluginContext
import io.osrsx.api.RestockSpec
import io.osrsx.api.section
import io.osrsx.plugin.Plugin

/**
 * The routine for ordinary anvil smithing: web-walk to the chosen catalogued [AnvilSite], bank for bars,
 * click an anvil, drive the smithing interface (group 312 — quantity "All", then the product's slot) and
 * let the batch run; re-bank when the bars run out. Stops itself when the bank runs dry of the chosen bar.
 *
 * The site is chosen explicitly from [AnvilSites] (by the player or "Auto — select best"), and an anvil is
 * only clicked once we're AT that site, so the bot can't drift to a stray anvil it passes en route. Every
 * logical step is timed under a `smither/…` span via the plugin [Profiler].
 */
class AnvilSmither(
    private val ctx: PluginContext,
    private val site: () -> AnvilSite?,
    private val bar: () -> BarType,
    private val product: () -> SmithProduct?,
    private val buyBars: () -> Boolean,
    private val buyBatch: () -> Int,
    private val gearUp: () -> Long?,
    private val stats: SmitherStats,
    lockInput: () -> Boolean,
    stopReason: () -> String?,
) {
    private val loop = SmitherLoop(ctx, lockInput, stopReason) { step() }

    /** Two-phase interface latch: the quantity button is clicked first, the product slot next loop. */
    private var qtyClicked = false

    /** Set when the bank ran dry and a GE restock is underway — drives [restock] until the loadout holds. */
    private var restocking = false

    fun tick(): Long = loop.tick()
    fun releaseInput() = loop.releaseInput()

    private fun step(): Long {
        stats.status = "gearing up"
        gearUp()?.let { return it }
        val product = product() ?: run { stats.status = "no item chosen"; return snap(1200, 2500) }

        if (smithingOpen()) return driveInterface(product)
        qtyClicked = false

        // Debounced idle: the anvil animation dips between hammer swings, so only a sustained idle means
        // the batch finished (avoids re-clicking the anvil mid-batch, which would reopen the interface).
        if (loop.stillWorking()) { stats.status = "smithing"; return snap(400, 1100) }

        // A GE restock is underway — drive the loadout (it handles the GE trip itself) BEFORE the travel
        // branch, which would otherwise fight the loadout's own walking (the bank-vs-site oscillation class).
        if (restocking) return restock(product)

        // Travel FIRST, while the inventory is light: the web walker may need a bank detour for teleport
        // runes en route, and a pre-withdrawn bar load leaves it no free slots for them. Bars are only
        // withdrawn once we're AT the site — every catalogued site has a bank right beside the anvils.
        // SITE_RADIUS is deliberately wide enough to contain the whole bank↔anvil area: inside it we never
        // web-walk to the anchor, so heading to the site's bank can't be yanked back mid-walk (the old
        // tight radius made banking and site-return fight each other and oscillate).
        val target = site() ?: run { stats.status = "no location"; return snap(1200, 2500) }
        val me = ctx.players().localPlayer()?.tile()
        if (me == null || me.distanceTo(target.tile) > SITE_RADIUS) {
            stats.status = "walking"
            ctx.webWalking().walkTo(target.tile)
            return snap(300, 1100)
        }

        return if (ctx.inventory().count(bar().barName) >= product.bars) smith() else bankBars(product)
    }

    /** The smithing interface is up → pick quantity "All", then click the product's item slot. */
    private fun driveInterface(product: SmithProduct): Long = ctx.profiler().section("smither/interface") {
        stats.status = "smithing"
        if (!qtyClicked) {
            ctx.widgets().interact(SMITHING_GROUP, MAKE_ALL)
            qtyClicked = true
            return@section snap(300, 800)
        }
        qtyClicked = false
        ctx.widgets().interact(SMITHING_GROUP, product.child)
        snap(1200, 2200) // the batch starts — the stillWorking debounce takes over from here
    }

    /** In the site area with bars in hand → click the nearest reachable anvil (opens the interface).
     *  A direct click from across the area walks-to-interact; if no anvil is in clickable range (e.g.
     *  we're at the bank edge of a larger site), close in on the anchor locally. */
    private fun smith(): Long = ctx.profiler().section("smither/anvil") {
        val anvil = ctx.objects().query().named("Anvil").withAction("Smith").nearest()
        if (anvil != null && anvil.distance() <= INTERACT_RANGE && loop.canReach(anvil)) {
            stats.status = "smithing"
            if (!anvil.leftClickIfDefault("Smith")) anvil.interact("Smith")
            return@section snap(600, 1400)
        }
        site()?.let { target ->
            stats.status = "walking"
            ctx.walking().walkStep(ctx.walking().reachableNear(target.tile))
            return@section snap(300, 1000)
        }
        stats.status = "waiting"
        snap(600, 2000)
    }

    /** Bank the finished batch and withdraw a fresh load of bars; stop for good when the bank is dry. */
    private fun bankBars(product: SmithProduct): Long = ctx.profiler().section("smither/bank") {
        stats.status = "banking"
        val bank = ctx.bank()
        if (!bank.isOpen()) {
            return@section if (bank.openNearest()) snap(400, 900) else snap(700, 1500)
        }
        val bar = bar()
        val made = ctx.inventory().count(product.itemName(bar))
        if (made > 0) {
            stats.addProduced(made)
            bank.depositAll(product.itemName(bar))
        }
        // Bank dry AND not enough bars in hand for even one make → restock at the GE, or stop cleanly.
        if (bank.count(bar.barName) == 0 && ctx.inventory().count(bar.barName) < product.bars) {
            bank.close()
            if (buyBars()) { restocking = true; return@section snap(300, 700) }
            io.osrsx.plugin.PluginLog("smither").i("out of ${bar.barName}s — stopping")
            return@section Plugin.NO_LOOP
        }
        bank.withdraw(bar.barName, 0) // 0 = All — fills the free slots (the hammer keeps its slot)
        bank.close()
        snap(400, 1000)
    }

    /** Drive one step of the GE bar restock: a loadout wanting a full inventory of bars, with a GE buy of
     *  [buyBatch] as its restock path. `apply` owns the whole trip (bank, GE, walking); once it reports
     *  satisfied we fall back into the normal loop (the travel branch walks us back to the site). */
    private fun restock(product: SmithProduct): Long = ctx.profiler().section("smither/restock") {
        stats.status = "buying bars"
        val bar = bar()
        val batch = buyBatch().coerceAtLeast(product.bars)
        val loadout = ctx.loadouts().build("Smither bars") {
            item(
                ItemRef.ByName(bar.barName),
                quantity = FULL_LOAD,
                minimum = product.bars,
                restock = RestockSpec(ItemRef.ByName(bar.barName), batch, markupPercent = 10),
            )
        }
        if (ctx.loadouts().apply(loadout)) restocking = false
        snap(400, 1000)
    }

    private fun smithingOpen(): Boolean = ctx.widgets().isVisible(SMITHING_GROUP, SMITHING_FRAME)

    private companion object {
        // net.runelite.api.gameval.InterfaceID.Smithing — the anvil "What would you like to make?" interface.
        const val SMITHING_GROUP = 312
        const val SMITHING_FRAME = 1
        const val MAKE_ALL = 7 // the "All" quantity preset (children 3..7 are 1/5/10/X/All)

        /** Distance to the site anchor at/under which we're "in the site area" (bank + anvils both inside)
         *  and stop web-walking — wide so a trip to the site's bank never re-triggers the travel branch. */
        const val SITE_RADIUS = 25

        /** Max tiles an anvil may be to attempt interaction — beyond this it's off-screen with no clickbox. */
        const val INTERACT_RANGE = 15

        /** Bars per bank load — a full inventory less the hammer's slot. */
        const val FULL_LOAD = 27
    }
}
