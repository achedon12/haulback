**Haulback** is a client-side Fabric mod for Minecraft 1.21.11 that runs material errands for you. Pick some blocks or a schematic: it gathers the materials, smelts and crafts what the build needs, stores everything in your chests, builds the schematic if you want, then walks back to where it started.

It is built on the Baritone pathfinding library, and plays like a player would: nothing to install on the server.

## Features

- **Block mode**: pick blocks from an icon grid with category filters, set quantities, start.
- **Schematic mode** (`.litematic`, `.schem`, `.schematic`): reads the exact bill of materials and walks the recipe tree down to raw resources. Choose **Materials**, **Build** or **Both**.
- **Automatic workshop**: places a crafting table and furnaces next to your chests, smelts (glass, stone, bricks, ingots) and crafts (planks, sticks, stairs, slabs, panes) everything the build needs, fuel included.
- **Multiple chests**: reads every selected chest first and only fetches what is missing.
- **Tools**: crafts the missing pickaxe, axe, sword or shovel, up to iron and diamond pickaxes when the blocks require them. Tools you already carry are never crafted again.
- **Survival**: eats, retreats to heal, fights hostile mobs (skeletons at range included), never provokes neutral mobs, and always comes home within a set radius.
- **Human-like movement**: the camera follows the path and the block being mined, with smoothed rotations.
- English and French interface.

## Requirements

- Fabric Loader 0.18.2+ and Fabric API
- **Baritone API 1.17.0 for Fabric** (`baritone-api-fabric-1.17.0.jar`, from the official Baritone repository by cabaletta). The standalone build does not work.

## Controls

- `H` opens the interface, `J` adds or removes the chest you are looking at, `K` starts or stops the mission. All rebindable.
- `/haulback` (alias `/hb`): `start`, `stop`, `toggle`, `status`, `open`, `chest add|list|clear`.

## Good to know

- Baritone mines any matching block: on a shared server it can break a build made of the same blocks. Keep the radius sensible.
- It is still a bot: check your server's rules before using it.
- Netherite is out of scope; the bot stops at diamond tools.

Source code, wiki and issue tracker: github.com/achedon12/haulback
