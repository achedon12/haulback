# Architecture

Haulback is a client-side Fabric mod. It never talks to the server directly: it drives the local player the way a human would (movement, clicks, inventory actions), and delegates pathfinding, mining and block placement to [Baritone](https://github.com/cabaletta/baritone) through its API.

```
 HaulbackScreen ──start──▶ Collector ──────────────▶ Baritone API
   (ui/)                    (mission state machine)   (path, mine, build)
                              │    │     │
                ToolCrafter ◀─┘    │     └─▶ Workshop (crafting table, furnaces)
                                   │
                         job/ (planning: Materials, SchematicPlanner, Target)
```

## Mission flow

`Collector` runs one mission at a time, ticked from `ClientTickEvents.END_CLIENT_TICK`. A mission is a queue of **stages** built after the chest survey:

| Stage | Driven by | What happens |
|---|---|---|
| Survey | `Collector` (visit `SURVEY`) | Opens every selected chest, nearest first, and records its contents in `ChestCache`. |
| Tools | `ToolCrafter` | Crafts missing wooden/stone tools by clicking in the inventory and crafting table. |
| Gather | `Collector` (phase `MINING`) | `IMineProcess.mine(...)` on the target blocks, progress counted from block-break events, pickup, deposit. |
| Produce | `Workshop` | Smelting and crafting steps, using the game's recipe book (`handlePlaceRecipe`) and furnace slots. |
| Build | `Collector` (phase `BUILDING`) | `IBuilderProcess.build(...)`, restocking from the chests when Baritone pauses for missing blocks. |
| Home | `Collector` (phase `TO_HOME`) | Walks back to the starting point. |

The iron/diamond pickaxe chain is just extra Gather and Produce stages inserted before the main gathering.

Cross-cutting behavior lives in `Collector.tick()` and runs before the current phase: **combat** (pauses the task, fights, resumes), **eating**, **retreat** on low health, and the **maximum radius** check.

## Planning

`job/Materials` turns "items I want" into "blocks to break" plus "workshop steps":

- `TERMINALS`: raw items and the blocks that drop them (with an average yield, e.g. 4 clay balls per clay block).
- `RECIPES`: a small hand-written recipe table (smelting and crafting) with ingredient ratios.
- Shape rules: `*_stairs`, `*_slab`, `*_wall` resolve to their base block.
- `NATURAL`: blocks that drop themselves.

Resolution walks the tree depth first, consuming the known chest stock at every level, and records workshop steps in post-order (ingredients before products). Anything it cannot resolve is listed as "provide yourself".

`SchematicPlanner` runs it twice: a first pass counts smelting, then fuel, furnaces and a crafting table are added to the wanted list.

The recipe table is only used for **planning quantities**. Actual crafting goes through the player's recipe book, so results always match the game (and modded recipes work as long as the recipe is unlocked).

## Interface

- `ui/HaulbackScreen`: a single `Screen` drawn by hand (no vanilla widgets except the search box). Each frame registers clickable `Hit` regions, and `mouseClicked` dispatches to them.
- `ui/Hud`: the mission card, rendered through Fabric's `HudElementRegistry`.
- `ui/Theme`: the color palette.
- All text goes through `L.t(key)` (`I18n`), with strings in `assets/haulback/lang/`.

## Persistence

`HaulbackConfig` stores settings, the selected chests and the workshop location in `config/haulback.json` (Gson). `ChestCache` is in memory only and is cleared on disconnect.

## Tests

`src/gametest` holds end-to-end scenarios run with the Fabric client game test API (`./gradlew runClientGameTest`). They build small setups in a flat world with server commands, start missions through the same API the interface uses, and assert on the world state (chest contents, placed blocks, remaining mobs).

## Adding things

- **A new recipe or raw material**: add it to `Materials` (`terminal(...)`, `recipe(...)`), then cover it in a test if it changes behavior.
- **A new setting**: field in `HaulbackConfig`, a row in `HaulbackScreen.renderOptionsTab`, keys in the language files.
- **A new phase**: add it to `Collector.Phase`, handle it in `tick()`, `endFight()` and `Hud.phaseLabel`.
