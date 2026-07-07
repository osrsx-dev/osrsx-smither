package io.osrsx.plugins.skilling

import io.osrsx.api.BreakManager
import io.osrsx.api.PluginContext
import io.osrsx.api.SceneEntity
import io.osrsx.api.get
import io.osrsx.api.section
import io.osrsx.plugin.Plugin
import io.osrsx.plugin.PluginLog
import io.osrsx.util.Rng

/**
 * The shared per-loop scaffolding the smither needs on every tick — login, coordination yield, stop targets,
 * input lock, account-wide break checks, run management and a light antiban idle — wrapped around a
 * routine-supplied [step]. Self-contained (no skilling-lib): the anvil and Blast Furnace routines each HOLD
 * one of these and drive it from `onLoop`.
 *
 * Unlike the miner's loop this does NOT auto-continue dialogues: the Blast Furnace routine drives its own
 * dialogues (the coffer's Deposit prompt, paying the foreman), so a blanket `continueAuto()` here would eat
 * the very option menus a step is waiting on. Routines opt in via [autoDialogue] — the anvil routine keeps
 * it on (a level-up "continue" prompt otherwise stalls the batch), the furnace turns it off.
 *
 * The whole tick is timed under `smither/loop` via the plugin [Profiler]; when profiling is off that is a
 * single volatile read (zero overhead), so instrumentation is left in freely.
 */
class SmitherLoop(
    private val ctx: PluginContext,
    private val lockInput: () -> Boolean,
    private val stopReason: () -> String?,
    private val autoDialogue: Boolean = true,
    private val step: () -> Long,
) {
    private val breaks: BreakManager? get() = ctx.services().get<BreakManager>()

    /** Run one loop iteration. Returns ms until the next call, or [Plugin.NO_LOOP] to stop. */
    fun tick(): Long = ctx.profiler().section("smither/loop") {
        if (!ctx.login().isLoggedIn()) { ctx.login().login(); return@section 1500 }
        if (ctx.coordination().shouldYield()) return@section Rng.uniform(1200, 2000)
        stopReason()?.let { reason ->
            PluginLog("smither").i("stopping — $reason"); releaseInput(); return@section Plugin.NO_LOOP
        }
        applyInputLock()
        breaks?.let { if (it.onBreak()) return@section Rng.uniform(2000, 5000) }
        ctx.walking().manageRun()
        if (autoDialogue && ctx.dialogues().canContinue()) {
            ctx.dialogues().continueAuto(); return@section Rng.uniform(600, 1000)
        }
        if (Rng.chance(IDLE_CHANCE)) return@section Rng.uniform(IDLE_MIN_MS, IDLE_MAX_MS)
        step()
    }

    /** Release the input lock — call from the plugin's `onStop`. */
    fun releaseInput() { if (ctx.input().isLocked()) ctx.input().unlock() }

    private fun applyInputLock() {
        val want = lockInput()
        if (want && !ctx.input().isLocked()) ctx.input().lock()
        else if (!want && ctx.input().isLocked()) ctx.input().unlock()
    }

    fun isAnimating(): Boolean = (ctx.players().localPlayer()?.animation ?: IDLE) != IDLE

    /**
     * Whether we should still consider ourselves WORKING (busy at the anvil/furnace), with the idle
     * animation DEBOUNCED: the smithing animation dips to idle for a beat between hammer swings (each item
     * is several game ticks), so a bare [isAnimating] == false would look like the batch finished and make
     * the routine re-click the anvil mid-batch. Returns true while animating, and for up to [debounceMs]
     * after idle first appears — only a sustained idle (batch actually done / out of bars) reads as stopped.
     */
    fun stillWorking(debounceMs: Long = IDLE_DEBOUNCE_MS): Boolean {
        if (isAnimating()) { idleSinceMs = 0L; return true }
        if (idleSinceMs == 0L) idleSinceMs = System.currentTimeMillis()
        return System.currentTimeMillis() - idleSinceMs < debounceMs
    }

    private var idleSinceMs = 0L

    /** Can we stand next to [entity] and interact with it? */
    fun canReach(entity: SceneEntity): Boolean {
        val tile = entity.tile() ?: return false
        return ctx.walking().canReachToInteract(tile)
    }

    private companion object {
        const val IDLE = -1
        /** How long the idle animation must persist before we treat the batch as actually stopped — the
         *  anvil animation's between-swing dip is well under this. */
        const val IDLE_DEBOUNCE_MS = 2200L
        const val IDLE_CHANCE = 0.03
        const val IDLE_MIN_MS = 1500L
        const val IDLE_MAX_MS = 4000L
    }
}
