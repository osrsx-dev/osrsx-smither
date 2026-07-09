package io.osrsx.plugins.skilling

import io.osrsx.api.ItemRef
import io.osrsx.api.profile
import io.osrsx.config.PluginConfig
import io.osrsx.config.and
import io.osrsx.config.eq
import io.osrsx.config.isTrue
import io.osrsx.plugin.HasOverlay
import io.osrsx.plugin.PluginDescriptor
import io.osrsx.plugin.RoutinePlugin
import io.osrsx.plugin.ScriptGui
import io.osrsx.plugin.routine

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
class SmitherPlugin : RoutinePlugin(), HasOverlay {

    object Config : PluginConfig("smither") {
        private const val ANVIL = "Anvil"
        private const val BF = "Blast Furnace"

        // Memo for the live item-picker providers (queried per UI frame — see the `item` provider).
        private const val CHOICES_TTL_MS = 1000L
        @Volatile private var itemChoicesMetal: BarType? = null
        @Volatile private var itemChoicesAtMs = 0L
        @Volatile private var itemChoicesCache: List<Int> = emptyList()
        @Volatile private var bfChoicesAtMs = 0L
        @Volatile private var bfChoicesCache: List<Int> = emptyList()

        var mode by enumItem("mode", "Mode", ANVIL, listOf(ANVIL, BF),
            "Smith items at an anvil, or smelt bars at the Blast Furnace")

        // ---- Anvil ----
        // Item pickers (sprite + name), not plain enums: the Metal is the real bar item, and the Item list
        // is the real product items for that metal — filtered live to what the account can make.
        var bar by itemItem("bar", "Metal", BarType.BRONZE.barName,
            "Which bars to smith",
            choices = BarType.barNames, browse = true,
            visibleIf = eq("mode", ANVIL))

        var item by itemItem(
            "item", "Item",
            description = "What to smith — only items your Smithing level allows are shown",
            visibleIf = eq("mode", ANVIL),
        ) { ctx ->
            // The live provider is re-queried EVERY UI FRAME while the config panel is open, and the
            // eligibility scan reads skills/worlds (client-thread hops) — uncached it stutters the whole
            // client. Memoise on (metal, short TTL) so the panel costs at most one scan per second.
            val metal = BarType.fromBarName(bar)
            val now = System.currentTimeMillis()
            if (metal != itemChoicesMetal || now - itemChoicesAtMs > CHOICES_TTL_MS) {
                itemChoicesMetal = metal
                itemChoicesAtMs = now
                itemChoicesCache = SmithProduct.eligible(metal, ctx).mapNotNull { p ->
                    ctx.itemResolver().idOf(ItemRef.ByName(p.itemName(metal))).takeIf { it >= 0 }
                }
            }
            itemChoicesCache
        }

        var location by enumItem(
            "location", "Location",
            default = AnvilSites.BEST,
            description = "Where to smith — catalogued anvils with a bank close by",
            visibleIf = eq("mode", ANVIL),
        ) { ctx -> AnvilSites.optionsFor(ctx) }

        // ---- Blast Furnace ----
        var bfBar by itemItem(
            "bfBar", "Bar",
            default = BlastBar.STEEL.barName,
            description = "What to smelt — only bars your Smithing level allows are shown",
            visibleIf = eq("mode", BF),
        ) { ctx ->
            val now = System.currentTimeMillis()
            if (now - bfChoicesAtMs > CHOICES_TTL_MS) {
                bfChoicesAtMs = now
                bfChoicesCache = BlastBar.eligible(ctx).mapNotNull { b ->
                    ctx.itemResolver().idOf(ItemRef.ByName(b.barName)).takeIf { it >= 0 }
                }
            }
            bfChoicesCache
        }

        var buyBars by boolItem("buyBars", "Buy bars from GE", true,
            "When the bank runs out of bars, buy more at the Grand Exchange instead of stopping",
            visibleIf = eq("mode", ANVIL))

        var buyBatch by intItem("buyBatch", "GE buy batch", 100, 27, 5000,
            "How many bars to buy per Grand Exchange restock",
            visibleIf = eq("mode", ANVIL) and isTrue("buyBars"))

        var cofferTopUp by intItem("cofferTopUp", "Coffer deposit (gp)", 100_000, 10_000, 10_000_000,
            "How much to put in the coffer each time it runs low (the furnace dwarves draw from it)",
            visibleIf = eq("mode", BF))

        var buyOres by boolItem("buyOres", "Buy ore from Ordan", true,
            "When the bank runs out of ore, buy the next load from Ordan's ore store at the furnace " +
                "instead of stopping (he stocks everything except runite)",
            visibleIf = eq("mode", BF))

        var hopToBfWorld by boolItem("hopToBfWorld", "Hop to Blast Furnace world", true,
            "Before working, world-hop onto an official Blast Furnace world (the ones whose paid dwarves " +
                "run the furnace) — hops only from a clean slate so nothing in the furnace is left behind",
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

        var wearGraceful by boolItem("wearGraceful", "Wear Graceful outfit", true,
            "Equip your Graceful (agility) clothing while working, if you own it", section = "Setup")

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
            blastFurnace = { Config.isBlastFurnace },
            wantGraceful = { Config.wearGraceful },
        )
    }
    private val stops by lazy {
        StopTargets(stats,
            level = { Config.stopAtLevel }, count = { Config.stopAtCount }, minutes = { Config.stopAfterMins })
    }

    private fun currentBar(): BarType = BarType.fromBarName(Config.bar)

    /** The chosen product (the picker stores the real item name, e.g. "Steel platebody"), resolved live —
     *  a blank/stale value falls back to the first eligible line for the metal. */
    private fun currentProduct(): SmithProduct? {
        val bar = currentBar()
        return SmithProduct.entries.firstOrNull { it.itemName(bar) == Config.item }
            ?: SmithProduct.eligible(bar, ctx).firstOrNull()
    }

    private fun currentBlastBar(): BlastBar = BlastBar.fromBarName(Config.bfBar)

    private fun gearUp(): Long? = gear.ensure()

    private val anvil by lazy {
        AnvilSmither(
            ctx,
            site = { AnvilSites.siteFor(ctx, Config.location) },
            bar = { currentBar() },
            product = { currentProduct() },
            buyBars = { Config.buyBars },
            buyBatch = { Config.buyBatch },
            gearUp = { gearUp() },
            stats = stats,
        )
    }

    private val furnace by lazy {
        BlastFurnaceRoutine(
            ctx,
            bar = { currentBlastBar() },
            cofferTopUp = { Config.cofferTopUp },
            buyOres = { Config.buyOres },
            hopToBfWorld = { Config.hopToBfWorld },
            gearUp = { gearUp() },
            highlight = { Config.highlightObjects },
            stats = stats,
        )
    }

    /** The single "core" routine: the shared [smitherPrologue] guards (login/yield/stop/break/dialogue/idle)
     *  plus a per-tick camera-hold refresh for the Blast Furnace, then a subroutine per mode. A
     *  [RoutinePlugin] drives it — onStart/onStop/onLoop are wired for us; the routine's own onStart/onStop
     *  hooks carry stats init and input/camera release. */
    private val core by lazy {
        routine(ctx.profiler(), "smither", status = { stats.status = it }) {
            smitherPrologue(
                ctx,
                lockInput = { Config.lockInput },
                stopReason = { stops.reason() },
                beforeEachExtra = { if (Config.isBlastFurnace) furnace.holdCamera() },
            )
            onStart {
                stats.start()
                stats.carried = {
                    if (Config.isBlastFurnace) inventory.count(currentBlastBar().barName)
                    else currentProduct()?.let { inventory.count(it.itemName(currentBar())) } ?: 0
                }
                refreshBarPrice()
            }
            onStop {
                anvil.releaseInput()
                furnace.releaseInput()
            }
            subroutine("blast furnace", { Config.isBlastFurnace }, furnace.routine)
            subroutine("anvil", { true }, anvil.routine)
        }
    }

    /** The chosen bar's live GE guide price, fetched ONCE at start and on a target change — not per overlay
     *  frame. 0 until the prices cache has loaded; [barPriceOrRefresh] tops it up while it's still 0. */
    @Volatile private var barPrice: Int = 0
    private fun refreshBarPrice() { barPrice = ctx.prices().price(currentBlastBar().barName) }
    /** The cached price, re-fetching only while it hasn't landed yet (prices load in the background). */
    private fun barPriceOrRefresh(): Int {
        if (barPrice == 0) refreshBarPrice()
        return barPrice
    }

    override fun routine() = core

    /** Reset the item to "first eligible" when the metal changes — a label for the old metal is invalid.
     *  Re-price when the Blast Furnace bar (or mode) changes so GP/hr tracks the new target. */
    override fun onConfigChanged(key: String) {
        if (key == "bar") Config.item = ""
        if (key == "bfBar" || key == "mode") refreshBarPrice()
    }

    override fun overlayTitle() = "Smithing"

    override fun onOverlay(gui: ScriptGui) = profile("smither/overlay") {
        val rows = if (Config.isBlastFurnace) {
            val price = barPriceOrRefresh() // cached at start / on target change, not looked up per frame
            listOf(
                "Target" to "${currentBlastBar().display} bars — Blast Furnace",
                "Bars banked" to SmitherOverlay.commas(stats.output()),
                "GP made" to SmitherOverlay.compact(stats.gpValue(price)),
                "GP/hr" to SmitherOverlay.compact(stats.gpPerHour(price)),
                "Coffer" to SmitherOverlay.elapsed(furnace.cofferSecondsLeft() * 1000L),
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
