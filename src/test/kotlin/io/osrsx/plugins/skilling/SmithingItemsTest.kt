package io.osrsx.plugins.skilling

import io.osrsx.api.Skill
import io.osrsx.api.WorldInfo
import io.osrsx.testkit.TestContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmithingItemsTest {

    /** A headless context reporting a fixed membership / Smithing level for eligibility checks. */
    private fun ctx(members: Boolean, smithing: Int): TestContext {
        val ctx = TestContext()
        whenever(ctx.worlds.current()).thenReturn(301)
        whenever(ctx.worlds.list())
            .thenReturn(listOf(WorldInfo(301, 100, 0, members, false, false, emptySet())))
        whenever(ctx.skills.real(Skill.SMITHING)).thenReturn(smithing)
        return ctx
    }

    @Test
    fun `bar display round-trips`() {
        BarType.entries.forEach { assertEquals(it, BarType.fromDisplay(it.display)) }
        BlastBar.entries.forEach { assertEquals(it, BlastBar.fromDisplay(it.display)) }
    }

    @Test
    fun `bar item names round-trip (the item-picker stored value)`() {
        BarType.entries.forEach { assertEquals(it, BarType.fromBarName(it.barName)) }
        BlastBar.entries.forEach { assertEquals(it, BlastBar.fromBarName(it.barName)) }
    }

    @Test
    fun `levels follow the wiki base + offset table`() {
        // Spot-checks straight off the OSRS wiki Smithing table.
        assertEquals(1, SmithProduct.DAGGER.levelFor(BarType.BRONZE))
        assertEquals(18, SmithProduct.PLATEBODY.levelFor(BarType.BRONZE))
        assertEquals(20, SmithProduct.SCIMITAR.levelFor(BarType.IRON))
        assertEquals(34, SmithProduct.NAILS.levelFor(BarType.STEEL))
        assertEquals(48, SmithProduct.PLATEBODY.levelFor(BarType.STEEL))
        assertEquals(54, SmithProduct.DART_TIPS.levelFor(BarType.MITHRIL))
        assertEquals(86, SmithProduct.PLATELEGS.levelFor(BarType.ADAMANT))
        assertEquals(90, SmithProduct.SCIMITAR.levelFor(BarType.RUNE))
        assertEquals(99, SmithProduct.TWO_H_SWORD.levelFor(BarType.RUNE))
        assertEquals(99, SmithProduct.PLATEBODY.levelFor(BarType.RUNE)) // 85+18 clamps to 99
    }

    @Test
    fun `product item names match the real game items`() {
        assertEquals("Bronze dagger", SmithProduct.DAGGER.itemName(BarType.BRONZE))
        assertEquals("Adamant platebody", SmithProduct.PLATEBODY.itemName(BarType.ADAMANT))
        assertEquals("Rune sq shield", SmithProduct.SQ_SHIELD.itemName(BarType.RUNE))
        assertEquals("Mithril dart tip", SmithProduct.DART_TIPS.itemName(BarType.MITHRIL))
        assertEquals("Steel bolts (unf)", SmithProduct.BOLTS.itemName(BarType.STEEL))
        // The bar is "Adamantite"/"Runite", the products "Adamant"/"Rune".
        assertEquals("Adamantite bar", BarType.ADAMANT.barName)
        assertEquals("Runite bar", BarType.RUNE.barName)
    }

    @Test
    fun `interface slots are unique`() {
        val children = SmithProduct.entries.map { it.child }
        assertEquals(children.size, children.toSet().size, "duplicate interface slots")
    }

    @Test
    fun `eligibility hides high-level and members lines`() {
        // Smithing 30 on F2P: steel dagger (30) fine, steel platebody (48) hidden, dart tips members-hidden.
        val options = SmithProduct.optionsFor(BarType.STEEL, ctx(members = false, smithing = 30))
        assertTrue(SmithProduct.DAGGER.display in options)
        assertFalse(SmithProduct.PLATEBODY.display in options)
        assertFalse(SmithProduct.DART_TIPS.display in options)
    }

    @Test
    fun `maxed members account sees every product line`() {
        val options = SmithProduct.optionsFor(BarType.RUNE, ctx(members = true, smithing = 99))
        assertEquals(SmithProduct.entries.size, options.size)
    }

    @Test
    fun `blast furnace coal ratios are the halved wiki costs`() {
        assertEquals(0, BlastBar.IRON.coalPerBar)
        assertEquals(1, BlastBar.STEEL.coalPerBar)
        assertEquals(2, BlastBar.MITHRIL.coalPerBar)
        assertEquals(3, BlastBar.ADAMANTITE.coalPerBar)
        assertEquals(4, BlastBar.RUNITE.coalPerBar)
    }

    @Test
    fun `blast furnace varbits are distinct per bar and ore`() {
        val varbits = BlastBar.entries.flatMap { listOf(it.barVarbit, it.oreVarbit) }
        // Steel shares iron's ORE varbit (both feed iron ore) — everything else must be distinct.
        assertEquals(varbits.size - 1, varbits.toSet().size)
    }

    @Test
    fun `blast furnace options are level-gated`() {
        val options = BlastBar.optionsFor(ctx(members = true, smithing = 40))
        assertTrue(BlastBar.GOLD.display in options)
        assertTrue(BlastBar.IRON.display in options)
        assertFalse(BlastBar.MITHRIL.display in options)
    }

    @Test
    fun `blast furnace world is identified by its activity and picked by lowest population`() {
        fun w(id: Int, players: Int, activity: String = "", types: Set<String> = emptySet()) =
            WorldInfo(id, players, 0, true, false, false, types, activity)

        val worlds = listOf(
            w(301, 500),                                    // plain world, no activity
            w(352, 800, "Blast Furnace"),                   // official BF, busy
            w(355, 120, "Blast Furnace"),                   // official BF, quietest
            w(358, 60, "Blast Furnace", setOf("HIGH_RISK")),// BF but restricted — excluded by `normal`
            w(360, 90, "Wintertodt"),                       // different activity
        )

        assertTrue(BfWorlds.isOn(worlds, 352))
        assertFalse(BfWorlds.isOn(worlds, 301))
        // Picks the least-populated NORMAL Blast Furnace world, never the current one or the high-risk one.
        assertEquals(355, BfWorlds.pick(worlds, current = 301))
        assertEquals(355, BfWorlds.pick(worlds, current = 352))
        // Already on the quietest BF world → the only other normal BF world (352) is the next best.
        assertEquals(352, BfWorlds.pick(worlds, current = 355))
        // Nothing to hop to when the list holds no eligible BF world.
        assertEquals(null, BfWorlds.pick(worlds.filter { it.id == 358 }, current = 301))
        assertEquals(null, BfWorlds.pick(emptyList(), current = 301))
    }

    @Test
    fun `WorldInfo activity match is case-insensitive and defaults empty`() {
        val bf = WorldInfo(352, 100, 0, true, false, false, emptySet(), "Blast Furnace")
        assertTrue(bf.isActivity("blast furnace"))
        assertFalse(bf.isActivity("Wintertodt"))
        // The trailing default keeps older positional constructors valid.
        assertFalse(WorldInfo(301, 100, 0, false, false, false, emptySet()).isActivity("Blast Furnace"))
    }

    @Test
    fun `anvil sites resolve BEST and a specific label`() {
        val ctx = ctx(members = false, smithing = 40)
        assertEquals(AnvilSites.BEST, AnvilSites.optionsFor(ctx).last(), "BEST is always the last option")
        assertNotNull(AnvilSites.siteFor(ctx, AnvilSites.BEST))
        assertEquals("VarrockWest", AnvilSites.siteFor(ctx, "VarrockWest")?.id)
    }
}
