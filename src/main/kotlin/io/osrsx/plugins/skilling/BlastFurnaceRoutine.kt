package io.osrsx.plugins.skilling

import io.osrsx.api.HIGHLIGHT_FOREVER
import io.osrsx.api.Highlight
import io.osrsx.api.HighlightStyle
import io.osrsx.api.PluginContext
import io.osrsx.api.SceneEntity
import io.osrsx.api.Skill
import io.osrsx.api.Tile
import io.osrsx.api.section
import io.osrsx.plugin.Plugin
import io.osrsx.plugin.PluginLog
import java.awt.Color

/**
 * The Blast Furnace routine — the full smelting cycle the anvil loop can't model:
 *
 *  1. **Fund the coffer** whenever it runs low (the dwarves running the furnace draw from it) — withdraw
 *     coins, "Use" the coffer, pick Deposit and type the amount into the chatbox prompt (the [Keyboard]
 *     surface). Stops for good if the bank runs out of coins.
 *  2. **Pay the foreman** every <10 minutes while Smithing is under 60 (his 2,500 gp fee).
 *  3. **Feed the conveyor** with a banked load of ore. Coal-bearing bars run coal loads first until the
 *     furnace's coal buffer covers a full primary load (varbit-tracked), then the primary ore.
 *  4. **Collect** from the bar dispenser once the ore has smelted — Ice gloves are worn when owned (see
 *     [SmithGear]); otherwise a bucket of water is carried to cool each batch.
 *  5. **Bank the bars** and repeat.
 *
 * The furnace's internal stock (bars ready, ore/coal still buffered, coffer gp) is read from the same
 * `VarbitID.BLAST_FURNACE_*` varbits RuneLite's own plugin uses, so every decision is driven by real
 * machine state, not timers. Interaction latches follow the Motherlode pattern: a click is only re-issued
 * once the world-state change it should cause (ore leaving the inventory, bars arriving, the dialogue
 * opening) has failed to appear within a retry window. Every step is timed under a `smither/bf-*` span.
 */
class BlastFurnaceRoutine(
    private val ctx: PluginContext,
    private val bar: () -> BlastBar,
    private val cofferTopUp: () -> Int,
    private val buyOres: () -> Boolean,
    private val gearUp: () -> Long?,
    private val highlight: () -> Boolean,
    private val stats: SmitherStats,
    lockInput: () -> Boolean,
    stopReason: () -> String?,
) {
    private val loop = SmitherLoop(ctx, lockInput, stopReason) { step() }
    private val log = PluginLog("smither")

    /** When we last paid the foreman (0 = never this run) — repaid a little before the 10 min lapses. */
    private var lastPaidMs = 0L
    /** Latched while the coffer Deposit flow is mid-dialogue, so we don't re-click the coffer. */
    private var cofferClickedAt = 0L
    /** Latched when a conveyor click LANDED; cleared when the held ore actually drops. */
    private var feeding = false
    private var oreAtFeed = 0
    private var feedClickedAt = 0L
    /** Latched when a dispenser Take landed; cleared when bars reach the inventory. */
    private var taking = false
    private var takeClickedAt = 0L
    /** The ore currently being bought from Ordan's store (null = not buying); cleared on a full load. */
    private var buyingOre: String? = null
    private var tradeClickedAt = 0L

    /** The single live target highlight — re-pointed as the current object changes (so they don't stack). */
    private var targetHl: Highlight? = null
    private var targetHlKey: String? = null

    fun tick(): Long = loop.tick()
    fun releaseInput() { loop.releaseInput(); clearMark() }

    private fun step(): Long {
        stats.status = "gearing up"
        gearUp()?.let { return it }

        // Not at the furnace yet → web-walk in (the walker knows the Keldagrim routes).
        val me = ctx.players().localPlayer()?.tile()
        if (me == null || me.distanceTo(ANCHOR) > BF_RADIUS) {
            stats.status = "walking"
            ctx.webWalking().walkTo(ANCHOR)
            return snap(300, 1100)
        }

        val bar = bar()
        val held = ctx.inventory().count(bar.barName)
        val coffer = ctx.varps().varbit(COFFER_VARBIT)
        val barsReady = ctx.varps().varbit(bar.barVarbit)
        val oreBuffered = ctx.varps().varbit(bar.oreVarbit)
        val coalBuffered = ctx.varps().varbit(COAL_VARBIT)

        return when {
            // The coffer funds the dwarves who run the furnace — nothing smelts on an empty one.
            coffer < LOW_COFFER -> fundCoffer()
            // Under 60 Smithing the foreman's 2,500 gp buys 10 minutes — re-pay just before it lapses.
            ctx.skills().real(Skill.SMITHING) in 1..59 &&
                System.currentTimeMillis() - lastPaidMs > FOREMAN_REPAY_MS -> payForeman()
            // Finished bars in hand → bank them (also our stats tally point).
            held > 0 -> bankBars(bar)
            // The bank ran dry and we're restocking from Ordan's ore store.
            buyingOre != null -> buyOre()
            // Ore in hand → feed the conveyor (one click deposits the whole inventory of ore).
            holdingOre(bar) -> feedConveyor(bar)
            // Everything fed and smelted → take the batch from the dispenser.
            barsReady > 0 && oreBuffered == 0 -> collect(bar)
            // Ore still washing through the furnace → give it a beat.
            oreBuffered > 0 -> { stats.status = "smelting"; snap(900, 1800) }
            // Nothing in flight → withdraw the next load (coal first while the buffer is short).
            else -> withdrawLoad(bar, coalBuffered)
        }
    }

    // ---- Steps ---------------------------------------------------------------------------------------

    /** Withdraw coins if needed, then Use the coffer → "Deposit" → type the amount into the prompt. */
    private fun fundCoffer(): Long = ctx.profiler().section("smither/bf-coffer") {
        stats.status = "filling coffer"
        val amount = cofferTopUp().coerceAtLeast(LOW_COFFER)
        if (ctx.inventory().count(COINS) < amount) return@section withdrawCoins(amount)
        closeBankIfOpen()?.let { return@section it }

        // Mid-dialogue → drive it: pick Deposit, then type the amount into the numeric prompt.
        if (ctx.dialogues().getOptions().any { it.contains("Deposit", ignoreCase = true) }) {
            ctx.dialogues().chooseOption("Deposit")
            // enterAmount waits (up to its timeout) for the prompt to open, then types + Enters.
            if (!ctx.keyboard().enterAmount(amount)) log.w("coffer amount prompt never opened")
            cofferClickedAt = 0L
            return@section snap(800, 1500)
        }
        // A click is in flight → wait for its dialogue before re-clicking.
        if (System.currentTimeMillis() - cofferClickedAt < ACTION_RETRY_MS) return@section snap(300, 800)

        val coffer = ctx.objects().query().named("Coffer").withAction("Use").nearest()
            ?: run { stats.status = "walking"; walkNear(ANCHOR); return@section snap(300, 1200) }
        mark(coffer, Color.ORANGE, "Fund coffer")
        approach(coffer, ANCHOR)?.let { return@section it }
        if (coffer.leftClickIfDefault("Use") || coffer.interact("Use")) {
            cofferClickedAt = System.currentTimeMillis()
        }
        snap(500, 1200)
    }

    /** Pay the foreman's 2,500 gp fee (Smithing < 60): "Pay" him, then confirm the fee prompt. */
    private fun payForeman(): Long = ctx.profiler().section("smither/bf-foreman") {
        stats.status = "paying foreman"
        if (ctx.inventory().count(COINS) < FOREMAN_FEE) return@section withdrawCoins(FOREMAN_FEE + cofferTopUp())
        closeBankIfOpen()?.let { return@section it }

        // The fee confirmation ("Pay 2,500 coins…?" Yes/No) — confirm() picks the affirmative.
        if (ctx.dialogues().getOptions().isNotEmpty()) {
            if (ctx.dialogues().confirm()) lastPaidMs = System.currentTimeMillis()
            return@section snap(700, 1400)
        }
        val foreman = ctx.npcs().closest(FOREMAN)
            ?: run { stats.status = "walking"; walkNear(ANCHOR); return@section snap(300, 1200) }
        foreman.interact("Pay")
        snap(800, 1600)
    }

    /** Deposit the finished bars (the stats tally point) — the bank chest is inside the furnace room. */
    private fun bankBars(bar: BlastBar): Long = ctx.profiler().section("smither/bf-bank") {
        stats.status = "banking"
        val bank = ctx.bank()
        if (!bank.isOpen()) {
            return@section if (bank.openNearest()) snap(400, 900) else snap(700, 1500)
        }
        val made = ctx.inventory().count(bar.barName)
        stats.addProduced(made)
        bank.depositAll(bar.barName)
        bank.close()
        snap(400, 1000)
    }

    /** One click on the conveyor deposits every ore in the inventory — latched on the ore actually leaving. */
    private fun feedConveyor(bar: BlastBar): Long = ctx.profiler().section("smither/bf-feed") {
        stats.status = "feeding conveyor"
        closeBankIfOpen()?.let { return@section it }
        closeShop()?.let { return@section it }
        val ore = oreHeld(bar)
        // A landed click is in flight: the avatar walks to the belt and unloads. Only re-click once the
        // retry window passes with the ore still in hand (the click missed / was eaten).
        if (feeding) {
            if (ore < oreAtFeed) { feeding = false; return@section snap(250, 700) } // unloading — done
            val moving = ctx.players().localPlayer()?.isMoving ?: false
            if (moving || System.currentTimeMillis() - feedClickedAt < ACTION_RETRY_MS) return@section snap(400, 900)
            feeding = false
        }
        val belt = ctx.objects().query().id(CONVEYOR).nearest()
            ?: run { stats.status = "walking"; walkNear(BELT_TILE); return@section snap(300, 1200) }
        mark(belt, Color.GREEN, "Feed")
        approach(belt, BELT_TILE)?.let { return@section it }
        oreAtFeed = ore
        if (belt.leftClickIfDefault("Put-ore-on") || belt.interact("Put-ore-on")) {
            feeding = true; feedClickedAt = System.currentTimeMillis()
        }
        snap(500, 1200)
    }

    /** Take the smelted batch from the dispenser (hot bars are handled by worn ice gloves / the bucket). */
    private fun collect(bar: BlastBar): Long = ctx.profiler().section("smither/bf-collect") {
        stats.status = "collecting bars"
        closeBankIfOpen()?.let { return@section it }
        // The Take opens the skill-multi "How many?" interface — collect the lot.
        if (ctx.dialogues().makeOpen()) {
            ctx.dialogues().makeQuantity(all = true)
            taking = false
            return@section snap(900, 1700)
        }
        if (taking) {
            if (ctx.inventory().count(bar.barName) > 0) { taking = false; return@section snap(250, 700) }
            val moving = ctx.players().localPlayer()?.isMoving ?: false
            if (moving || System.currentTimeMillis() - takeClickedAt < ACTION_RETRY_MS) return@section snap(400, 900)
            taking = false
        }
        val dispenser = ctx.objects().query().id(DISPENSER).nearest()
            ?: run { stats.status = "walking"; walkNear(DISPENSER_TILE); return@section snap(300, 1200) }
        mark(dispenser, Color.YELLOW, "Take")
        approach(dispenser, DISPENSER_TILE)?.let { return@section it }
        if (dispenser.leftClickIfDefault("Take") || dispenser.interact("Take")) {
            taking = true; takeClickedAt = System.currentTimeMillis()
        }
        snap(500, 1200)
    }

    /**
     * Withdraw the next conveyor load. Coal-bearing bars run coal until the furnace's buffer covers a
     * full primary load, then the primary ore (Bronze splits the load half copper / half tin). Also keeps
     * a bucket of water on hand when no ice gloves are worn, and stops for good when the bank runs dry.
     */
    private fun withdrawLoad(bar: BlastBar, coalBuffered: Int): Long = ctx.profiler().section("smither/bf-withdraw") {
        stats.status = "banking"
        val bank = ctx.bank()
        if (!bank.isOpen()) {
            return@section if (bank.openNearest()) snap(400, 900) else snap(700, 1500)
        }
        // Hot-bar cooling: no ice gloves worn → carry one bucket of water for the next dispenser batch.
        if (!wearingIceGloves() && ctx.inventory().count(BUCKET_OF_WATER) == 0) {
            bank.withdraw(BUCKET_OF_WATER, 1)
        }

        val loadSize = ctx.inventory().emptySlotCount()
        val wantCoal = bar.coalPerBar > 0 && coalBuffered < bar.coalPerBar * PRIMARY_LOAD
        val oreName = if (wantCoal) "Coal" else bar.primaryOre

        if (bank.count(oreName) == 0) {
            // Restock from Ordan's ore store at the furnace (he sells everything except runite): make sure
            // a purse is carried first — his prices climb as his stock depletes.
            if (buyOres() && oreName in ORDAN_STOCK) {
                if (ctx.inventory().count(COINS) < ORE_BUY_COINS) return@section withdrawCoins(ORE_BUY_COINS)
                bank.close()
                buyingOre = oreName
                return@section snap(300, 700)
            }
            log.i("out of $oreName — stopping")
            bank.close()
            return@section Plugin.NO_LOOP
        }
        if (!wantCoal && bar.secondaryOre != null) {
            // Bronze: the furnace pairs the ores 1:1 — split the load half and half.
            bank.withdraw(bar.primaryOre, loadSize / 2)
            bank.withdraw(bar.secondaryOre, 0)
        } else {
            bank.withdraw(oreName, 0) // 0 = All — fills the free slots
        }
        bank.close()
        snap(400, 1000)
    }

    /** Withdraw coins for the coffer/foreman ([atLeast] gp); stops for good when the bank has none left. */
    private fun withdrawCoins(atLeast: Int): Long {
        stats.status = "banking"
        val bank = ctx.bank()
        if (!bank.isOpen()) {
            return if (bank.openNearest()) snap(400, 900) else snap(700, 1500)
        }
        if (bank.count(COINS) == 0) {
            log.i("out of coins — stopping")
            bank.close()
            return Plugin.NO_LOOP
        }
        bank.withdraw(COINS, atLeast)
        bank.close()
        return snap(400, 1000)
    }

    /**
     * Buy the next conveyor load of [buyingOre] from Ordan's ore store: Trade him, then a single "Buy 50"
     * on the ore's shop slot (buys are unnoted, so it caps at the free inventory slots — one click fills
     * the load). Done once a load is held; stops for good if he's out of stock or the coins run dry.
     */
    private fun buyOre(): Long = ctx.profiler().section("smither/bf-buy-ore") {
        val ore = buyingOre ?: return@section snap(200, 400)
        stats.status = "buying ore"
        closeBankIfOpen()?.let { return@section it }

        val have = ctx.inventory().count(ore)
        if (have >= PRIMARY_LOAD || ctx.inventory().emptySlotCount() == 0) {
            buyingOre = null
            return@section closeShop() ?: snap(300, 700)
        }
        if (ctx.inventory().count(COINS) < 1_000) {
            log.i("not enough coins to buy $ore — stopping")
            closeShop()
            return@section Plugin.NO_LOOP
        }
        if (!shopOpen()) {
            // A Trade click is in flight → wait for the shop before re-clicking.
            if (System.currentTimeMillis() - tradeClickedAt < ACTION_RETRY_MS) return@section snap(300, 800)
            val ordan = ctx.npcs().closest(ORDAN)
                ?: run { stats.status = "walking"; walkNear(ANCHOR); return@section snap(300, 1200) }
            mark(ordan, Color.MAGENTA, "Trade")
            if (ordan.interact("Trade")) tradeClickedAt = System.currentTimeMillis()
            return@section snap(600, 1400)
        }
        val slot = ctx.widgets().find(ore, "Buy 50") ?: ctx.widgets().find(ore, "Buy")
        if (slot == null) {
            log.i("Ordan has no $ore in stock — stopping")
            closeShop()
            return@section Plugin.NO_LOOP
        }
        ctx.widgets().interact(slot, "Buy 50")
        snap(700, 1500)
    }

    private fun shopOpen(): Boolean = ctx.widgets().isVisible(SHOP_GROUP, SHOP_FRAME)

    /** If the shop window is still open, close it (Esc) and wait — clicks land "through" an open shop UI. */
    private fun closeShop(): Long? {
        if (!shopOpen()) return null
        ctx.keyboard().pressEscape()
        return snap(300, 700)
    }

    // ---- Helpers -------------------------------------------------------------------------------------

    private fun holdingOre(bar: BlastBar): Boolean = oreHeld(bar) > 0

    /** Ore in the inventory that belongs on the conveyor (primary + coal + bronze's second ore). */
    private fun oreHeld(bar: BlastBar): Int {
        val inv = ctx.inventory()
        var n = inv.count(bar.primaryOre) + inv.count("Coal")
        bar.secondaryOre?.let { n += inv.count(it) }
        return n
    }

    private fun wearingIceGloves(): Boolean = ctx.equipment().count(ICE_GLOVES) > 0

    /** If a bank window is still open, close it and wait — clicks land "through" an open bank UI. */
    private fun closeBankIfOpen(): Long? {
        val bank = ctx.bank()
        if (!bank.isOpen()) return null
        bank.close()
        return snap(300, 700)
    }

    /** Walk (LOCALLY) toward [anchor] until [obj] is close enough to click reliably; null once in range. */
    private fun approach(obj: SceneEntity, anchor: Tile): Long? {
        if (obj.distance() <= CLICK_RANGE) return null
        stats.status = "walking"
        walkNear(anchor)
        return snap(300, 900)
    }

    /** Walk (LOCALLY) toward [tile], resolved to the nearest WALKABLE tile — the fixed furnace objects
     *  (belt/dispenser/coffer) sit on unwalkable tiles, and a local step to an unwalkable dest just fails. */
    private fun walkNear(tile: Tile) { ctx.walking().walkStep(ctx.walking().reachableNear(tile)) }

    /** Outline [entity] as the CURRENT target, colour-coded per action, reusing one handle. */
    private fun mark(entity: SceneEntity?, color: Color, label: String) {
        if (!highlight() || entity == null) { clearMark(); return }
        val key = "${entity.tile()}|$label"
        if (key == targetHlKey && targetHl?.active == true) return
        targetHl?.remove()
        targetHl = ctx.highlights().highlight(entity, HighlightStyle(color = color, label = label), HIGHLIGHT_FOREVER)
        targetHlKey = key
    }

    private fun clearMark() { targetHl?.remove(); targetHl = null; targetHlKey = null }

    private companion object {
        /** Centre of the Blast Furnace room (under Keldagrim) — travel target and local-walk fallback. */
        val ANCHOR = Tile(1940, 4962, 0)
        val BELT_TILE = Tile(1943, 4967, 0)      // conveyor belt (west pair)
        val DISPENSER_TILE = Tile(1940, 4963, 0) // bar dispenser
        /** "At the furnace" radius — the whole room fits comfortably inside it. */
        const val BF_RADIUS = 30

        // net.runelite.api.gameval.ObjectID
        const val CONVEYOR = 9100  // BLAST_FURNACE_CONVEYER_BELT_CLICKABLE ("Put-ore-on")
        const val DISPENSER = 9092 // BLAST_FURNACE_DISPENSER ("Take")

        // net.runelite.api.gameval.VarbitID
        const val COFFER_VARBIT = 5357 // BLAST_FURNACE_COFFER — gp left for the furnace dwarves
        const val COAL_VARBIT = 949    // BLAST_FURNACE_COAL — coal buffered inside the furnace

        // net.runelite.api.gameval.ItemID
        const val ICE_GLOVES = 1580
        const val BUCKET_OF_WATER = 1929

        const val COINS = "Coins"
        const val FOREMAN = "Blast Furnace Foreman"
        const val ORDAN = "Ordan" // the ore seller inside the furnace room
        /** What Ordan's ore store stocks — everything the furnace takes except runite ore. */
        val ORDAN_STOCK = setOf(
            "Copper ore", "Tin ore", "Iron ore", "Silver ore", "Coal", "Gold ore", "Mithril ore", "Adamantite ore",
        )
        /** Purse carried before trading Ordan — his prices climb steeply as his stock depletes. */
        const val ORE_BUY_COINS = 100_000
        // net.runelite.api.gameval.InterfaceID.Shopmain
        const val SHOP_GROUP = 300
        const val SHOP_FRAME = 1
        const val FOREMAN_FEE = 2_500
        /** Re-pay a little before the foreman's 10 minutes lapse so smelting never blocks on him. */
        const val FOREMAN_REPAY_MS = 9 * 60_000L + 30_000L
        /** Top the coffer up under this — about six minutes of the dwarves' drain rate. */
        const val LOW_COFFER = 7_200
        /** Coal the furnace should hold before a primary-ore load goes on (a full load's worth per coal). */
        const val PRIMARY_LOAD = 26

        const val CLICK_RANGE = 6      // walk this close to a fixed object before clicking
        const val ACTION_RETRY_MS = 3500L
    }
}
