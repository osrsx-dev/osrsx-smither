package io.osrsx.plugins.skilling

import io.osrsx.api.BankingService
import io.osrsx.api.HIGHLIGHT_FOREVER
import io.osrsx.api.Highlight
import io.osrsx.api.HighlightStyle
import io.osrsx.api.ItemRef
import io.osrsx.api.PluginContext
import io.osrsx.api.SceneEntity
import io.osrsx.api.Skill
import io.osrsx.api.Tile
import io.osrsx.api.get
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
    private val hopToBfWorld: () -> Boolean,
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
    /** When the last load actually left the inventory onto the belt — a grace so we wait for the furnace's
     *  varbits to register the fed ore (they lag a tick) instead of banking prematurely and backtracking. */
    private var lastFedMs = 0L
    /** Latched when a dispenser Take landed; cleared when bars reach the inventory. */
    private var taking = false
    private var takeClickedAt = 0L
    /** Set the moment the TARGET ORE (not coal) leaves the inventory onto the belt — the very next step must
     *  then be the bar dispenser, not the bank (the furnace varbits lag a tick after the feed, which used to
     *  send us to bank a fresh load and backtrack). Cleared once we've collected the bars. */
    private var awaitingCollect = false
    /** Whether the load currently being fed includes the primary ore (vs a coal-only load). */
    private var fedPrimaryOre = false
    /** The ore currently being bought from Ordan's store (null = not buying); cleared on a full load. */
    private var buyingOre: String? = null
    private var tradeClickedAt = 0L

    /** Latched when a bank-chest "Use" click LANDED; cleared once the bank is actually open. */
    private var bankOpening = false
    private var bankClickedAt = 0L
    /** When the bank first reported open — so counts wait for its items to populate before being trusted. */
    private var bankOpenedAt = 0L

    /** Coal-bag state: whether the account owns one (decided once the bank is seen), and whether the bag
     *  currently holds a load waiting to be emptied onto the belt (its second coal load). The bag ~doubles
     *  coal per bank trip. Emptied only AT THE BELT after the loose coal is fed, never at the bank. */
    private var coalBagChecked = false
    private var ownsCoalBag = false
    private var coalBagFilled = false

    /** Set once we've tilted the camera overhead for the furnace — done a single time, then held frozen. */
    private var cameraFramed = false

    /** The single live target highlight — re-pointed as the current object changes (so they don't stack). */
    private var targetHl: Highlight? = null
    private var targetHlKey: String? = null

    fun tick(): Long = loop.tick()
    fun releaseInput() {
        loop.releaseInput()
        clearMark()
        ctx.cameraControl().release() // let the humanizers resume once we stop
        cameraFramed = false
    }

    private fun step(): Long {
        // Freeze the background camera AND idle-mouse humanizers for the whole run: the furnace is a cramped
        // room and an auto-rotate/zoom/fidget mid-click loses the belt (the "viewport too short" misses).
        // hold() blocks every CameraAction, which CameraDirector reports as suspended() — the one signal both
        // the CameraHumanizer and the IdleMouseHumanizer stand down on. Refreshed each loop (5s lease TTL).
        ctx.cameraControl().hold()

        stats.status = "gearing up"
        gearUp()?.let { return it }

        val bar = bar()
        val held = ctx.inventory().count(bar.barName)
        val coffer = ctx.varps().varbit(COFFER_VARBIT)
        val barsReady = ctx.varps().varbit(bar.barVarbit)
        val oreBuffered = ctx.varps().varbit(bar.oreVarbit)
        val coalBuffered = ctx.varps().varbit(COAL_VARBIT)

        // Get onto an official Blast Furnace world before touching the furnace — only those run the paid
        // dwarves the coffer funds. A hop is PER-WORLD (coffer gp, buffered ore and finished bars stay on
        // the world we leave), so only hop from a clean slate; otherwise fall through and let the normal
        // steps drain what's in the furnace first, then hop on a later tick.
        if (hopToBfWorld() && !onBlastFurnaceWorld()) {
            val clean = held == 0 && !holdingOre(bar) && barsReady == 0 && oreBuffered == 0 && !coalBagFilled
            if (clean) return hopToBlastFurnace()
        }

        // Not at the furnace yet → web-walk in (the walker knows the Keldagrim routes).
        val me = ctx.players().localPlayer()?.tile()
        if (me == null || me.distanceTo(ANCHOR) > BF_RADIUS) {
            stats.status = "walking"
            ctx.webWalking().walkTo(ANCHOR)
            return snap(300, 1100)
        }

        // Once we're in the room, set a fixed overhead + fully-zoomed-out view so every furnace object
        // (belt/dispenser/coffer) stays on screen and clickable; the hold() above then keeps it there.
        frameCamera()

        val cofferLow = cofferRunningLow(coffer)
        cofferSecsLeft = cofferSecondsLeftFor(coffer) // for the overlay
        return when {
            // DRAIN whatever we're carrying FIRST so the inventory has a free slot before any coin/coffer
            // withdraw — funding the coffer while holding a full ore load silently failed and looped (the
            // "stuck withdrawing gold with a full inventory" bug).
            // Finished bars in hand → bank them (also our stats tally point).
            held > 0 -> bankBars(bar)
            // The bank ran dry and we're restocking from Ordan's ore store — finish that before feeding.
            buyingOre != null -> buyOre()
            // Ore in hand → feed the conveyor (one click deposits the whole inventory of ore).
            holdingOre(bar) -> feedConveyor(bar)
            // Loose coal fed, but the coal bag still holds a load → tip it into the inventory AT THE BELT, then
            // it feeds on the next tick (the bag's whole point: a second coal load with no extra bank trip).
            coalBagFilled -> emptyCoalBag()
            // Just fed the target ore → go STRAIGHT to the bar dispenser (walk-to-interact lands as "Take"
            // opens); never bank a fresh load in the varbit-lag gap and backtrack.
            awaitingCollect -> collect(bar)
            // Inventory is clear now → keep the coffer funded (just-in-time by estimated drain, not 5 min
            // early) and pay the foreman. The dwarves stop smelting on an empty coffer.
            cofferLow -> fundCoffer()
            // Under 60 Smithing the foreman's 2,500 gp buys 10 minutes — re-pay just before it lapses.
            ctx.skills().real(Skill.SMITHING) in 1..59 &&
                System.currentTimeMillis() - lastPaidMs > FOREMAN_REPAY_MS -> payForeman()
            // Everything fed and smelted → take the batch from the dispenser.
            barsReady > 0 && oreBuffered == 0 -> collect(bar)
            // Ore still washing through the furnace → give it a beat.
            oreBuffered > 0 -> { stats.status = "smelting"; snap(900, 1800) }
            // Just fed a load but the furnace varbits haven't caught up yet (they lag a tick) → wait for them
            // before concluding the furnace is idle and banking a fresh load (that premature trip = the backtrack).
            System.currentTimeMillis() - lastFedMs < FURNACE_SETTLE_MS -> { stats.status = "smelting"; snap(500, 1000) }
            // Nothing in flight → withdraw the next load (coal first while the buffer is short).
            else -> withdrawLoad(bar, coalBuffered)
        }
    }

    /** Whether to top the coffer up now — by TIME LEFT, not a flat gp threshold that fired ~5 min early. The
     *  dwarves drain a FIXED 72,000 gp/hour (12 gp/tick, OSRS wiki), so refuel once under [COFFER_LEAD_SEC]
     *  of smelting remains. */
    private fun cofferRunningLow(coffer: Int): Boolean = cofferSecondsLeftFor(coffer) < COFFER_LEAD_SEC

    /** Seconds of smelting the coffer has left at the fixed drain: `gp ÷ (72000/3600) = gp ÷ 20`. */
    private fun cofferSecondsLeftFor(coffer: Int): Int = coffer * 3600 / COFFER_DRAIN_PER_HOUR
    /** Cached each loop from the coffer varbit so the overlay reads it without a per-frame client-thread hop. */
    @Volatile private var cofferSecsLeft = 0
    fun cofferSecondsLeft(): Int = cofferSecsLeft

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
            ?: run { stats.status = "walking"; ctx.webWalking().walkTo(ANCHOR); return@section snap(300, 1200) }
        mark(coffer, Color.ORANGE, "Fund coffer")
        if (coffer.leftClickIfDefault("Use") || coffer.interactPrecise("Use")) {
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
            ?: run { stats.status = "walking"; ctx.webWalking().walkTo(ANCHOR); return@section snap(300, 1200) }
        foreman.interact("Pay") // NPC click walks-to-interact directly
        snap(800, 1600)
    }

    /** Deposit the finished bars AND any travel junk (the combat bracelet the web-walker teleported here on,
     *  stray drops) in one pass — keeping only the run's tools — via the shared [BankingService]. */
    private fun bankBars(bar: BlastBar): Long = ctx.profiler().section("smither/bf-bank") {
        stats.status = "banking"
        val banking = ctx.services().get<BankingService>() ?: return@section snap(700, 1600)
        ensureBankOpen()?.let { return@section it }
        awaitingCollect = false // bars in hand → the collect cycle is done
        stats.addProduced(ctx.inventory().count(bar.barName))
        // Menu injection (the miner's pattern): make "Deposit-All" the left-click default for the duration of
        // the deposit, so bars + junk each drop with one natural left-click instead of a right-click-select;
        // then clear the rule immediately so the swap never leaks. Keep only the coal bag, bucket and coins.
        val rule = ctx.menu().setDefault("Deposit-All")
        try {
            banking.depositAllExcept(*KEEP_REFS)
        } finally {
            ctx.menu().remove(rule)
        }
        banking.close()
        snap(400, 1000)
    }

    /** Fire [action] (Fill/Empty) on the coal bag with one natural LEFT-CLICK by menu-injecting it as the
     *  default (arm the swap, let it settle onto the live menu, then click while held). A FULL bag has no
     *  "Fill" row so the swap finds nothing and the left-click banks it — the caller re-checks the bag after
     *  a Fill and re-withdraws it (it's then simply loaded), so that only ever costs one recoverable trip. */
    private fun coalBagAction(action: String) {
        val rule = ctx.menu().setDefault(action, COAL_BAG)
        try {
            Thread.sleep(SWAP_SETTLE_MS) // let the injected default land on the live menu before the left-click
            ctx.inventory().interact(COAL_BAG)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            ctx.menu().remove(rule)
        }
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
            if (ore < oreAtFeed) { // unloaded — done
                feeding = false; lastFedMs = System.currentTimeMillis()
                if (fedPrimaryOre) awaitingCollect = true // fed the target ore → the dispenser is next, not the bank
                return@section snap(250, 700)
            }
            val moving = ctx.players().localPlayer()?.isMoving ?: false
            if (moving || System.currentTimeMillis() - feedClickedAt < ACTION_RETRY_MS) return@section snap(400, 900)
            feeding = false
        }
        // Match the belt by its "Put-ore-on" ACTION (id-agnostic). Click it DIRECTLY — it's pathable, so the
        // game walks-to-interact; no intermediate walk-to tile (that oscillation was the "stuck at belt" bug).
        val belt = ctx.objects().query().withAction("Put-ore-on").nearest()
            ?: run {
                // The belt's action briefly disappears right after a deposit (it's accepting the last load). We
                // are already at it, so WAIT for the action to return before the second coal load — DON'T walk
                // off to the anchor and back (the backtracking). Only web-walk if we truly left the room.
                val me = ctx.players().localPlayer()?.tile()
                if (me != null && me.distanceTo(ANCHOR) <= BF_RADIUS) { stats.status = "belt busy"; return@section snap(400, 800) }
                stats.status = "walking"; ctx.webWalking().walkTo(ANCHOR); return@section snap(300, 1200)
            }
        mark(belt, Color.GREEN, "Feed")
        oreAtFeed = ore
        fedPrimaryOre = ctx.inventory().count(bar.primaryOre) > 0 // this load is the target ore, not just coal
        if (belt.leftClickIfDefault("Put-ore-on") || belt.interactPrecise("Put-ore-on")) {
            feeding = true; feedClickedAt = System.currentTimeMillis()
            // Latch "dispenser is next" the MOMENT the selected-ore click lands — don't wait to detect the ore
            // leaving. The feeding latch can time out right as the ore deposits (isMoving flickers false past the
            // retry window), skipping the unload branch above; without this the furnace varbits lag and step()
            // falls through to withdrawLoad → a needless bank trip instead of the dispenser. holdingOre still
            // outranks awaitingCollect in step(), so if the click actually missed we simply re-feed.
            if (fedPrimaryOre) awaitingCollect = true
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
        // Click "Take" DIRECTLY — don't wait for the action to appear. The dispenser shows "Check" for the
        // instant right after the ore's fed, but the walk-to-interact takes just long enough that "Take" has
        // opened by the time we arrive; interactPrecise walks us over. The taking latch retries if we're a
        // hair early. (Matched by name so we still find it while it reads "Check".)
        val dispenser = ctx.objects().query().named("Bar dispenser").nearest()
            ?: run { stats.status = "walking"; ctx.webWalking().walkTo(ANCHOR); return@section snap(300, 1200) }
        mark(dispenser, Color.YELLOW, "Take")
        // Plain LEFT-CLICK the dispenser on its default action (it reads "Check" for the instant after the
        // ore's fed, "Take" once bars form). We DON'T target "Take" — it isn't in the menu yet when we click,
        // which left interactPrecise stuck; the walk-to-interact lands right as it flips to "Take".
        if (dispenser.interact()) {
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
        ensureBankOpen()?.let { return@section it }
        // First look at the bank tells us whether a coal bag is available to use this run.
        if (!coalBagChecked) {
            ownsCoalBag = ctx.bank().count(COAL_BAG) > 0 || ctx.inventory().count(COAL_BAG) > 0
            coalBagChecked = true
            if (ownsCoalBag) log.i("using coal bag")
        }
        // Hot-bar cooling: no ice gloves worn → carry one bucket of water for the next dispenser batch.
        if (!wearingIceGloves() && ctx.inventory().count(BUCKET_OF_WATER) == 0) {
            bank.withdraw(BUCKET_OF_WATER, 1)
        }

        val wantCoal = bar.coalPerBar > 0 && coalBuffered < bar.coalPerBar * PRIMARY_LOAD
        val oreName = if (wantCoal) "Coal" else bar.primaryOre

        if (ctx.bank().count(oreName) == 0) {
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

        // Coal bag: in ONE bank visit Fill the bag from the bank AND Withdraw-All the loose coal, so we leave
        // HOLDING a loose load with the bag also full. Doing both in the same visit is what keeps the Empty OUT
        // of the bank: we leave holding loose coal, so step()'s `holdingOre -> feedConveyor` outranks
        // `coalBagFilled -> emptyCoalBag` and the belt feeds the loose coal FIRST; only once that's gone does
        // [emptyCoalBag] tip the bag's second load in AT THE BELT. (Filling then returning before the
        // withdraw-all left us holding nothing, so emptyCoalBag fired at the bank and dumped the fresh coal.)
        if (wantCoal && ownsCoalBag) {
            // The bag must be in the inventory before it can be filled — withdraw it on its own tick first.
            if (ctx.inventory().count(COAL_BAG) == 0) { bank.withdraw(COAL_BAG, 1); return@section snap(300, 700) }
            coalBagAction("Fill")             // menu-inject Fill → the bag loads up from the bank
            withdrawAllInjected(bank, "Coal") // then one left-click Withdraw-All fills the remaining free slots
            coalBagFilled = true              // hold loose coal now, so step() feeds it BEFORE emptying the bag
            bank.close()
            return@section snap(400, 1000)
        }

        val loadSize = ctx.inventory().emptySlotCount()
        if (!wantCoal && bar.secondaryOre != null) {
            // Bronze: the furnace pairs the ores 1:1 — split the load half and half (specific amounts).
            bank.withdraw(bar.primaryOre, loadSize / 2)
            bank.withdraw(bar.secondaryOre, 0)
        } else {
            withdrawAllInjected(bank, oreName) // menu-inject "Withdraw-All" → one left-click fills the free slots (27)
        }
        bank.close()
        snap(400, 1000)
    }

    /** Withdraw the whole stack of [name] into the free slots with a single LEFT-CLICK by menu-injecting
     *  "Withdraw-All" as the item's default for just this click, then removing the rule. Falls back to a plain
     *  Withdraw-All if the item can't be brought on-screen. */
    private fun withdrawAllInjected(bank: io.osrsx.api.Bank, name: String) {
        val id = bank.getItem(name)?.id ?: run { bank.withdraw(name, 0); return }
        val rule = ctx.menu().setDefault("Withdraw-All", name)
        try {
            if (bank.bringItemIntoView(id)) ctx.widgets().interact(bank.itemWidget(id)) else bank.withdraw(name, 0)
        } finally {
            ctx.menu().remove(rule)
        }
    }

    /** Withdraw coins for the coffer/foreman ([atLeast] gp); stops for good when the bank has none left. */
    private fun withdrawCoins(atLeast: Int): Long {
        stats.status = "banking"
        val bank = ctx.bank()
        ensureBankOpen()?.let { return it }
        if (ctx.bank().count(COINS) == 0) {
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
                ?: run { stats.status = "walking"; ctx.webWalking().walkTo(ANCHOR); return@section snap(300, 1200) }
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

    /** One-time camera framing for the furnace room: fully zoom OUT (negative notches) and tilt to the most
     *  overhead pitch this client allows, so the small room's objects never fall off-screen or behind a wall.
     *  [step] then calls hold() every loop so the humanizer leaves this framing frozen. */
    private fun frameCamera() {
        if (cameraFramed) return
        ctx.camera().zoom(-FULL_ZOOM_NOTCHES, 800) // negative = zoom OUT; wait a beat for the mouse lock
        ctx.camera().rotateToPitch(OVERHEAD_PITCH) // coerced to the client's max (most top-down)
        cameraFramed = true
    }

    /** After the first (loose) coal load is on the belt, tip the coal bag's stored load into the inventory so
     *  the next tick feeds it too — a second coal load per bank trip. NOT done at the bank (that dumped the
     *  coal we'd just withdrawn); only here, once the loose coal has already gone onto the belt. */
    private fun emptyCoalBag(): Long = ctx.profiler().section("smither/bf-coalbag") {
        stats.status = "emptying coal bag"
        closeBankIfOpen()?.let { return@section it }
        closeShop()?.let { return@section it }
        coalBagAction("Empty") // menu-inject Empty (left-click) — tips the bag's coal into the inventory
        coalBagFilled = false
        // The emptied coal lands a game tick AFTER the click, so the very next step() can read an empty-handed
        // inventory. Mark a feed-grace so step() waits it out on the "just fed, don't bank" settle branch
        // instead of falling through to withdrawLoad and opening the bank; once the coal registers, holdingOre
        // outranks that branch and feedConveyor puts it on the belt.
        lastFedMs = System.currentTimeMillis()
        snap(500, 900)
    }

    // ---- Blast Furnace world -------------------------------------------------------------------------

    /** Are we on a world officially listed for the Blast Furnace (the ones whose paid dwarves run it)? */
    private fun onBlastFurnaceWorld(): Boolean {
        val worlds = ctx.worlds()
        return BfWorlds.isOn(worlds.list(), worlds.current())
    }

    /** Hop to the least-populated official Blast Furnace world. Waits (never stops) while the world list is
     *  still loading; logs and stays put if none are listed, so a data hiccup can't strand the run. */
    private fun hopToBlastFurnace(): Long {
        stats.status = "finding Blast Furnace world"
        val worlds = ctx.worlds()
        val all = worlds.list()
        if (all.isEmpty()) return snap(600, 1200) // list not fetched yet
        val cur = worlds.current()
        val target = BfWorlds.pick(all, cur)
            ?: run { log.w("no Blast Furnace world listed — staying on world $cur"); return snap(2000, 4000) }
        stats.status = "hopping to world $target"
        log.i("hopping to Blast Furnace world $target")
        worlds.hop(target) // blocks until the hop lands or times out
        return snap(600, 1400)
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

    /** Ensure the furnace bank is open via a DIRECT, highlighted left-click on the chest ("Use" is its
     *  default) — the game walks-to-interact, exactly like the belt/dispenser/coffer steps. Returns null once
     *  open (proceed), else a wait while it opens; latched on the landed click. Only falls back to the
     *  web-walker if the chest isn't in the loaded scene. */
    private fun ensureBankOpen(): Long? {
        if (ctx.bank().isOpen()) {
            bankOpening = false
            // The bank reports open a tick before its items populate; a count read in that gap is a false 0
            // (the "out of ore/coins" bug). Wait for the container to fill — one cheap [items] read, NOT the
            // bank cache (which resolves every name via a client hop: ~11s/call on a big bank). Proceeds the
            // instant items appear; only a genuinely empty bank waits the whole window.
            if (bankOpenedAt == 0L) bankOpenedAt = System.currentTimeMillis()
            if (ctx.bank().items().isEmpty() && System.currentTimeMillis() - bankOpenedAt < BANK_SETTLE_MS) {
                return snap(120, 260)
            }
            return null
        }
        bankOpenedAt = 0L
        val chest = ctx.objects().query().named("Bank chest").nearest()
            ?: return if (ctx.bank().openNearest()) snap(400, 900) else snap(700, 1500)
        mark(chest, Color(0x33, 0x99, 0xFF), "Bank")
        if (bankOpening) {
            val moving = ctx.players().localPlayer()?.isMoving ?: false
            if (moving || System.currentTimeMillis() - bankClickedAt < ACTION_RETRY_MS) return snap(300, 900)
            bankOpening = false
        }
        // Left-click when "Use" is the clean default; else a PRECISE click on the chest's real clickbox
        // (interactPrecise finds the true footprint instead of right-click-hunting a wrong point — which
        // spammed "No menu option 'Use'"). Fall back to the client's robust opener only if both miss.
        if (chest.leftClickIfDefault("Use") || chest.interactPrecise("Use")) {
            bankOpening = true; bankClickedAt = System.currentTimeMillis()
            return snap(400, 1000)
        }
        return if (ctx.bank().open()) snap(300, 800) else snap(500, 1000)
    }

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
        /** Centre of the Blast Furnace room (under Keldagrim) — the web-walk target to get INTO the room;
         *  once inside, every object is clicked directly (walk-to-interact), never via a walk-to tile. */
        val ANCHOR = Tile(1940, 4962, 0)
        /** "At the furnace" radius — the whole room fits comfortably inside it. */
        const val BF_RADIUS = 30

        // net.runelite.api.gameval.VarbitID
        const val COFFER_VARBIT = 5357 // BLAST_FURNACE_COFFER — gp left for the furnace dwarves
        const val COAL_VARBIT = 949    // BLAST_FURNACE_COAL — coal buffered inside the furnace

        // net.runelite.api.gameval.ItemID
        const val ICE_GLOVES = 1580
        const val BUCKET_OF_WATER = 1929

        const val COINS = "Coins"
        const val COAL_BAG = "Coal bag" // ItemID.COAL_BAG (12019) — "Fill"/"Empty" at the bank/belt
        /** Kept in the inventory when banking — everything else (travel jewellery, stray drops) is deposited. */
        val KEEP_REFS = arrayOf(ItemRef.ByName(COAL_BAG), ItemRef.ById(BUCKET_OF_WATER), ItemRef.ByName(COINS))
        /** One-time furnace framing: enough wheel notches to bottom out the zoom, and the max overhead pitch. */
        const val FULL_ZOOM_NOTCHES = 15
        const val OVERHEAD_PITCH = 383 // CameraImpl coerces to the client's PITCH_MAX
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
        /** Minimum coffer deposit — the top-up is coerced up to this so a run always buys a decent buffer. */
        const val LOW_COFFER = 7_200
        /** The furnace dwarves drain the coffer at a FIXED 12 gp/tick = 72,000 gp/hour (OSRS wiki). */
        const val COFFER_DRAIN_PER_HOUR = 72_000
        /** Refuel once the coffer has under this many SECONDS of smelting left (≈1800 gp) — just-in-time,
         *  not the ~5 minutes early a flat 7,200-gp threshold gave. */
        const val COFFER_LEAD_SEC = 90
        /** Coal the furnace should hold before a primary-ore load goes on (a full load's worth per coal). */
        const val PRIMARY_LOAD = 26

        const val ACTION_RETRY_MS = 3500L
        /** Hold a menu-swap this long before/around the click so the injected default lands on the live menu. */
        const val SWAP_SETTLE_MS = 150L
        /** After a load lands on the belt, wait at least this long for the furnace's varbits to register it
         *  before treating the furnace as idle (they lag ~a tick) — stops a premature bank trip + backtrack. */
        const val FURNACE_SETTLE_MS = 1800L
        /** Max wait for the bank's items to populate after it opens before trusting a count — proceeds the
         *  instant they appear; only a genuinely empty bank waits this out. */
        const val BANK_SETTLE_MS = 1200L
    }
}
