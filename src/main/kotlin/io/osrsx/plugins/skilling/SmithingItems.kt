package io.osrsx.plugins.skilling

import io.osrsx.api.PluginContext
import io.osrsx.api.Skill
import io.osrsx.api.Tile
import io.osrsx.api.WorldInfo

/**
 * The static Smithing catalogue the [SmitherPlugin] is driven by, plus the live account checks that decide
 * what the current player may actually make (mirroring [the miner's site catalogue] shape):
 *
 *  - [BarType] — the six anvil metals ("Adamant platebody" is made from an "Adamantite bar").
 *  - [SmithProduct] — everything the anvil interface can make, each carrying its interface slot (the child
 *    component in group 312), its level offset from the metal's base, and its bars-per-make.
 *  - [BlastBar] — what the Blast Furnace can smelt, each with its ore inputs, its (halved) coal cost and
 *    the varbits tracking that bar/ore inside the furnace.
 *  - [AnvilSites] — curated anvil-with-a-bank-nearby locations, filtered live by account eligibility.
 *
 * Smithing levels follow the wiki's fixed table: `level = clamp(base + offset, 1, 99)` where base is
 * Bronze 0 / Iron 15 / Steel 30 / Mithril 50 / Adamant 70 / Rune 85 (e.g. platebody +18 ⇒ Steel platebody
 * 48, Rune platebody 99).
 */
enum class BarType(val display: String, val barName: String, val productPrefix: String, val baseLevel: Int) {
    BRONZE("Bronze", "Bronze bar", "Bronze", 0),
    IRON("Iron", "Iron bar", "Iron", 15),
    STEEL("Steel", "Steel bar", "Steel", 30),
    MITHRIL("Mithril", "Mithril bar", "Mithril", 50),
    ADAMANT("Adamant", "Adamantite bar", "Adamant", 70),
    RUNE("Rune", "Runite bar", "Rune", 85);

    companion object {
        fun fromDisplay(value: String): BarType = entries.firstOrNull { it.display == value } ?: BRONZE
        val displays: List<String> get() = entries.map { it.display }

        /** The enum whose real bar item name matches [value] (the item-picker stored string), default [BRONZE]. */
        fun fromBarName(value: String): BarType = entries.firstOrNull { it.barName == value } ?: BRONZE
        val barNames: List<String> get() = entries.map { it.barName }
    }
}

/**
 * One anvil-interface product line. [child] is the item slot inside interface group 312
 * (`net.runelite.api.gameval.InterfaceID.Smithing`), [levelOffset] the wiki offset from the metal's base
 * level, [bars] the bars consumed per make and [perMake] the items produced per make (dart tips come in
 * 10s, nails in 15s, …). [itemSuffix] builds the real item name: `"${bar.productPrefix} $itemSuffix"`.
 */
enum class SmithProduct(
    val display: String,
    val child: Int,
    val levelOffset: Int,
    val bars: Int,
    val itemSuffix: String,
    val perMake: Int = 1,
    val members: Boolean = false,
) {
    DAGGER("Dagger", 9, 0, 1, "dagger"),
    AXE("Axe", 14, 1, 1, "axe"),
    MACE("Mace", 15, 2, 1, "mace"),
    MED_HELM("Med helm", 24, 3, 1, "med helm"),
    BOLTS("Bolts (unf)", 34, 3, 1, "bolts (unf)", perMake = 10, members = true),
    SWORD("Sword", 10, 4, 1, "sword"),
    DART_TIPS("Dart tips", 29, 4, 1, "dart tip", perMake = 10, members = true),
    NAILS("Nails", 23, 4, 1, "nails", perMake = 15, members = true),
    ARROWTIPS("Arrowtips", 30, 5, 1, "arrowtips", perMake = 15, members = true),
    SCIMITAR("Scimitar", 11, 5, 2, "scimitar"),
    LONGSWORD("Longsword", 12, 6, 2, "longsword"),
    LIMBS("Crossbow limbs", 35, 6, 1, "limbs", members = true),
    FULL_HELM("Full helm", 25, 7, 2, "full helm"),
    KNIVES("Knives", 31, 7, 1, "knife", perMake = 5, members = true),
    SQ_SHIELD("Sq shield", 26, 8, 2, "sq shield"),
    WARHAMMER("Warhammer", 16, 9, 3, "warhammer"),
    BATTLEAXE("Battleaxe", 17, 10, 3, "battleaxe"),
    CHAINBODY("Chainbody", 19, 11, 3, "chainbody"),
    KITESHIELD("Kiteshield", 27, 12, 3, "kiteshield"),
    CLAWS("Claws", 18, 13, 2, "claws", members = true),
    TWO_H_SWORD("2h sword", 13, 14, 3, "2h sword"),
    PLATELEGS("Platelegs", 20, 16, 3, "platelegs"),
    PLATESKIRT("Plateskirt", 21, 16, 3, "plateskirt"),
    PLATEBODY("Platebody", 22, 18, 5, "platebody");

    /** The Smithing level to make this from [bar] (wiki base+offset table, clamped to 1..99). */
    fun levelFor(bar: BarType): Int = (bar.baseLevel + levelOffset).coerceIn(1, 99)

    /** The real product item name for [bar], e.g. `"Rune sq shield"`, `"Mithril dart tip"`. */
    fun itemName(bar: BarType): String = "${bar.productPrefix} $itemSuffix"

    companion object {
        fun fromDisplay(value: String): SmithProduct? = entries.firstOrNull { it.display == value }

        /**
         * The products the account can make from [bar] RIGHT NOW: Smithing level met (permissive when the
         * skill isn't readable yet) and members-only lines hidden on a F2P world. Drives the "Item" dropdown.
         */
        fun eligible(bar: BarType, ctx: PluginContext): List<SmithProduct> {
            val level = ctx.skills().real(Skill.SMITHING)
            val members = isMembers(ctx)
            return entries.filter { p ->
                (level <= 0 || level >= p.levelFor(bar)) && !(p.members && members == false)
            }
        }

        fun optionsFor(bar: BarType, ctx: PluginContext): List<String> = eligible(bar, ctx).map { it.display }
    }
}

/**
 * Official Blast Furnace world selection. Only these worlds run the paid dwarves that the coffer funds;
 * RuneLite tags them `activity = "Blast Furnace"` (see [WorldInfo.activity]). Pure functions over a world
 * list so the routine's hop decision is unit-testable headlessly.
 */
object BfWorlds {
    const val ACTIVITY = "Blast Furnace"

    /** Is [worldId] an official Blast Furnace world within [worlds]? */
    fun isOn(worlds: List<WorldInfo>, worldId: Int): Boolean =
        worlds.firstOrNull { it.id == worldId }?.isActivity(ACTIVITY) == true

    /** The least-populated official Blast Furnace world other than [current], or null if none are listed. */
    fun pick(worlds: List<WorldInfo>, current: Int): Int? =
        worlds.filter { it.id != current && it.normal && it.isActivity(ACTIVITY) }
            .minByOrNull { it.players }?.id
}

/** True/false when the current world's membership is known, null while the world list is still loading. */
fun isMembers(ctx: PluginContext): Boolean? {
    val worlds = ctx.worlds()
    val current = worlds.current()
    return worlds.list().firstOrNull { it.id == current }?.members
}

/**
 * One Blast Furnace bar line: the ore(s) fed onto the conveyor, the per-bar coal cost (the furnace HALVES
 * the normal coal requirement), and the varbits that mirror the furnace's internal stock of this bar and
 * its primary ore (`net.runelite.api.gameval.VarbitID.BLAST_FURNACE_*` — the same ids RuneLite's own
 * Blast Furnace plugin reads).
 */
enum class BlastBar(
    val display: String,
    val barName: String,
    val level: Int,
    val primaryOre: String,
    val coalPerBar: Int,
    val barVarbit: Int,
    val oreVarbit: Int,
    /** Bronze only: the 1:1 second ore fed alongside the primary. */
    val secondaryOre: String? = null,
) {
    BRONZE("Bronze", "Bronze bar", 1, "Copper ore", 0, barVarbit = 941, oreVarbit = 959, secondaryOre = "Tin ore"),
    IRON("Iron", "Iron bar", 15, "Iron ore", 0, barVarbit = 942, oreVarbit = 951),
    SILVER("Silver", "Silver bar", 20, "Silver ore", 0, barVarbit = 948, oreVarbit = 956),
    STEEL("Steel", "Steel bar", 30, "Iron ore", 1, barVarbit = 943, oreVarbit = 951),
    GOLD("Gold", "Gold bar", 40, "Gold ore", 0, barVarbit = 947, oreVarbit = 955),
    MITHRIL("Mithril", "Mithril bar", 50, "Mithril ore", 2, barVarbit = 944, oreVarbit = 952),
    ADAMANTITE("Adamantite", "Adamantite bar", 70, "Adamantite ore", 3, barVarbit = 945, oreVarbit = 953),
    RUNITE("Runite", "Runite bar", 85, "Runite ore", 4, barVarbit = 946, oreVarbit = 954);

    companion object {
        fun fromDisplay(value: String): BlastBar = entries.firstOrNull { it.display == value } ?: STEEL
        val displays: List<String> get() = entries.map { it.display }

        /** The enum whose real bar item name matches [value] (the item-picker stored string), default [STEEL]. */
        fun fromBarName(value: String): BlastBar = entries.firstOrNull { it.barName == value } ?: STEEL

        /** The bars the account can smelt right now (Smithing level met; permissive when unreadable). */
        fun eligible(ctx: PluginContext): List<BlastBar> {
            val level = ctx.skills().real(Skill.SMITHING)
            return entries.filter { level <= 0 || level >= it.level }
        }

        fun optionsFor(ctx: PluginContext): List<String> = eligible(ctx).map { it.display }
    }
}

/**
 * One catalogued anvil spot: an anchor [tile] in the middle of the anvils with a bank close by. Mirrors
 * the miner's `MineSite` shape so more sites (Prifddinas, …) can be added as their gating (quests) becomes
 * checkable.
 */
data class AnvilSite(
    val id: String,
    val tile: Tile,
    val members: Boolean = false,
) {
    fun label(): String = if (members) "$id (P2P)" else id
}

object AnvilSites {

    /** The always-present bottom entry of the Location dropdown — resolved live to the nearest eligible site. */
    const val BEST = "Auto — select best"

    // Anchor tiles are the centre of the anvil cluster (bank within a short walk). Varrock West is THE
    // classic bank-anvil pair (4 anvils one building south of the west bank) and is F2P.
    val SITES: List<AnvilSite> = listOf(
        AnvilSite("VarrockWest", Tile(3188, 3425, 0)),
    )

    fun eligible(site: AnvilSite, ctx: PluginContext): Boolean =
        !(site.members && isMembers(ctx) == false)

    fun eligibleSites(ctx: PluginContext): List<AnvilSite> = SITES.filter { eligible(it, ctx) }

    fun optionsFor(ctx: PluginContext): List<String> = eligibleSites(ctx).map { it.label() } + BEST

    fun byLabel(label: String): AnvilSite? = SITES.firstOrNull { it.label() == label }

    /** The nearest eligible site to the player (they all have a bank beside them). Null if none qualify. */
    fun resolveBest(ctx: PluginContext): AnvilSite? {
        val sites = eligibleSites(ctx)
        if (sites.isEmpty()) return null
        val me = ctx.players().localPlayer()?.tile() ?: return sites.first()
        return sites.minByOrNull { it.tile.distanceTo(me) }
    }

    fun siteFor(ctx: PluginContext, location: String): AnvilSite? =
        if (location == BEST || location.isBlank()) resolveBest(ctx)
        else byLabel(location) ?: resolveBest(ctx)
}
