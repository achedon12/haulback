package fr.achedon12.haulback;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import fr.achedon12.haulback.job.Target;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Function;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Machine a etats du bot : recolter -> deposer -> (construire) -> rentrer au point A.
 * Tout le deplacement, le minage et la construction sont delegues a Baritone ;
 * cette classe decide quoi faire et quand, et gere la survie (manger, fuir, rentrer).
 */
public final class Collector {

	public enum Phase {IDLE, TOOLS, MINING, PICKUP, EATING, TO_CHEST, AT_CHEST, RESTING, BUILDING, TO_HOME}

	private enum Visit {SURVEY, DEPOSIT, DEPOSIT_FINAL, RESTOCK}

	private static final Set<Item> BAD_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
			Items.PUFFERFISH, Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW, Items.CHICKEN);
	private static final Set<Item> THROWAWAY = Set.of(Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK,
			Items.COBBLED_DEEPSLATE, Items.STONE);

	private static final Collector INSTANCE = new Collector();

	public static Collector get() {
		return INSTANCE;
	}

	private final Minecraft mc = Minecraft.getInstance();

	private Phase phase = Phase.IDLE;
	private Visit visit;
	private Phase resumeAfterEating;
	private int ticks;
	private String status = "En attente";
	private String finishReason;

	private final List<Target> targets = new ArrayList<>();
	private final Map<Item, Integer> startCounts = new HashMap<>();
	private File buildFile;
	private BlockPos buildOrigin;
	private final Set<Item> buildItems = new HashSet<>();
	private BlockPos home;
	private BlockPos chest;
	private final List<BlockPos> chests = new ArrayList<>();
	private final Set<BlockPos> fullChests = new HashSet<>();
	private final List<BlockPos> surveyQueue = new ArrayList<>();
	private Function<Map<Item, Integer>, List<Target>> planner;
	private int menuSeen;
	private long startedAt;
	private long endedAt;
	private boolean endedWell;

	// suivi du minage
	private Set<Block> issuedMine = Set.of();
	private int mineGiveUps;
	private Vec3 stuckRef;
	private int stuckTicks;
	private int stuckCount;
	private boolean restAfterDeposit;
	private boolean buildIssued;

	// suivi du coffre
	private int pathRetries;
	private int openTries;
	private final Set<Integer> triedSlots = new HashSet<>();
	private boolean chestFull;
	private int withdrawn;
	private int restockEmpty;

	// suivi du repas
	private int foodAtStart;
	private int eatAttempts;

	private Collector() {}

	// ------------------------------------------------------------------ API publique

	public Phase phase() {
		return phase;
	}

	public boolean active() {
		return phase != Phase.IDLE;
	}

	public String status() {
		return status;
	}

	public List<Target> targets() {
		return targets;
	}

	public BlockPos home() {
		return home;
	}

	public BlockPos chest() {
		return chest;
	}

	/** Heure de depart (ms), pour le chrono du HUD. */
	public long startedAt() {
		return startedAt;
	}

	/** Heure de fin (ms) de la derniere mission, 0 si aucune. */
	public long endedAt() {
		return endedAt;
	}

	/** Vrai si la derniere mission s'est terminee normalement. */
	public boolean endedWell() {
		return endedWell;
	}

	public boolean hasBuild() {
		return buildFile != null;
	}

	/** Version simple : liste de cibles fixe, deduite du stock des coffres par {@link Target#deduct}. */
	public void start(List<Target> newTargets, File schematic, BlockPos origin, Set<Item> schematicItems) {
		List<Target> copy = new ArrayList<>(newTargets);
		start(stock -> {
			for (Target t : copy) t.deduct(stock);
			return copy;
		}, !newTargets.isEmpty(), schematic, origin, schematicItems);
	}

	/**
	 * @param planner   calcule les cibles a partir du stock des coffres (lu au depart)
	 * @param gather    vrai s'il y a quelque chose a recolter
	 */
	public void start(Function<Map<Item, Integer>, List<Target>> planner, boolean gather, File schematic, BlockPos origin,
					  Set<Item> schematicItems) {
		LocalPlayer p = mc.player;
		if (p == null) return;
		List<BlockPos> list = HaulbackConfig.get().chestList();
		if (list.isEmpty()) {
			say("Choisis d'abord au moins un coffre (vise-le puis touche J ou bouton \"Ajouter\").", ChatFormatting.RED);
			return;
		}
		if (!gather && schematic == null) {
			say("Rien à faire : ajoute des blocs ou choisis un schematic.", ChatFormatting.RED);
			return;
		}
		if (schematic != null && origin == null) {
			say("Choisis l'endroit où construire (bouton \"Ma position\").", ChatFormatting.RED);
			return;
		}
		reset();
		chests.addAll(list);
		this.planner = planner;
		buildFile = schematic;
		buildOrigin = origin;
		if (schematicItems != null) buildItems.addAll(schematicItems);
		home = p.blockPosition();
		startedAt = System.currentTimeMillis();
		snapshotInventory();
		applyBaritoneSettings();
		say("Départ. Point A : " + fmt(home) + ", " + chests.size() + (chests.size() > 1 ? " coffres." : " coffre."), ChatFormatting.GRAY);
		// inventaire des coffres, du plus proche au plus loin
		surveyQueue.clear();
		surveyQueue.addAll(chests);
		goChest(Visit.SURVEY);
	}

	/** Fin de l'inventaire des coffres : on deduit le stock et on lance la suite. */
	private void finishSurvey() {
		LocalPlayer p = mc.player;
		Map<Item, Integer> stock = new HashMap<>(ChestCache.stockOf(chests));
		List<Target> planned = planner.apply(stock);
		targets.clear();
		int saved = 0;
		for (Target t : planned) {
			saved += t.fromStock;
			if (t.needed > 0) targets.add(t);
		}
		if (saved > 0 || planned.size() != targets.size()) {
			say("Stock des coffres pris en compte : " + targets.size() + " objectif(s) restant(s).", ChatFormatting.GRAY);
		}
		if (targets.isEmpty()) {
			if (buildFile != null) {
				say("Tous les matériaux sont déjà dans les coffres.", ChatFormatting.GREEN);
				goChest(Visit.RESTOCK);
			} else {
				finishReason = "Tout est déjà dans les coffres.";
				goHome();
			}
			return;
		}
		if (!enterToolsIfNeeded(p)) {
			if (tools.failure() != null) {
				finishReason = tools.failure();
				goHome();
				return;
			}
			enterMining();
		}
	}

	// ------------------------------------------------------------------ outils

	private final ToolCrafter tools = new ToolCrafter();

	public ToolCrafter tools() {
		return tools;
	}

	/** Passe en phase "outils" si des outils manquent. Renvoie true si c'est le cas. */
	private boolean enterToolsIfNeeded(LocalPlayer p) {
		if (!tools.plan(p, targets)) return false;
		baritone().getPathingBehavior().cancelEverything();
		setPhase(Phase.TOOLS);
		return true;
	}

	private void tickTools(LocalPlayer p) {
		if (needsFood(p) && findFoodSlot(p) >= 0 && p.getFoodData().getFoodLevel() <= 8) {
			startEating(Phase.TOOLS);
			return;
		}
		ToolCrafter.Result r = tools.tick(p);
		status = tools.status();
		if (r == ToolCrafter.Result.DONE) {
			enterMining();
		} else if (r == ToolCrafter.Result.FAILED) {
			if (p.containerMenu != p.inventoryMenu) p.closeContainer();
			finishReason = tools.failure();
			goHome();
		}
	}

	// ------------------------------------------------------------------ combat

	private Mob foe;
	private int fightTicks;

	/**
	 * Mode combat : si un monstre hostile s'approche, on met la tache en pause, on sort l'epee,
	 * on se tourne vers lui et on frappe (en respectant la recharge), puis on reprend.
	 *
	 * @return true si le combat a pris la main ce tick
	 */
	private boolean combat(LocalPlayer p) {
		if (phase == Phase.IDLE || !HaulbackConfig.get().fight) return false;
		Mob m = nearestFoe(p, foe != null ? 7 : 5);
		if (m == null) m = nearestRanged(p, foe != null ? 20 : 16);
		if (m == null || fightTicks > 400) {
			if (foe != null) endFight();
			return false;
		}
		if (foe == null) {
			baritone().getPathingBehavior().cancelEverything();
			releaseKeys();
			if (p.containerMenu != p.inventoryMenu) p.closeContainer();
			fightTicks = 0;
			status = "Se défend contre " + m.getName().getString();
			HaulbackClient.LOG.info("Combat : {} a {} blocs", m.getType().toShortString(), (int) m.distanceTo(p));
		}
		if (foe != m) HaulbackClient.LOG.info("Cible : {} a {} blocs", m.getType().toShortString(), (int) m.distanceTo(p));
		foe = m;
		fightTicks++;
		selectSword(p);
		double d = p.getEyePosition().distanceTo(m.getBoundingBox().getCenter());
		IBaritone b = baritone();
		if (d > 4.5) {
			// tireur a distance : on fonce dessus (Baritone gere le chemin et le regard)
			mc.options.keyUp.setDown(false);
			mc.options.keyDown.setDown(false);
			if (fightTicks % 20 == 1 || !b.getCustomGoalProcess().isActive()) {
				b.getCustomGoalProcess().setGoalAndPath(new GoalNear(m.blockPosition(), 2));
			}
			return true;
		}
		if (b.getPathingBehavior().isPathing()) b.getPathingBehavior().cancelEverything();
		boolean aimed = Look.towards(p, m.getBoundingBox().getCenter());
		// avance vers le monstre s'il est un peu loin, recule devant un creeper qui gonfle
		boolean creeperClose = m instanceof Creeper c && c.getSwellDir() > 0 && d < 3.5;
		mc.options.keyUp.setDown(aimed && d > 2.8 && !creeperClose);
		mc.options.keyDown.setDown(creeperClose);
		if (aimed && d <= 3.0 && p.getAttackStrengthScale(0) >= 0.92f) {
			mc.gameMode.attack(p, m);
			p.swing(InteractionHand.MAIN_HAND);
		}
		return true;
	}

	private Mob nearestFoe(LocalPlayer p, double radius) {
		AABB box = p.getBoundingBox().inflate(radius, 3, radius);
		// les mobs neutres (endermen, piglins zombifies...) ne sont jamais provoques
		List<Mob> mobs = mc.level.getEntitiesOfClass(Mob.class, box,
				e -> e instanceof Enemy && !(e instanceof NeutralMob) && e.isAlive() && p.hasLineOfSight(e)
						&& e.distanceTo(p) <= radius);
		if (mobs.isEmpty()) return null;
		mobs.sort((x, y) -> Double.compare(x.distanceToSqr(p), y.distanceToSqr(p)));
		return mobs.get(0);
	}

	/** Tireurs (squelettes, strays, pillards, blazes...) qui nous voient, meme de loin. */
	private Mob nearestRanged(LocalPlayer p, double radius) {
		AABB box = p.getBoundingBox().inflate(radius, 6, radius);
		List<Mob> mobs = mc.level.getEntitiesOfClass(Mob.class, box,
				e -> e instanceof RangedAttackMob && e instanceof Enemy && !(e instanceof NeutralMob)
						&& e.isAlive() && p.hasLineOfSight(e) && e.distanceTo(p) <= radius);
		if (mobs.isEmpty()) return null;
		mobs.sort((x, y) -> Double.compare(x.distanceToSqr(p), y.distanceToSqr(p)));
		return mobs.get(0);
	}

	private void selectSword(LocalPlayer p) {
		Inventory inv = p.getInventory();
		int sword = -1;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			if (inv.getItem(i).is(ItemTags.SWORDS)) {
				sword = i;
				break;
			}
		}
		if (sword < 0) {
			for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
				if (inv.getItem(i).is(ItemTags.AXES)) {
					sword = i;
					break;
				}
			}
		}
		if (sword < 0) return;
		if (sword >= Inventory.SELECTION_SIZE) {
			mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, sword, 7, ClickType.SWAP, p);
			sword = 7;
		}
		if (inv.getSelectedSlot() != sword) inv.setSelectedSlot(sword);
	}

	/** Fin du combat : on reprend la tache en cours la ou elle en etait. */
	private void endFight() {
		foe = null;
		fightTicks = 0;
		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
		switch (phase) {
			case MINING -> issuedMine = Set.of();
			case PICKUP -> pickupTarget = null;
			case TO_CHEST -> goChest(visit);
			case AT_CHEST -> {
				openTries = 0;
				setPhase(Phase.AT_CHEST);
			}
			case BUILDING -> buildIssued = false;
			case TO_HOME -> goHome();
			case EATING -> ticks = 0;
			default -> {}
		}
	}

	public void stop(String why) {
		if (phase == Phase.IDLE) return;
		baritone().getPathingBehavior().cancelEverything();
		releaseKeys();
		foe = null;
		phase = Phase.IDLE;
		status = why;
		endedAt = System.currentTimeMillis();
		endedWell = false;
		say(why, ChatFormatting.YELLOW);
	}

	/** Appele par l'evenement Fabric quand le joueur casse un bloc. */
	public void onBlockBroken(BlockState state) {
		if (phase != Phase.MINING && phase != Phase.PICKUP && phase != Phase.EATING) return;
		Block b = state.getBlock();
		for (Target t : targets) {
			if (!t.done() && t.sources.contains(b)) {
				t.got++;
				return;
			}
		}
	}

	public void onDisconnect() {
		if (phase != Phase.IDLE) {
			releaseKeys();
			foe = null;
			phase = Phase.IDLE;
			status = "Arrêté (déconnexion)";
		}
	}

	// ------------------------------------------------------------------ boucle

	public void tick() {
		if (phase == Phase.IDLE) return;
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.gameMode == null) {
			onDisconnect();
			return;
		}
		if (p.isDeadOrDying()) {
			stop("Le joueur est mort, arrêt du bot.");
			return;
		}
		ticks++;
		if (combat(p)) return;
		switch (phase) {
			case TOOLS -> tickTools(p);
			case MINING -> tickMining(p);
			case PICKUP -> tickPickup(p);
			case EATING -> tickEating(p);
			case TO_CHEST -> tickToChest(p);
			case AT_CHEST -> tickAtChest(p);
			case RESTING -> tickResting(p);
			case BUILDING -> tickBuilding(p);
			case TO_HOME -> tickToHome(p);
			default -> {}
		}
	}

	// ------------------------------------------------------------------ recolte

	private void enterMining() {
		setPhase(Phase.MINING);
		issuedMine = Set.of();
		stuckRef = null;
		stuckTicks = 0;
	}

	private void tickMining(LocalPlayer p) {
		HaulbackConfig cfg = HaulbackConfig.get();
		if (targets.stream().allMatch(Target::done)) {
			say("Tout est récolté, ramassage puis retour au coffre.", ChatFormatting.GREEN);
			startPickup(Visit.DEPOSIT_FINAL);
			return;
		}
		if (p.getHealth() <= cfg.retreatHealth) {
			say("PV bas (" + (int) p.getHealth() + "), retour à la base pour se soigner.", ChatFormatting.GOLD);
			restAfterDeposit = true;
			goChest(Visit.DEPOSIT);
			return;
		}
		if (needsFood(p)) {
			if (findFoodSlot(p) >= 0) {
				startEating(Phase.MINING);
				return;
			}
			if (p.getFoodData().getFoodLevel() <= 6) {
				finishReason = "Plus de nourriture : récolte interrompue.";
				goChest(Visit.DEPOSIT_FINAL);
				return;
			}
		}
		if (p.getInventory().getFreeSlot() < 0) {
			say("Inventaire plein, aller-retour au coffre.", ChatFormatting.GRAY);
			goChest(Visit.DEPOSIT);
			return;
		}
		if (home != null && Math.sqrt(p.blockPosition().distSqr(home)) > cfg.maxRadius) {
			finishReason = "Rayon maximum (" + cfg.maxRadius + " blocs) atteint : récolte interrompue.";
			goChest(Visit.DEPOSIT_FINAL);
			return;
		}

		if (ticks % 100 == 99 && ToolCrafter.bestPickTier(p) < 0) {
			say("Plus de pioche : fabrication d'une nouvelle.", ChatFormatting.GOLD);
			if (enterToolsIfNeeded(p)) return;
		}

		Set<Block> wanted = new LinkedHashSet<>();
		for (Target t : targets) {
			if (!t.done()) wanted.addAll(t.sources);
		}
		IBaritone b = baritone();
		boolean running = b.getMineProcess().isActive();
		if (!wanted.equals(issuedMine) || !running) {
			if (wanted.equals(issuedMine) && !running && ticks > 40) {
				mineGiveUps++;
				if (mineGiveUps >= 3) {
					finishReason = "Baritone ne trouve plus de blocs à portée : récolte interrompue.";
					goChest(Visit.DEPOSIT_FINAL);
					return;
				}
			}
			b.getMineProcess().mine(0, wanted.toArray(new Block[0]));
			issuedMine = wanted;
			ticks = 0;
		}
		status = "Récolte : " + summary();

		// detection de blocage : pas bouge de 2 blocs en 60 s
		if (stuckRef == null || p.position().distanceTo(stuckRef) > 2) {
			stuckRef = p.position();
			stuckTicks = 0;
		} else if (++stuckTicks > 1200) {
			stuckTicks = 0;
			if (++stuckCount >= 3) {
				finishReason = "Le bot semble bloqué : récolte interrompue.";
				goChest(Visit.DEPOSIT_FINAL);
			} else {
				b.getPathingBehavior().cancelEverything();
				issuedMine = Set.of();
			}
		}
	}

	private String summary() {
		int done = 0, total = 0;
		for (Target t : targets) {
			done += Math.min(t.got, t.needed);
			total += t.needed;
		}
		return done + " / " + total;
	}

	// ------------------------------------------------------------------ ramassage

	private Visit pickupThen;
	private ItemEntity pickupTarget;

	/** Avant de repartir, ramasse les objets tombes autour (la derniere buche, etc.). */
	private void startPickup(Visit then) {
		baritone().getPathingBehavior().cancelEverything();
		pickupThen = then;
		pickupTarget = null;
		setPhase(Phase.PICKUP);
		status = "Ramasse les objets tombés";
	}

	private void tickPickup(LocalPlayer p) {
		if (ticks > 300 || p.getInventory().getFreeSlot() < 0) {
			goChest(pickupThen);
			return;
		}
		if (pickupTarget == null || !pickupTarget.isAlive()) {
			AABB box = p.getBoundingBox().inflate(12, 6, 12);
			List<ItemEntity> items = mc.level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive() && e.onGround());
			if (items.isEmpty()) {
				if (ticks > 10) goChest(pickupThen);
				return;
			}
			items.sort((a, b) -> Double.compare(a.distanceToSqr(p), b.distanceToSqr(p)));
			pickupTarget = items.get(0);
			baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(pickupTarget.blockPosition()));
		}
	}

	// ------------------------------------------------------------------ repas

	private boolean needsFood(LocalPlayer p) {
		return p.getFoodData().getFoodLevel() <= HaulbackConfig.get().eatBelowFood;
	}

	private int findFoodSlot(LocalPlayer p) {
		Inventory inv = p.getInventory();
		int best = -1;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !s.has(DataComponents.FOOD) || BAD_FOOD.contains(s.getItem())) continue;
			if (i < Inventory.SELECTION_SIZE) return i;
			if (best < 0) best = i;
		}
		return best;
	}

	private void startEating(Phase resume) {
		baritone().getPathingBehavior().cancelEverything();
		resumeAfterEating = resume;
		foodAtStart = mc.player.getFoodData().getFoodLevel();
		eatAttempts = 0;
		setPhase(Phase.EATING);
		status = "Pause repas";
	}

	private void tickEating(LocalPlayer p) {
		if (ticks == 1) {
			int slot = findFoodSlot(p);
			if (slot < 0) {
				endEating();
				return;
			}
			if (slot >= Inventory.SELECTION_SIZE) {
				// echange avec la derniere case de la barre rapide
				mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, slot, 8, ClickType.SWAP, p);
				slot = 8;
			}
			p.getInventory().setSelectedSlot(slot);
		}
		if (ticks >= 3) {
			mc.options.keyUse.setDown(true);
		}
		int food = p.getFoodData().getFoodLevel();
		if (food > foodAtStart || ticks > 80) {
			mc.options.keyUse.setDown(false);
			eatAttempts++;
			foodAtStart = food;
			if (needsFood(p) && eatAttempts < 4 && findFoodSlot(p) >= 0) {
				ticks = 0;
			} else {
				endEating();
			}
		}
	}

	private void endEating() {
		releaseKeys();
		Phase next = resumeAfterEating == null ? Phase.MINING : resumeAfterEating;
		if (next == Phase.MINING) {
			enterMining();
		} else if (next == Phase.TOOLS) {
			setPhase(Phase.TOOLS);
		} else if (next == Phase.BUILDING) {
			buildIssued = false;
			setPhase(Phase.BUILDING);
		} else if (next == Phase.TO_CHEST) {
			goChest(visit);
		} else {
			setPhase(next);
		}
	}

	// ------------------------------------------------------------------ coffre

	private void goChest(Visit v) {
		visit = v;
		BlockPos next = pickChest(v);
		if (next == null) {
			if (v == Visit.SURVEY) {
				finishSurvey();
			} else if (v == Visit.RESTOCK) {
				finishReason = "Il manque des matériaux pour la construction dans les coffres.";
				goHome();
			} else {
				finishReason = "Tous les coffres sont pleins.";
				goHome();
			}
			return;
		}
		chest = next;
		baritone().getPathingBehavior().cancelEverything();
		baritone().getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(chest));
		pathRetries = 0;
		setPhase(Phase.TO_CHEST);
		status = switch (v) {
			case SURVEY -> "Inventaire des coffres (" + (chests.size() - surveyQueue.size()) + "/" + chests.size() + ")";
			case RESTOCK -> "Vers le coffre (prendre les matériaux)";
			default -> "Retour au coffre";
		};
	}

	/** Choix du coffre a visiter selon le but de la visite. */
	private BlockPos pickChest(Visit v) {
		LocalPlayer p = mc.player;
		Comparator<BlockPos> byDist = Comparator.comparingDouble(c -> c.distSqr(p.blockPosition()));
		switch (v) {
			case SURVEY -> {
				if (surveyQueue.isEmpty()) return null;
				surveyQueue.sort(byDist);
				return surveyQueue.remove(0);
			}
			case RESTOCK -> {
				BlockPos best = null;
				int bestN = 0;
				for (BlockPos c : chests) {
					int n = 0;
					for (Item it : buildItems) n += ChestCache.count(c, it);
					if (n > bestN) {
						bestN = n;
						best = c;
					}
				}
				return best;
			}
			default -> {
				return chests.stream().filter(c -> !fullChests.contains(c)).min(byDist).orElse(null);
			}
		}
	}

	private boolean nearChest(LocalPlayer p) {
		return p.getEyePosition().distanceTo(Vec3.atCenterOf(chest)) < 4.2;
	}

	private void tickToChest(LocalPlayer p) {
		if (needsFood(p) && findFoodSlot(p) >= 0 && p.getFoodData().getFoodLevel() <= 8) {
			startEating(Phase.TO_CHEST);
			return;
		}
		if (nearChest(p)) {
			baritone().getPathingBehavior().cancelEverything();
			openTries = 0;
			triedSlots.clear();
			chestFull = false;
			withdrawn = 0;
			menuSeen = 0;
			setPhase(Phase.AT_CHEST);
			return;
		}
		IBaritone b = baritone();
		if (!b.getCustomGoalProcess().isActive() && !b.getPathingBehavior().isPathing() && ticks > 40) {
			if (++pathRetries > 6) {
				stop("Impossible d'atteindre le coffre en " + fmt(chest) + ".");
				return;
			}
			b.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(chest));
			ticks = 0;
		}
	}

	private void tickAtChest(LocalPlayer p) {
		AbstractContainerMenu menu = openChestMenu();
		if (menu == null) {
			if (!Look.towards(p, Vec3.atCenterOf(chest)) && ticks < 40) return;
			if (ticks % 20 == 1 || ticks == 40 || openTries == 0) {
				if (++openTries > 6) {
					stop("Le coffre en " + fmt(chest) + " ne s'ouvre pas (bloc manquant ou bloqué ?).");
					return;
				}
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false);
				mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
				p.swing(InteractionHand.MAIN_HAND);
			}
			return;
		}
		if (menuSeen == 0) menuSeen = ticks;
		if (ticks - menuSeen < 4) return; // laisse le serveur envoyer le contenu
		if (visit == Visit.SURVEY) {
			ChestCache.snapshot(chest, menu, p.getInventory());
			p.closeContainer();
			afterChest();
			return;
		}
		status = visit == Visit.RESTOCK ? "Prend les matériaux" : "Range dans le coffre";
		if (ticks % 2 != 0) return; // une action tous les 2 ticks, pour rester discret
		Slot next = nextDeposit(menu, p);
		if (next != null) {
			triedSlots.add(next.index);
			mc.gameMode.handleInventoryMouseClick(menu.containerId, next.index, 0, ClickType.QUICK_MOVE, p);
			return;
		}
		if (visit == Visit.RESTOCK) {
			Slot w = nextWithdraw(menu, p);
			if (w != null) {
				triedSlots.add(w.index);
				withdrawn++;
				mc.gameMode.handleInventoryMouseClick(menu.containerId, w.index, 0, ClickType.QUICK_MOVE, p);
				return;
			}
		}
		// tout ce qui pouvait l'etre a ete deplace : verifie s'il reste des choses a deposer
		chestFull = remainingToDeposit(menu, p);
		ChestCache.snapshot(chest, menu, p.getInventory());
		p.closeContainer();
		afterChest();
	}

	private AbstractContainerMenu openChestMenu() {
		if (mc.screen instanceof AbstractContainerScreen<?> s) {
			AbstractContainerMenu m = s.getMenu();
			if (!(m instanceof InventoryMenu)) return m;
		}
		return null;
	}

	private boolean isPlayerSlot(Slot s, LocalPlayer p) {
		return s.container == p.getInventory() && s.getContainerSlot() < Inventory.INVENTORY_SIZE;
	}

	private boolean keep(ItemStack s) {
		return s.isDamageableItem() || s.has(DataComponents.FOOD) || s.has(DataComponents.EQUIPPABLE)
				|| s.is(Items.TOTEM_OF_UNDYING);
	}

	private int baseline(Item it) {
		int start = startCounts.getOrDefault(it, 0);
		if (visit == Visit.DEPOSIT && THROWAWAY.contains(it)) {
			return Math.max(start, HaulbackConfig.get().keepThrowaway);
		}
		return start;
	}

	private int total(LocalPlayer p, Item it) {
		int n = 0;
		Inventory inv = p.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(it)) n += s.getCount();
		}
		return n;
	}

	private boolean shouldDeposit(ItemStack s, LocalPlayer p) {
		if (s.isEmpty() || keep(s)) return false;
		if (visit == Visit.RESTOCK && buildItems.contains(s.getItem())) return false;
		return total(p, s.getItem()) - s.getCount() >= baseline(s.getItem());
	}

	private Slot nextDeposit(AbstractContainerMenu menu, LocalPlayer p) {
		for (Slot s : menu.slots) {
			if (triedSlots.contains(s.index) || !isPlayerSlot(s, p)) continue;
			if (shouldDeposit(s.getItem(), p)) return s;
		}
		return null;
	}

	private boolean remainingToDeposit(AbstractContainerMenu menu, LocalPlayer p) {
		for (Slot s : menu.slots) {
			if (isPlayerSlot(s, p) && shouldDeposit(s.getItem(), p)) return true;
		}
		return false;
	}

	private Slot nextWithdraw(AbstractContainerMenu menu, LocalPlayer p) {
		int free = 0;
		Inventory inv = p.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			if (inv.getItem(i).isEmpty()) free++;
		}
		if (free <= 2) return null;
		for (Slot s : menu.slots) {
			if (triedSlots.contains(s.index) || s.container == p.getInventory()) continue;
			if (!s.getItem().isEmpty() && buildItems.contains(s.getItem().getItem())) return s;
		}
		return null;
	}

	private void afterChest() {
		if (chestFull) fullChests.add(chest);
		switch (visit) {
			case SURVEY -> goChest(Visit.SURVEY);
			case DEPOSIT -> {
				if (chestFull) {
					say("Coffre plein, passage au suivant.", ChatFormatting.GRAY);
					goChest(Visit.DEPOSIT);
				} else if (restAfterDeposit) {
					restAfterDeposit = false;
					setPhase(Phase.RESTING);
				} else {
					enterMining();
				}
			}
			case DEPOSIT_FINAL -> {
				if (chestFull) {
					goChest(Visit.DEPOSIT_FINAL);
					return;
				}
				if (buildFile != null && finishReason == null) {
					goChest(Visit.RESTOCK);
				} else {
					goHome();
				}
			}
			case RESTOCK -> {
				boolean haveAny = buildItems.stream().anyMatch(it -> total(mc.player, it) > 0);
				if (withdrawn == 0 && !haveAny) {
					finishReason = "Il n'y a plus de matériaux pour la construction dans les coffres.";
					goHome();
					return;
				}
				restockEmpty = withdrawn == 0 ? restockEmpty + 1 : 0;
				if (restockEmpty >= 2) {
					finishReason = "Construction en pause : il manque des matériaux dans le coffre.";
					goHome();
					return;
				}
				buildIssued = false;
				setPhase(Phase.BUILDING);
			}
		}
	}

	private void tickResting(LocalPlayer p) {
		if (needsFood(p) && findFoodSlot(p) >= 0) {
			startEating(Phase.RESTING);
			return;
		}
		status = "Repos à la base (" + (int) p.getHealth() + " PV)";
		if (p.getHealth() >= 16 || ticks > 2400) {
			enterMining();
		}
	}

	// ------------------------------------------------------------------ construction

	private void tickBuilding(LocalPlayer p) {
		IBaritone b = baritone();
		if (!buildIssued) {
			if (!b.getBuilderProcess().build("haulback", buildFile, buildOrigin)) {
				stop("Schematic illisible par Baritone : " + buildFile.getName());
				return;
			}
			buildIssued = true;
			ticks = 0;
			return;
		}
		status = "Construction en cours";
		if (p.getHealth() <= HaulbackConfig.get().retreatHealth && needsFood(p) && findFoodSlot(p) >= 0) {
			startEating(Phase.BUILDING);
			return;
		}
		if (needsFood(p) && findFoodSlot(p) >= 0) {
			startEating(Phase.BUILDING);
			return;
		}
		if (ticks < 40) return;
		if (b.getBuilderProcess().isPaused()) {
			say("Il manque des blocs, passage au coffre.", ChatFormatting.GRAY);
			goChest(Visit.RESTOCK);
			return;
		}
		if (!b.getBuilderProcess().isActive()) {
			finishReason = "Construction terminée.";
			goHome();
		}
	}

	// ------------------------------------------------------------------ retour

	private void goHome() {
		baritone().getPathingBehavior().cancelEverything();
		baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(home));
		pathRetries = 0;
		setPhase(Phase.TO_HOME);
		status = "Retour au point A";
	}

	private void tickToHome(LocalPlayer p) {
		Vec3 c = Vec3.atBottomCenterOf(home);
		if (p.position().distanceTo(c) < 1.5) {
			baritone().getPathingBehavior().cancelEverything();
			String msg = finishReason != null ? finishReason : "Mission terminée.";
			phase = Phase.IDLE;
			status = msg;
			endedAt = System.currentTimeMillis();
			endedWell = finishReason == null || finishReason.startsWith("Construction termin");
			say(msg + " Revenu au point A.", ChatFormatting.GREEN);
			return;
		}
		IBaritone b = baritone();
		if (!b.getCustomGoalProcess().isActive() && !b.getPathingBehavior().isPathing() && ticks > 40) {
			if (++pathRetries > 6) {
				stop("Impossible de revenir au point A " + fmt(home) + ".");
				return;
			}
			b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(home));
			ticks = 0;
		}
	}

	// ------------------------------------------------------------------ utilitaires

	private void reset() {
		targets.clear();
		startCounts.clear();
		buildItems.clear();
		buildFile = null;
		buildOrigin = null;
		finishReason = null;
		mineGiveUps = 0;
		stuckCount = 0;
		restockEmpty = 0;
		restAfterDeposit = false;
		chests.clear();
		fullChests.clear();
		surveyQueue.clear();
		buildIssued = false;
	}

	private void setPhase(Phase p) {
		phase = p;
		ticks = 0;
	}

	private void snapshotInventory() {
		Inventory inv = mc.player.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) startCounts.merge(s.getItem(), s.getCount(), Integer::sum);
		}
	}

	private static IBaritone baritone() {
		return BaritoneAPI.getProvider().getPrimaryBaritone();
	}

	private static void applyBaritoneSettings() {
		Settings s = BaritoneAPI.getSettings();
		s.allowBreak.value = true;
		s.allowPlace.value = true;
		s.allowInventory.value = true;
		s.autoTool.value = true;
		s.allowParkour.value = false;
		s.allowParkourPlace.value = false;
		s.assumeWalkOnLava.value = false;
		s.allowDiagonalAscend.value = false;
		s.avoidance.value = true;
		s.mobAvoidanceRadius.value = 8;
		s.maxFallHeightNoWater.value = 3;
		s.mineScanDroppedItems.value = true;
		s.exploreForBlocks.value = true;
		s.legitMine.value = false;
		s.pauseMiningForFallingBlocks.value = true;
		s.antiCheatCompatibility.value = true;
		// comportement "humain" : la camera suit la marche et le minage, rotations lissees
		s.freeLook.value = false;
		s.blockFreeLook.value = false;
		s.smoothLook.value = true;
		s.smoothLookTicks.value = 7;
		s.randomLooking.value = 0.35;
		s.remainWithExistingLookDirection.value = true;
		s.allowSprint.value = true;
		List<Block> protect = new ArrayList<>(s.blocksToDisallowBreaking.value);
		for (Block b : new Block[]{Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST}) {
			if (!protect.contains(b)) protect.add(b);
		}
		s.blocksToDisallowBreaking.value = protect;
	}

	private void releaseKeys() {
		mc.options.keyUse.setDown(false);
		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
	}

	private void lookAt(LocalPlayer p, Vec3 target) {
		Vec3 eye = p.getEyePosition();
		double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		p.setYRot((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f);
		p.setXRot((float) -(Mth.atan2(dy, h) * Mth.RAD_TO_DEG));
	}

	private static String fmt(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	public void say(String msg, ChatFormatting color) {
		if (mc.player == null) return;
		mc.player.displayClientMessage(Component.literal("[Haulback] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(msg).withStyle(color)), false);
	}
}
