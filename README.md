<p align="center"><img src="icon.png" width="96" alt=""></p>

<h1 align="center">Haulback</h1>

<p align="center">
Mod Fabric client pour Minecraft 1.21.11, basé sur <a href="https://github.com/cabaletta/baritone">Baritone</a>.<br>
Choisis des blocs ou un schematic : il les récolte, les range dans tes coffres, construit si tu veux, puis revient au point de départ.
</p>

---

## Ce qu'il fait

- **Mode blocs** : choisis un ou plusieurs blocs dans une grille d'icônes et une quantité, le bot va les miner.
- **Mode schematic** (`.litematic`, `.schem`, `.schematic`) : le mod lit la liste exacte des matériaux, remonte aux matières premières (planches vers bûches, verre vers sable, escaliers vers blocs de base...), puis les récolte.
- **Construction** : après la récolte (ou directement), Baritone construit le schematic à l'endroit choisi en prenant les blocs dans tes coffres.
- **Plusieurs coffres** : au départ, le bot fait le tour des coffres choisis, lit leur contenu et **déduit ce qui est déjà en stock**, y compris les intermédiaires (des planches en stock réduisent les bûches à couper).
- **Outils** : s'il lui manque pioche, hache, épée ou pelle, il les fabrique (bûches, planches, bâtons, établi, pioche en bois, pierre, outils en pierre). Un outil déjà présent n'est jamais refait. Le choix des outils et du matériau se fait dans l'onglet Options.
- **Survie** : il mange quand il a faim, rentre se soigner si ses PV baissent, se défend contre les monstres (y compris les squelettes qui tirent de loin) et ne provoque jamais les mobs neutres.
- **Comportement naturel** : la caméra suit la marche et le minage, avec des rotations lissées.
- **Retour au point A** à la fin, avec un rayon maximum pour ne jamais se perdre.

## Captures

| Choix des blocs | Schematic | Options |
|---|---|---|
| ![](docs/screenshots/blocs.png) | ![](docs/screenshots/schematic.png) | ![](docs/screenshots/options.png) |

| HUD en récolte | Fabrication des outils | Fin de mission |
|---|---|---|
| ![](docs/screenshots/hud-recolte.png) | ![](docs/screenshots/hud-outils.png) | ![](docs/screenshots/hud-fin.png) |

## Installation

1. Fabric Loader 0.18.2+ et Fabric API pour Minecraft 1.21.11.
2. [Baritone **API** Fabric 1.17.0](https://github.com/cabaletta/baritone/releases/tag/v1.17.0) : le fichier `baritone-api-fabric-1.17.0.jar`. La version « standalone » ne convient pas, Haulback a besoin de l'API.
3. Le jar de Haulback depuis les [releases](https://github.com/achedon12/haulback/releases).

Les deux jars vont dans le dossier `mods`. Avec Lunar Client, ajoute-les au profil Fabric (dossier `mods/fabric-1.21.11` du profil).

Le mod est **uniquement côté client** : rien à installer sur le serveur.

## Utilisation

| Touche | Action |
|---|---|
| `H` | Ouvrir l'interface |
| `J` | Ajouter ou retirer le coffre visé |

1. Vise un coffre et appuie sur `J` (répète pour en ajouter d'autres).
2. Ouvre l'interface avec `H`, choisis des blocs ou un schematic.
3. Pour construire, place-toi à l'origine voulue et clique « Ma position ».
4. Lance. Le HUD en haut à gauche suit la mission ; « Arrêter » dans l'interface (ou `#stop` de Baritone) l'interrompt.

Les schematics se placent dans le dossier `schematics` du jeu (bouton « Dossier » dans l'interface).

## Limites connues

- Les blocs qui demandent une pioche en fer ou mieux (diamant, or, redstone...) exigent que tu aies déjà cette pioche : le bot ne fait pas encore fondre le fer.
- La fabrication des blocs transformés (planches, verre, briques...) pour la construction reste à faire à la main : l'interface indique ce qu'il faut fabriquer.
- Baritone mine les blocs qu'il trouve : sur un serveur partagé, il peut casser une construction faite des mêmes blocs. Garde un rayon raisonnable.
- Un bot reste un bot : vérifie que les règles de ton serveur l'autorisent.

## Compiler

```bash
./gradlew build                # jar dans build/libs
./gradlew runClientGameTest    # scénarios de bout en bout dans un vrai client
```

Les tests en jeu couvrent : fabrication des outils sans rien au départ, récolte avec déduction du stock de deux coffres, construction d'un schematic, combat contre un zombie et un squelette.

## Licence

LGPL-3.0, comme Baritone.
