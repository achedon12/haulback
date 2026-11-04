# Contributing to Haulback

Thanks for wanting to make Haulback better. Bug reports, translations, docs and code are all welcome.

## Ground rules

- Be kind: this project follows the [Code of Conduct](CODE_OF_CONDUCT.md).
- Open an issue before a large change, so we can agree on the approach first.
- One topic per pull request. Small, focused PRs get reviewed and merged faster.
- Everything in the repository is written in English (code, comments, docs, commit messages). The in-game text lives in translation files, see [Translations](#translations).

## Development setup

You need **JDK 21** and Git. Gradle is provided by the wrapper.

```bash
git clone https://github.com/achedon12/haulback
cd haulback
./gradlew build
```

The mod jar ends up in `build/libs/`.

### Running the game from source

```bash
# once: Baritone ships a nested jar that the dev environment needs extracted
unzip -j libs/baritone-api-fabric-1.17.0.jar META-INF/jars/nether-pathfinder-1.6.jar -d libs

./gradlew runClient
```

### In-game tests

Haulback is tested end to end inside a real Minecraft client, using the Fabric client game test API:

```bash
./gradlew runClientGameTest
```

It opens a client, creates a flat world and plays the scenarios in
[`src/gametest/java/.../HaulbackClientGameTest.java`](src/gametest/java/fr/achedon12/haulback/test/HaulbackClientGameTest.java):
crafting tools from nothing, gathering with stock deduction, building, the automatic workshop, the iron pickaxe chain and combat.
A full run takes about five minutes. Screenshots land in `build/run/clientGameTest/screenshots/`.

**Please run it before opening a PR that touches the bot's behavior**, and add or extend a scenario when you fix a bug or add a feature.

## Project layout

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the pieces fit together. In short:

| Path | What it is |
|---|---|
| `Collector.java` | The mission state machine: survey, tools, gather, workshop, build, home, plus survival and combat. |
| `ToolCrafter.java` | Crafts wooden and stone tools from scratch. |
| `Workshop.java` | Crafting table and furnaces: smelting and crafting steps. |
| `job/Materials.java` | Recipe tree: turns wanted items into blocks to mine and workshop steps. |
| `job/SchematicPlanner.java` | Full plan for a schematic (fuel, furnaces, table included). |
| `ui/` | The interface (`HaulbackScreen`) and the HUD. |
| `assets/haulback/lang/` | Translations. |

## Code style

- Java 21, tabs for indentation (see `.editorconfig`).
- Mojang official mappings.
- Keep classes focused; prefer a small helper over a long method.
- Comments explain *why*, not *what*.
- No user-facing string in Java: use `L.t("haulback.some.key")` and add the key to the language files.

## Translations

All in-game text is in `src/main/resources/assets/haulback/lang/`. `en_us.json` is the reference.

To add a language:

1. Copy `en_us.json` to your locale code, for example `de_de.json` or `es_es.json` ([list of codes](https://minecraft.wiki/w/Language)).
2. Translate the values, keep the keys. Leading and trailing spaces matter: many strings are joined with numbers or names.
3. Open a pull request. No Java change is needed.

To fix a translation, edit the value in the matching file.

## Commit messages

- English, imperative mood: "Add shovel recipe", "Fix pickup of the last drop".
- First line under 72 characters, details in the body if needed.

## Pull requests

1. Fork the repository and create a branch from `develop`.
2. Make your change, with a test scenario when it changes the bot's behavior.
3. Run `./gradlew build` (and `./gradlew runClientGameTest` for behavior changes).
4. Update `CHANGELOG.md` under **Unreleased**.
5. Open the PR and fill in the template.

## Branches and releases

- `develop` is the default branch: pull requests target it.
- `main` holds released code. Merging `develop` into `main` publishes a release automatically:
  the [release workflow](.github/workflows/release.yml) builds the jar, creates the tag and the GitHub release, and uploads the file to CurseForge.
- To release, bump `mod_version` in `gradle.properties` and move the **Unreleased** notes of `CHANGELOG.md` under a new version heading, on `develop`, then merge into `main`.
- If the tag for `mod_version` already exists, a push to `main` publishes nothing.

## Reporting bugs

Use the [bug report form](https://github.com/achedon12/haulback/issues/new?template=bug_report.yml). The most useful things to include are the Minecraft and mod versions, the other mods installed, what the bot was doing (the HUD status), and the end of `logs/latest.log`.

## License

By contributing, you agree that your contributions are licensed under the [LGPL-3.0](LICENSE), like the rest of the project.
