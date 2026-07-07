package io.osrsx.plugins.skilling

import io.osrsx.api.ItemRef
import io.osrsx.api.PluginContext
import io.osrsx.api.RestockSpec
import io.osrsx.api.section
import io.osrsx.util.Rng

/**
 * Keeps the smither's tools in order, on the declarative **Loadout** API (the same shape as the miner's
 * `Pickaxe`): a hammer in the inventory for anvil work (bought off the GE if none is owned — it's pennies),
 * and, for the Blast Furnace, **Ice gloves worn if the account owns a pair** (they let hot bars be taken
 * straight from the dispenser; never bought — they're a Fight Arena drop, not a shop item).
 *
 * Drive it one step per loop before working. [ensure] returns:
 *  - `null`  → geared — proceed;
 *  - `> 0`   → busy acquiring this loop — return this ms delay from `onLoop`.
 */
class SmithGear(
    private val ctx: PluginContext,
    private val wantHammer: () -> Boolean,
    private val wantIceGloves: () -> Boolean = { false },
) {

    /** Consecutive loops the gear has looked UNMET — debounces a transient empty inventory read so one
     *  glitchy loop can't trigger a needless bank/GE trip for tools we're actually holding. */
    private var gearUnmetStreak = 0

    /** Set once we've seen the bank and equipped ice gloves if owned — so we don't keep reopening the bank
     *  chasing gloves the account simply doesn't have (unowned vs unseen is undecidable while it's shut). */
    private var glovesChecked = false

    /** Drive one step toward holding the hammer (and wearing owned ice gloves when wanted). */
    fun ensure(): Long? = ctx.profiler().section("smither/gear") {
        // Read each container ONCE (a single client-thread hop each) and match locally.
        val invNames = ctx.inventory().items().mapNotNull { it.name }
        val equipIds = ctx.equipment().items().mapTo(HashSet()) { it.id }

        val hasHammer = !wantHammer() || invNames.any { it in HAMMERS }
        val glovesDone = !wantIceGloves() || glovesChecked || ICE_GLOVES in equipIds

        if (hasHammer && glovesDone) { gearUnmetStreak = 0; return@section null }

        // Require the deficit to PERSIST a few loops before touching the bank — a transient empty container
        // read (hot-reload, client-thread hiccup) must not fire a bank/GE trip for gear we already hold.
        if (++gearUnmetStreak < GEAR_CONFIRM) return@section Rng.uniform(200, 500)

        // Equipping gloves needs the bank VISIBLE to know whether we own a pair — open it first when the
        // hammer side is already fine (the loadout below wouldn't open it on its own then).
        val bankOpen = ctx.bank().isOpen()
        if (hasHammer && !glovesDone && !bankOpen) {
            ctx.bank().openNearest()
            return@section Rng.uniform(400, 800)
        }

        val invIds = ctx.inventory().items().mapTo(HashSet()) { it.id }
        val bankIds = if (bankOpen) ctx.bank().items().mapTo(HashSet()) { it.id } else emptySet()
        val ownGloves = ICE_GLOVES in invIds || ICE_GLOVES in equipIds || ICE_GLOVES in bankIds

        val loadout = ctx.loadouts().build("Smither gear") {
            if (wantHammer()) {
                item(
                    ItemRef.AnyOf(HAMMER_IDS.toList()),
                    quantity = 1,
                    minimum = 1,
                    restock = RestockSpec(ItemRef.ById(HAMMER), 1, markupPercent = 10),
                )
            }
            // Equip ONLY if owned — a loadout equip for an item we don't have can never be satisfied.
            if (wantIceGloves() && ownGloves) equip(ItemRef.ById(ICE_GLOVES))
        }
        if (ctx.loadouts().apply(loadout)) {
            if (bankOpen || ICE_GLOVES in equipIds) glovesChecked = true
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
    }
}
