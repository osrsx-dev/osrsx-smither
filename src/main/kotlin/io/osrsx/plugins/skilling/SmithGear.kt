package io.osrsx.plugins.skilling

import io.osrsx.api.ItemRef
import io.osrsx.api.PluginContext
import io.osrsx.api.RestockSpec
import io.osrsx.api.section
import io.osrsx.util.Rng

/**
 * Keeps the smither's tools in order AND declutters the inventory to a clean working set, on the declarative
 * **Loadout** API (the same shape as the miner's `Pickaxe`):
 *
 *  - a hammer in the inventory for anvil work (bought off the GE if none is owned — it's pennies);
 *  - for the Blast Furnace, **Ice gloves worn if owned** (they let hot bars be taken bare-handed; never
 *    bought — a Fight Arena drop), else a bucket of water carried; the **coal bag kept** if owned;
 *  - **Graceful (agility) clothing worn if owned** — each piece equipped when the account has it (never
 *    bought), matched by name so any recolour counts;
 *  - **declutter**: because [io.osrsx.api.Loadouts.apply] deposits every FOREIGN item (anything not declared
 *    in the loadout) plus excess, applying this loadout at gear-up strips transport junk (teleport tabs,
 *    stray jewellery, leftover drops) down to the required set — exactly like the miner's gear-up.
 *
 * Drive it one step per loop before working. [ensure] returns:
 *  - `null`  → geared + decluttered — proceed;
 *  - `> 0`   → busy acquiring/tidying this loop — return this ms delay from `onLoop`.
 */
class SmithGear(
    private val ctx: PluginContext,
    private val wantHammer: () -> Boolean,
    private val wantIceGloves: () -> Boolean = { false },
    /** Blast Furnace: carry a bucket of water for cooling when ice gloves aren't worn, and keep the coal bag. */
    private val blastFurnace: () -> Boolean = { false },
    /** Equip owned Graceful clothing while working (agility outfit — purely cosmetic upkeep, never bought). */
    private val wantGraceful: () -> Boolean = { false },
) {

    /** Consecutive loops the gear has looked UNMET — debounces a transient empty inventory read so one
     *  glitchy loop can't trigger a needless bank/GE trip for tools we're actually holding. */
    private var gearUnmetStreak = 0

    /** Set once we've seen the bank and settled the owned-only wearables (ice gloves + graceful) — so we don't
     *  keep reopening the bank chasing pieces the account simply doesn't have (unowned vs unseen is undecidable
     *  while the bank is shut). */
    private var wearablesChecked = false

    /** Drive one step toward holding the hammer, wearing owned ice gloves / graceful, and a decluttered inv. */
    fun ensure(): Long? = ctx.profiler().section("smither/gear") {
        // Read each container ONCE (a single client-thread hop each) and match locally.
        val invNames = ctx.inventory().items().mapNotNull { it.name }
        val equipIds = ctx.equipment().items().mapTo(HashSet()) { it.id }
        val equipNames = ctx.equipment().items().mapNotNull { it.name }.toHashSet()

        val hasHammer = !wantHammer() || invNames.any { it in HAMMERS }
        val glovesDone = !wantIceGloves() || wearablesChecked || ICE_GLOVES in equipIds
        val gracefulDone = !wantGraceful() || wearablesChecked || GRACEFUL_PIECES.all { it in equipNames }

        if (hasHammer && glovesDone && gracefulDone) { gearUnmetStreak = 0; return@section null }

        // Require the deficit to PERSIST a few loops before touching the bank — a transient empty container
        // read (hot-reload, client-thread hiccup) must not fire a bank/GE trip for gear we already hold.
        if (++gearUnmetStreak < GEAR_CONFIRM) return@section Rng.uniform(200, 500)

        // The owned-only wearables (ice gloves / graceful / coal bag) need the bank VISIBLE to decide ownership
        // and to equip/withdraw from — open it first when the hammer side is already fine (the loadout below
        // wouldn't open it on its own then).
        val bankOpen = ctx.bank().isOpen()
        if (hasHammer && !(glovesDone && gracefulDone) && !bankOpen) {
            ctx.bank().openNearest()
            return@section Rng.uniform(400, 800)
        }

        val invIds = ctx.inventory().items().mapTo(HashSet()) { it.id }
        val bankIds = if (bankOpen) ctx.bank().items().mapTo(HashSet()) { it.id } else emptySet()
        val bankNames = if (bankOpen) ctx.bank().items().mapNotNull { it.name }.toHashSet() else emptySet()
        fun owned(id: Int) = id in invIds || id in equipIds || id in bankIds
        fun ownedName(name: String) = name in invNames || name in equipNames || name in bankNames

        val ownGloves = owned(ICE_GLOVES)
        val ownedGraceful = if (wantGraceful()) GRACEFUL_PIECES.filter { ownedName(it) && it !in equipNames } else emptyList()
        val ownCoalBag = ownedName(COAL_BAG)

        // ONE loadout describes the whole required set. apply() reconciles: withdraw/equip the deficits, and
        // DEPOSIT everything foreign (the declutter). Declaring the coal bag / bucket keeps them out of that
        // foreign-deposit so a decluttering pass never dumps the working tools.
        val loadout = ctx.loadouts().build("Smither gear") {
            if (wantHammer()) {
                item(
                    ItemRef.AnyOf(HAMMER_IDS.toList()),
                    quantity = 1,
                    minimum = 1,
                    restock = RestockSpec(ItemRef.ById(HAMMER), 1, markupPercent = 10),
                )
            }
            // Equip ONLY what's owned — a loadout equip for an item we don't have can never be satisfied.
            if (wantIceGloves() && ownGloves) equip(ItemRef.ById(ICE_GLOVES))
            ownedGraceful.forEach { equip(ItemRef.ByName(it)) }
            if (blastFurnace()) {
                // Keep the coal bag (a tool) so the declutter never deposits it; a bucket of water for cooling
                // rides along only when ice gloves aren't the plan.
                if (ownCoalBag) item(ItemRef.ByName(COAL_BAG), quantity = 1, minimum = 1)
                if (!wantIceGloves()) item(ItemRef.ById(BUCKET_OF_WATER), quantity = 1, minimum = 1)
            }
        }
        if (ctx.loadouts().apply(loadout)) {
            // Once the bank's been seen, latch the owned-only wearables so we don't reopen it chasing pieces
            // the account doesn't have (equipped what we own; the rest simply isn't owned).
            if (bankOpen || (glovesDone && gracefulDone)) wearablesChecked = true
            return@section null
        }
        Rng.uniform(400, 800)
    }

    private companion object {
        const val GEAR_CONFIRM = 3
        const val HAMMER = 2347     // ItemID.HAMMER
        const val IMCANDO = 25644   // ItemID.IMCANDO_HAMMER (used if owned, never bought)
        val HAMMER_IDS = intArrayOf(HAMMER, IMCANDO)
        val HAMMERS = setOf("Hammer", "Imcando hammer")
        const val ICE_GLOVES = 1580 // ItemID.ICE_GLOVES — worn if owned, never bought
        const val BUCKET_OF_WATER = 1929
        const val COAL_BAG = "Coal bag"

        /** Graceful (agility) outfit pieces, matched by NAME so any recolour (Ardougne, Kourend, …) counts.
         *  Worn if the account already owns them — purely cosmetic, never bought. */
        val GRACEFUL_PIECES = listOf(
            "Graceful hood", "Graceful top", "Graceful legs", "Graceful cape", "Graceful gloves", "Graceful boots",
        )
    }
}
