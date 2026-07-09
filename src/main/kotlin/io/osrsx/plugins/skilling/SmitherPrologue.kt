package io.osrsx.plugins.skilling

import io.osrsx.api.BreakManager
import io.osrsx.api.PluginContext
import io.osrsx.api.SceneEntity
import io.osrsx.api.get
import io.osrsx.plugin.Plugin
import io.osrsx.plugin.PluginLog
import io.osrsx.plugin.RoutineBuilder
import io.osrsx.util.Rng

/**
 * The shared per-tick scaffolding every smither routine needs, expressed as [io.osrsx.plugin.Routine] guards +
 * a before-tick side effect instead of a hand-rolled loop — this REPLACES the old `SmitherLoop`.
 *
 * Guards run in priority order BEFORE the routine senses, so a heavy / side-effecting `sense()` (an inventory
 * read, a `gearUp()` that banks a hammer) never fires while logged out or on a break:
 *
 *   login → coordination-yield → stop-target → break → auto-dialogue → antiban-idle
 *
 * The always-run upkeep — input-lock maintenance + run-energy management + [beforeEachExtra] — is the
 * [RoutineBuilder.beforeEach] hook, gated on being logged in. Input-lock is plugin *policy* (a config toggle),
 * so it lives HERE in the plugin, not in the SDK base. [beforeEachExtra] is where the Blast Furnace refreshes
 * its camera hold EVERY tick (including break / idle ticks) so the lease can't lapse mid-run.
 *
 * NOTE — the auto-dialogue guard uses `canContinue()` (a "click to continue" prompt), NOT `inDialogue()`: the
 * Blast Furnace routine drives its OWN option menus (the coffer's Deposit prompt, the foreman's Yes/No fee),
 * so a blanket `inDialogue()` guard would fire on those menus and consume the tick — deadlocking the very
 * option menus a step is waiting on. `canContinue()` only fires on continue prompts (e.g. a level-up), which
 * both routines DO want auto-advanced; this matches the old `SmitherLoop` behaviour verbatim.
 */
fun <C> RoutineBuilder<C>.smitherPrologue(
    ctx: PluginContext,
    lockInput: () -> Boolean,
    stopReason: () -> String?,
    beforeEachExtra: () -> Unit = {},
) {
    beforeEach {
        if (ctx.login().isLoggedIn()) {
            val want = lockInput()
            if (want && !ctx.input().isLocked()) ctx.input().lock()
            else if (!want && ctx.input().isLocked()) ctx.input().unlock()
            ctx.walking().manageRun()
            beforeEachExtra()
        }
    }
    guard("login", { !ctx.login().isLoggedIn() }) { ctx.login().login(); 1500 }
    guard("yielding", { ctx.coordination().shouldYield() }) { Rng.uniform(1200, 2000) }
    guard("stopping", { stopReason() != null }) {
        PluginLog("smither").i("stopping — ${stopReason()}")
        if (ctx.input().isLocked()) ctx.input().unlock()
        Plugin.NO_LOOP
    }
    guard("break", { ctx.services().get<BreakManager>()?.onBreak() == true }) { Rng.uniform(2000, 5000) }
    guard("dialogue", { ctx.dialogues().canContinue() }) { ctx.dialogues().continueAuto(); Rng.uniform(600, 1000) }
    guard("idle", { Rng.chance(IDLE_CHANCE) }) { Rng.uniform(IDLE_MIN_MS, IDLE_MAX_MS) }
}

private const val IDLE_CHANCE = 0.03
private const val IDLE_MIN_MS = 1500L
private const val IDLE_MAX_MS = 4000L

/**
 * Debounced "still busy at the anvil" gate (was `SmitherLoop.stillWorking`): the smithing animation dips to
 * idle for a beat between hammer swings (each item is several game ticks), so a bare not-animating would look
 * like the batch finished and make the routine re-click the anvil mid-batch. Reads busy while animating, and
 * for up to [debounceMs] after idle first appears — only a sustained idle (batch actually done) reads as
 * not-working.
 */
class IdleGate(private val ctx: PluginContext, private val defaultDebounceMs: Long = 600L) {
    private var idleSinceMs = 0L

    fun isAnimating(): Boolean = (ctx.players().localPlayer()?.animation ?: IDLE) != IDLE

    fun stillBusy(debounceMs: Long = defaultDebounceMs): Boolean {
        if (isAnimating()) { idleSinceMs = 0L; return true }
        if (idleSinceMs == 0L) idleSinceMs = System.currentTimeMillis()
        return System.currentTimeMillis() - idleSinceMs < debounceMs
    }

    private companion object { const val IDLE = -1 }
}

/** Can we stand next to [entity] and interact with it? (was `SmitherLoop.canReach`.) */
fun PluginContext.canReach(entity: SceneEntity): Boolean {
    val tile = entity.tile() ?: return false
    return walking().canReachToInteract(tile)
}
