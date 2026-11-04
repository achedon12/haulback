# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- German and Spanish translations.
- Recipe table covers copper blocks, mud bricks, prismarine, end stone bricks, stained glass and concrete powder.
- Netherite ingots and blocks (ancient debris, smelting, gold) and more Nether blocks in the recipe table.

### Changed
- Piglins are not attacked while the player wears gold armor.
- Code comments, logs and test messages are now in English.

### Fixed
- In-game test commands that were silently ignored.

## [0.2.0] - 2026-11-04

### Added
- Automatic workshop: crafting table and furnaces next to the chests, smelting and crafting of every intermediate a schematic needs, with fuel handling.
- Iron and diamond pickaxes are mined, smelted and crafted when the target blocks require them.
- Schematic modes: Materials, Build or Both.
- `K` key and `/haulback` (`/hb`) command to start, stop and inspect missions.
- English and French translations; the interface follows the game language.

### Fixed
- The last broken block is always picked up before heading back.
- The builder uses the closest orientation when an exact one cannot be placed.

## [0.1.0] - 2026-11-04

### Added
- Block mode and schematic mode (`.litematic`, `.schem`, `.schematic`).
- Multiple chests with stock deduction.
- Tool crafting from scratch (wood and stone).
- Survival: eating, retreating, combat against hostile mobs, maximum radius.
- Human-like camera movement.
- Interface and HUD.

[Unreleased]: https://github.com/achedon12/haulback/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/achedon12/haulback/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/achedon12/haulback/releases/tag/v0.1.0
