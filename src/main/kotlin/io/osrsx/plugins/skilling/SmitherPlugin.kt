package io.osrsx.plugins.skilling

import io.osrsx.api.profile
import io.osrsx.config.PluginConfig
import io.osrsx.config.eq
import io.osrsx.plugin.HasOverlay
import io.osrsx.plugin.Plugin
import io.osrsx.plugin.PluginDescriptor
import io.osrsx.plugin.ScriptGui

/**
 * Smithing plugin with two modes (mirroring the miner's normal-vs-Motherlode split):
 *
 *  - **Anvil** — pick a metal, an item and a catalogued location; [AnvilSmither] banks bars, hammers the
 *    batch out at the anvils and re-banks until the bank runs dry. The item dropdown lists only what the
 *    account's Smithing level (and world membership) allows — see [SmithProduct].
 *  - **Blast Furnace** — pick a bar; [BlastFurnaceRoutine] runs the whole furnace cycle: coffer funding,
 *    the under-60 foreman fee, coal/ore conveyor loads, dispenser collection (ice gloves worn when owned)
 *    and banking.
 *
 * Gears via the Loadout API ([SmithGear]) and shows a live stats overlay. Every loop is profiled under
 * `smither/…` spans (zero-overhead when profiling is off).
 */
@PluginDescriptor(
    name = "Smither",
    description = "Smiths items at an anvil, or smelts bars at the Blast Furnace.",
    author = "osrsx",
    tags = ["skilling", "smithing", "processing"],
)
class SmitherPlugin : Plugin(), HasOverlay {

    object Config : PluginConfig("smither") {
        private const val ANVIL = "Anvil"
        private const val BF = "Blast Furnace"

        var mode by enumItem("mode", "Mode", ANVIL, listOf(ANVIL, BF),
            "Smith items at an anvil, or smelt bars at the Blast Furnace")

        // ---- Anvil ----
        var bar by enumItem("bar", "Metal", BarType.BRONZE.display, BarType.displays,
            "Which bars to smith", visibleIf = eq("mode", ANVIL))

        var item by enumItem(
            "item", "Item",
            description = "What to smith — only items your Smithing level allows are shown",
            visibleIf = eq("mode", ANVIL),
        ) { ctx -> SmithProduct.optionsFor(BarType.fromDisplay(bar), ctx) }

        var location by enumItem(
            "location", "Location",
            default = AnvilSites.BEST,
            description = "Where to smith — catalogued anvils with a bank close by",
            visibleIf = eq("mode", ANVIL),
        ) { ctx -> AnvilSites.optionsFor(ctx) }

        // ---- Blast Furnace ----
        var bfBar by enumItem(
            "bfBar", "Bar",
            default = BlastBar.STEEL.display,
            description = "What to smelt — only bars your Smithing level allows are shown",
            visibleIf = eq("mode", BF),
        ) { ctx -> BlastBar.optionsFor(ctx) }

        var cofferTopUp by intItem("cofferTopUp", "Coffer deposit (gp)", 100_000, 10_000, 10_000_000,
            "How much to put in the coffer each time it runs low (the furnace dwarves draw from it)",
            visibleIf = eq("mode", BF))

        var iceGloves by boolItem("iceGloves", "Wear ice gloves", true,
            "Equip Ice gloves from the bank if you own a pair (hot bars are taken bare-handed); " +
                "otherwise a bucket of water is carried to cool each batch",
            visibleIf = eq("mode", BF))

        // ---- Setup ----
        var getHammer by boolItem("getHammer", "Get hammer", true,
            "Before smithing, make sure a hammer is carried — withdrawn from the bank, or bought if you own none",
            section = "Setup", visibleIf = eq("mode", ANVIL))

        var highlightObjects by boolItem("highlightObjects", "Highlight targets", true,
            "Outline the object the bot is currently acting on (conveyor, dispenser, coffer)",
            section = "Setup", visibleIf = eq("mode", BF))

        var lockInput by boolItem("lockInput", "Lock user input", false,
            "While running, ignore physical mouse/keyboard input so it can't disrupt the bot", section = "Antiban")

        var stopAtLevel by intItem("stopAtLevel", "Stop at level", 0, 0, 99,
            "Stop when Smithing hits this level (0 = never)", "Stopping")
        var stopAtCount by intItem("stopAtCount", "Stop at items", 0, 0, 1_000_000,
            "Stop after this many items/bars made (0 = never)", "Stopping")
        var stopAfterMins by intItem("stopAfterMins", "Stop after (min)", 0, 0, 100_000,
            "Stop after this many minutes (0 = never)", "Stopping")

        val isBlastFurnace: Boolean get() = mode == BF
    }

    override fun config() = Config

    private val stats by lazy { SmitherStats(ctx) }
    private val gear by lazy {
        SmithGear(
            ctx,
            wantHammer = { !Config.isBlastFurnace && Config.getHammer },
            wantIceGloves = { Config.isBlastFurnace && Config.iceGloves },
        )
    }
    private val stops by lazy {
        StopTargets(stats,
            level = { Config.stopAtLevel }, count = { Config.stopAtCount }, minutes = { Config.stopAfterMins })
    }

    private fun currentBar(): BarType = BarType.fromDisplay(Config.bar)

    /** The chosen product, resolved live — a blank/stale value falls back to the first eligible line. */
    private fun currentProduct(): SmithProduct? =
        SmithProduct.fromDisplay(Config.item) ?: SmithProduct.eligible(currentBar(), ctx).firstOrNull()

    private fun currentBlastBar(): BlastBar = BlastBar.fromDisplay(Config.bfBar)

    private fun gearUp(): Long? = gear.ensure()

    private val anvil by lazy {
        AnvilSmither(
            ctx,
            site = { AnvilSites.siteFor(ctx, Config.location) },
            bar = { currentBar() },
            product = { currentProduct() },
            gearUp = { gearUp() },
            stats = stats,
            lockInput = { Config.lockInput },
            stopReason = { stops.reason() },
        )
    }

    private val furnace by lazy {
        BlastFurnaceRoutine(
            ctx,
            bar = { currentBlastBar() },
            cofferTopUp = { Config.cofferTopUp },
            gearUp = { gearUp() },
            highlight = { Config.highlightObjects },
            stats = stats,
            lockInput = { Config.lockInput },
            stopReason = { stops.reason() },
        )
    }

    override fun onStart() {
        stats.start()
        stats.carried = {
            if (Config.isBlastFurnace) inventory.count(currentBlastBar().barName)
            else currentProduct()?.let { inventory.count(it.itemName(currentBar())) } ?: 0
        }
    }

    /** Reset the item to "first eligible" when the metal changes — a label for the old metal is invalid. */
    override fun onConfigChanged(key: String) {
        if (key == "bar") Config.item = ""
    }

    override fun onStop() {
        anvil.releaseInput()
        furnace.releaseInput()
    }

    override fun onLoop(): Long =
        if (Config.isBlastFurnace) furnace.tick() else anvil.tick()

    override fun overlayTitle() = "Smithing"

    override fun onOverlay(gui: ScriptGui) = profile("smither/overlay") {
        val rows = if (Config.isBlastFurnace) {
            listOf(
                "Target" to "${currentBlastBar().display} bars — Blast Furnace",
                "Bars banked" to SmitherOverlay.commas(stats.output()),
            )
        } else {
            val bar = currentBar()
            val product = currentProduct()
            listOf(
                "Target" to "${bar.display} ${product?.display ?: "?"} — ${Config.location}",
                "Made" to SmitherOverlay.commas(stats.produced()),
            )
        }
        SmitherOverlay.render(gui, stats, rows)
    }
}
