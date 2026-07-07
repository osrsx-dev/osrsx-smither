# osrsx-smither

Smithing plugin for [osrsx](https://github.com/osrsx/osrsx-client) with two modes, mirroring the miner's
normal-vs-Motherlode split:

- **Anvil** — pick a metal, an item and a catalogued location. The bot banks bars, hammers batches out
  at the anvils (quantity **All** through the smithing interface) and re-banks until the bank runs dry
  of bars. The item dropdown is filtered live to what your Smithing level (and world membership) allows.
- **Blast Furnace** — pick a bar. The bot runs the whole furnace cycle: it keeps the **coffer** funded
  (typing the deposit into the chatbox prompt), pays the **foreman** every <10 minutes while Smithing is
  under 60, feeds the **conveyor** (coal loads first for coal-bearing bars, tracked against the furnace's
  varbit-mirrored internal stock), collects from the **bar dispenser** (Ice gloves worn if owned, a bucket
  of water carried otherwise) and banks the bars.

## Config

| Setting | Notes |
| --- | --- |
| Mode | `Anvil` / `Blast Furnace` |
| Metal / Item / Location | Anvil mode. Items are level-filtered; locations come from the curated anvil-with-bank catalogue |
| Bar | Blast Furnace mode; level-filtered |
| Coffer deposit (gp) | How much to put in per top-up (default 100k) |
| Wear ice gloves | Equip from the bank when owned; falls back to a bucket of water |
| Get hammer | Withdraw (or GE-buy) a hammer before anvil work |
| Stop at level / items / minutes | Stop targets |

## Notes & assumptions

- The Blast Furnace routine assumes an **official Blast Furnace world** (the paid dwarves run the pumps;
  the coffer funds them). Solo-running the furnace machinery on other worlds is not modelled.
- Gold with goldsmith gauntlet swapping is not (yet) modelled — gold smelts fine wearing ice gloves.
- Stamina potions are not (yet) used; run management is the engine's standard `manageRun()`.
- Anvil catalogue currently ships Varrock West (the classic F2P bank-anvil pair); the catalogue is
  structured for more sites as their gating (e.g. Prifddinas' quest lock) becomes checkable.

## Dev

```bash
./gradlew build            # compile + tests
./gradlew -t installPlugin # dev loop: rebuild + hot-reload into a running client
```

Requires `io.osrsx:osrsx-api` **0.11.6+** (the `Keyboard` surface used for the coffer's amount prompt).
