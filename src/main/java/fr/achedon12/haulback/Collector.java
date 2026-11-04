package fr.achedon12.haulback;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import fr.achedon12.haulback.job.Materials;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Comparator;
import java.util.function.Function;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bot state machine: gather -> store -> (build) -> return to point A.
 * All movement, mining and building is delegated to Baritone;
 * this class decides what to do and when, and handles survival (eat, flee, go home).
 */
public final class Collector {

	public enum Phase {IDLE, TOOLS, WORKSHOP, MINING, PICKUP, EATING, TO_CHEST, AT_CHEST, RESTING, BUILDING, TO_HOME}

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
	private String status = L.t("haulback.status.waiting");
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
	private Function<Map<Item, Integer>, MissionPlan> planner;
	private int menuSeen;
	private long startedAt;
	private long endedAt;
	private boolean buildDone;
	private boolean endedWell;

	// mining tracking
	private Set<Block> issuedMine = Set.of();
	private int mineGiveUps;
	private Vec3 stuckRef;
	private int stuckTicks;
	private int stuckCount;
	private boolean restAfterDeposit;
	private boolean buildIssued;

	// chest tracking
	private int pathRetries;
	private int openTries;
	private final Set<Integer> triedSlots = new HashSet<>();
	private boolean chestFull;
	private int withdrawn;
	private int restockEmpty;

	// eating tracking
	private int foodAtStart;
	private int eatAttempts;

	private Collector() {}

	// ------------------------------------------------------------------ public API

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

	/** Start time (ms), for the HUD timer. */
	public long startedAt() {
		return startedAt;
	}

	/** End time (ms) of the last mission, 0 if none. */
	public long endedAt() {
		return endedAt;
	}

	/** True if the last mission ended normally. */
	public boolean endedWell() {
		return endedWell;
	}

	public boolean hasBuild() {
		return buildFile != null;
	}

	/** What to do after the chest survey: blocks to break, then workshop steps. */
	public record MissionPlan(List<Target> targets, List<Materials.Step> steps) {}

	/** Simple version: fixed target list, minus the chest stock via {@link Target#deduct}. */
	public void start(List<Target> newTargets, File schematic, BlockPos origin, Set<Item> schematicItems) {
		List<Target> proto = new ArrayList<>();
		for (Target t : newTargets) proto.add(t.copy());
		start(stock -> {
			List<Target> fresh = new ArrayList<>();
			for (Target t : proto) {
				Target c = t.copy();
				c.deduct(stock);
				fresh.add(c);
			}
			return new MissionPlan(fresh, List.of());
		}, !newTargets.isEmpty(), schematic, origin, schematicItems);
	}

	/**
	 * @param planner   computes the targets from the chest stock (read at start)
	 * @param gather    true if there is something to gather
	 */
	public void start(Function<Map<Item, Integer>, MissionPlan> planner, boolean gather, File schematic, BlockPos origin,
					  Set<Item> schematicItems) {
		LocalPlayer p = mc.player;
		if (p == null) return;
		List<BlockPos> list = HaulbackConfig.get().chestList();
		if (list.isEmpty()) {
			say(L.t("haulback.msg.need_chest"), ChatFormatting.RED);
			return;
		}
		if (!gather && schematic == null) {
			say(L.t("haulback.msg.nothing_to_do"), ChatFormatting.RED);
			return;
		}
		if (schematic != null && origin == null) {
			say(L.t("haulback.msg.need_origin"), ChatFormatting.RED);
			return;
		}
		lastStart = () -> start(planner, gather, schematic, origin, schematicItems);
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
		say(L.t("haulback.msg.start") + fmt(home) + ", " + chests.size() + (chests.size() > 1 ? L.t("haulback.msg.chests_dot") : L.t("haulback.msg.chest_dot")), ChatFormatting.GRAY);
		// survey the chests, nearest first
		surveyQueue.clear();
		surveyQueue.addAll(chests);
		goChest(Visit.SURVEY);
	}

	private Runnable lastStart;

	public boolean hasLastMission() {
		return lastStart != null;
	}

	/** Restarts the last mission started from the interface. */
	public boolean relaunch() {
		if (lastStart == null) {
			say(L.t("haulback.msg.no_last_mission"), ChatFormatting.RED);
			return false;
		}
		if (active()) return false;
		lastStart.run();
		return active();
	}

	/** Start/stop key: stops if running, otherwise restarts the last mission. */
	public void toggle() {
		if (active()) stop(L.t("haulback.msg.stopped_by_user"));
		else relaunch();
	}

	/** End of the chest survey: subtract the stock and prepare the stages. */
	private void finishSurvey() {
		LocalPlayer p = mc.player;
		Map<Item, Integer> stock = new HashMap<>(ChestCache.stockOf(chests));
		MissionPlan plan = planner.apply(stock);
		List<Target> main = new ArrayList<>();
		int saved = 0;
		for (Target t : plan.targets()) {
			saved += t.fromStock;
			if (t.needed > 0) main.add(t);
		}
		if (saved > 0 || plan.targets().size() != main.size()) {
			say(L.t("haulback.msg.stock_used") + main.size() + L.t("haulback.msg.goals_left"), ChatFormatting.GRAY);
		}

		stages.clear();
		// iron / diamond pickaxe when the blocks require it: ore + coal, furnace, then workshop
		int required = ToolCrafter.requiredTier(main);
		int have = ToolCrafter.bestPickTier(p);
		tools.extraSticks = 0;
		if (!main.isEmpty()) stages.add(new Stage(StageKind.TOOLS, List.of(), List.of()));
		if (required >= 2 && have < 2) {
			tools.extraSticks += 2;
			say(L.t("haulback.msg.need_iron_pick"), ChatFormatting.GRAY);
			Map<Item, Integer> st = new HashMap<>(stock);
			List<Target> iron = new ArrayList<>();
			Target ore = new Target(Items.RAW_IRON.getName().getString(), new ItemStack(Items.RAW_IRON),
					Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), 3);
			int ingots = st.getOrDefault(Items.IRON_INGOT, 0);
			ore.needed = Math.max(0, 3 - ingots);
			ore.stockItem = Items.RAW_IRON;
			ore.deduct(st);
			if (ore.needed > 0) iron.add(ore);
			int coalHave = st.getOrDefault(Items.COAL, 0) + st.getOrDefault(Items.CHARCOAL, 0);
			if (coalHave == 0 && ingots < 3) {
				iron.add(new Target(Items.COAL.getName().getString(), new ItemStack(Items.COAL),
						Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE), 1));
			}
			if (!iron.isEmpty()) stages.add(new Stage(StageKind.GATHER, iron, List.of()));
			List<Materials.Step> ironSteps = new ArrayList<>();
			if (ingots < 3) ironSteps.add(new Materials.Step(Items.IRON_INGOT, 3 - ingots, true, Map.of(Items.RAW_IRON, 1.0)));
			ironSteps.add(new Materials.Step(Items.IRON_PICKAXE, 1, false, Map.of(Items.IRON_INGOT, 3.0, Items.STICK, 2.0)));
			stages.add(new Stage(StageKind.PRODUCE, List.of(), ironSteps));
		}
		if (required >= 3 && have < 3) {
			tools.extraSticks += 2;
			say(L.t("haulback.msg.need_diamond_pick"), ChatFormatting.GRAY);
			int dia = stock.getOrDefault(Items.DIAMOND, 0);
			if (dia < 3) {
				stages.add(new Stage(StageKind.GATHER, List.of(new Target(Items.DIAMOND.getName().getString(),
						new ItemStack(Items.DIAMOND), Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE), 3 - dia)), List.of()));
			}
			stages.add(new Stage(StageKind.PRODUCE, List.of(), List.of(
					new Materials.Step(Items.DIAMOND_PICKAXE, 1, false, Map.of(Items.DIAMOND, 3.0, Items.STICK, 2.0)))));
		}
		if (!main.isEmpty()) stages.add(new Stage(StageKind.GATHER, main, List.of()));
		if (!plan.steps().isEmpty()) stages.add(new Stage(StageKind.PRODUCE, List.of(), plan.steps()));
		if (buildFile != null) stages.add(new Stage(StageKind.BUILD, List.of(), List.of()));
		if (main.isEmpty() && plan.steps().isEmpty() && buildFile == null) {
			finishReason = L.t("haulback.msg.all_in_stock");
		}
		stages.add(new Stage(StageKind.HOME, List.of(), List.of()));
		// targets shown right away: those of the main gathering
		targets.clear();
		targets.addAll(main);
		nextStage();
	}

	// ------------------------------------------------------------------ stages

	private enum StageKind {TOOLS, GATHER, PRODUCE, BUILD, HOME}

	private record Stage(StageKind kind, List<Target> targets, List<Materials.Step> steps) {}

	private final Deque<Stage> stages = new ArrayDeque<>();
	private Stage stage;
	private final Workshop workshop = new Workshop();
	private boolean toolsMidGather;

	public Workshop workshop() {
		return workshop;
	}

	/** Moves on to the next stage of the mission. */
	private void nextStage() {
		LocalPlayer p = mc.player;
		stage = stages.poll();
		if (stage == null) {
			goHome();
			return;
		}
		switch (stage.kind()) {
			case TOOLS -> {
				toolsMidGather = false;
				List<Target> all = new ArrayList<>(targets);
				if (!enterToolsIfNeeded(p, all)) {
					if (tools.failure() != null) {
						finishReason = tools.failure();
						goHome();
					} else {
						nextStage();
					}
				}
			}
			case GATHER -> {
				targets.clear();
				targets.addAll(stage.targets());
				mineGiveUps = 0;
				stuckCount = 0;
				if (targets.isEmpty()) nextStage();
				else enterMining();
			}
			case PRODUCE -> {
				baritone().getPathingBehavior().cancelEverything();
				workshop.start(stage.steps(), chests);
				setPhase(Phase.WORKSHOP);
				status = L.t("haulback.status.workshop");
			}
			case BUILD -> goChest(Visit.RESTOCK);
			case HOME -> goHome();
		}
	}

	private void tickWorkshop(LocalPlayer p) {
		if (needsFood(p) && findFoodSlot(p) >= 0 && p.getFoodData().getFoodLevel() <= 8 && mc.screen == null) {
			startEating(Phase.WORKSHOP);
			return;
		}
		Workshop.Result r = workshop.tick(p);
		status = workshop.status();
		if (r == Workshop.Result.DONE) {
			nextStage();
		} else if (r == Workshop.Result.FAILED) {
			if (p.containerMenu != p.inventoryMenu) p.closeContainer();
			finishReason = workshop.failure();
			goHome();
		}
	}

	// ------------------------------------------------------------------ tools

	private final ToolCrafter tools = new ToolCrafter();

	public ToolCrafter tools() {
		return tools;
	}

	/** Enters the "tools" phase if tools are missing. Returns true if so. */
	private boolean enterToolsIfNeeded(LocalPlayer p) {
		return enterToolsIfNeeded(p, targets);
	}

	private boolean enterToolsIfNeeded(LocalPlayer p, List<Target> forTargets) {
		if (!tools.plan(p, forTargets)) return false;
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
			if (toolsMidGather) {
				toolsMidGather = false;
				enterMining();
			} else {
				nextStage();
			}
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
	 * Combat mode: when a hostile mob gets close, the current task is paused, the sword is drawn,
	 * the bot turns to face it and attacks (respecting the cooldown), then resumes.
	 *
	 * @return true if combat took over this tick
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
			status = L.t("haulback.status.fighting") + m.getName().getString();
			HaulbackClient.LOG.info("Combat: {} at {} blocks", m.getType().toShortString(), (int) m.distanceTo(p));
		}
		if (foe != m) HaulbackClient.LOG.info("Target: {} at {} blocks", m.getType().toShortString(), (int) m.distanceTo(p));
		foe = m;
		fightTicks++;
		selectSword(p);
		double d = p.getEyePosition().distanceTo(m.getBoundingBox().getCenter());
		IBaritone b = baritone();
		if (d > 4.5) {
			// ranged attacker: charge it (Baritone handles the path and the camera)
			mc.options.keyUp.setDown(false);
			mc.options.keyDown.setDown(false);
			if (fightTicks % 20 == 1 || !b.getCustomGoalProcess().isActive()) {
				b.getCustomGoalProcess().setGoalAndPath(new GoalNear(m.blockPosition(), 2));
			}
			return true;
		}
		if (b.getPathingBehavior().isPathing()) b.getPathingBehavior().cancelEverything();
		boolean aimed = Look.towards(p, m.getBoundingBox().getCenter());
		// step towards the mob if it is a bit far, back off from a swelling creeper
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
		// neutral mobs (endermen, zombified piglins...) are never provoked
		List<Mob> mobs = mc.level.getEntitiesOfClass(Mob.class, box,
				e -> e instanceof Enemy && !(e instanceof NeutralMob) && !peacefulPiglin(p, e) && e.isAlive()
						&& p.hasLineOfSight(e) && e.distanceTo(p) <= radius);
		if (mobs.isEmpty()) return null;
		mobs.sort((x, y) -> Double.compare(x.distanceToSqr(p), y.distanceToSqr(p)));
		return mobs.get(0);
	}

	/**
	 * Piglins leave a player wearing gold armor alone; hitting one would turn the whole group hostile.
	 * Piglin brutes are always hostile.
	 */
	private static boolean peacefulPiglin(LocalPlayer p, Mob e) {
		if (!(e instanceof AbstractPiglin) || e instanceof PiglinBrute) return false;
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			if (p.getItemBySlot(slot).is(ItemTags.PIGLIN_SAFE_ARMOR)) return true;
		}
		return false;
	}

	/** Ranged attackers (skeletons, strays, pillagers, blazes...) that can see us, even from afar. */
	private Mob nearestRanged(LocalPlayer p, double radius) {
		AABB box = p.getBoundingBox().inflate(radius, 6, radius);
		List<Mob> mobs = mc.level.getEntitiesOfClass(Mob.class, box,
				e -> e instanceof RangedAttackMob && e instanceof Enemy && !(e instanceof NeutralMob) && !peacefulPiglin(p, e)
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

	/** End of combat: resume the current task where it left off. */
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

	/** Called by the Fabric event when the player breaks a block. */
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
			status = L.t("haulback.status.disconnected");
		}
	}

	// ------------------------------------------------------------------ main loop

	public void tick() {
		if (phase == Phase.IDLE) return;
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.gameMode == null) {
			onDisconnect();
			return;
		}
		if (p.isDeadOrDying()) {
			stop(L.t("haulback.msg.died"));
			return;
		}
		ticks++;
		if (combat(p)) return;
		switch (phase) {
			case TOOLS -> tickTools(p);
			case WORKSHOP -> tickWorkshop(p);
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

	// ------------------------------------------------------------------ gathering

	private void enterMining() {
		setPhase(Phase.MINING);
		issuedMine = Set.of();
		stuckRef = null;
		stuckTicks = 0;
	}

	private void tickMining(LocalPlayer p) {
		HaulbackConfig cfg = HaulbackConfig.get();
		if (targets.stream().allMatch(Target::done)) {
			say(L.t("haulback.msg.all_gathered"), ChatFormatting.GREEN);
			startPickup(Visit.DEPOSIT_FINAL);
			return;
		}
		if (p.getHealth() <= cfg.retreatHealth) {
			say(L.t("haulback.msg.low_hp") + (int) p.getHealth() + L.t("haulback.msg.low_hp2"), ChatFormatting.GOLD);
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
				finishReason = L.t("haulback.msg.no_food");
				goChest(Visit.DEPOSIT_FINAL);
				return;
			}
		}
		if (p.getInventory().getFreeSlot() < 0) {
			say(L.t("haulback.msg.inv_full"), ChatFormatting.GRAY);
			goChest(Visit.DEPOSIT);
			return;
		}
		if (home != null && Math.sqrt(p.blockPosition().distSqr(home)) > cfg.maxRadius) {
			finishReason = L.t("haulback.msg.radius") + cfg.maxRadius + L.t("haulback.msg.radius2");
			goChest(Visit.DEPOSIT_FINAL);
			return;
		}

		if (ticks % 100 == 99 && ToolCrafter.bestPickTier(p) < 0) {
			say(L.t("haulback.msg.pick_broke"), ChatFormatting.GOLD);
			tools.extraSticks = 0;
			if (enterToolsIfNeeded(p)) {
				toolsMidGather = true;
				return;
			}
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
					finishReason = L.t("haulback.msg.no_blocks");
					goChest(Visit.DEPOSIT_FINAL);
					return;
				}
			}
			b.getMineProcess().mine(0, wanted.toArray(new Block[0]));
			issuedMine = wanted;
			ticks = 0;
		}
		status = L.t("haulback.status.gathering") + summary();

		// stuck detection: has not moved 2 blocks in 60 s
		if (stuckRef == null || p.position().distanceTo(stuckRef) > 2) {
			stuckRef = p.position();
			stuckTicks = 0;
		} else if (++stuckTicks > 1200) {
			stuckTicks = 0;
			if (++stuckCount >= 3) {
				finishReason = L.t("haulback.msg.stuck");
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

	// ------------------------------------------------------------------ pickup

	private Visit pickupThen;
	private ItemEntity pickupTarget;

	/** Before leaving, pick up nearby drops (the last log, etc.). */
	private void startPickup(Visit then) {
		baritone().getPathingBehavior().cancelEverything();
		pickupThen = then;
		pickupTarget = null;
		setPhase(Phase.PICKUP);
		status = L.t("haulback.status.pickup");
	}

	private void tickPickup(LocalPlayer p) {
		if (ticks > 300 || p.getInventory().getFreeSlot() < 0) {
			goChest(pickupThen);
			return;
		}
		if (pickupTarget == null || !pickupTarget.isAlive()) {
			AABB box = p.getBoundingBox().inflate(12, 6, 12);
			List<ItemEntity> items = mc.level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive());
			if (items.isEmpty()) {
				// give the last broken block's drop time to spawn and fall
				if (ticks > 30) goChest(pickupThen);
				return;
			}
			items.sort((a, b) -> Double.compare(a.distanceToSqr(p), b.distanceToSqr(p)));
			pickupTarget = items.get(0);
			baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(pickupTarget.blockPosition()));
		}
	}

	// ------------------------------------------------------------------ eating

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
		status = L.t("haulback.status.eating");
	}

	private void tickEating(LocalPlayer p) {
		if (ticks == 1) {
			int slot = findFoodSlot(p);
			if (slot < 0) {
				endEating();
				return;
			}
			if (slot >= Inventory.SELECTION_SIZE) {
				// swap with the last hotbar slot
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
		} else if (next == Phase.WORKSHOP) {
			setPhase(Phase.WORKSHOP);
		} else if (next == Phase.BUILDING) {
			buildIssued = false;
			setPhase(Phase.BUILDING);
		} else if (next == Phase.TO_CHEST) {
			goChest(visit);
		} else {
			setPhase(next);
		}
	}

	// ------------------------------------------------------------------ chests

	private void goChest(Visit v) {
		visit = v;
		BlockPos next = pickChest(v);
		if (next == null) {
			if (v == Visit.SURVEY) {
				finishSurvey();
			} else if (v == Visit.RESTOCK) {
				if (buildItems.stream().anyMatch(it -> total(mc.player, it) > 0) && restockEmpty < 2) {
					// nothing in the chests, but we still carry blocks (picked up): keep going
					restockEmpty++;
					buildIssued = false;
					setPhase(Phase.BUILDING);
					return;
				}
				finishReason = L.t("haulback.msg.build_missing");
				goHome();
			} else {
				finishReason = L.t("haulback.msg.all_full");
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
			case SURVEY -> L.t("haulback.status.survey") + (chests.size() - surveyQueue.size()) + "/" + chests.size() + ")";
			case RESTOCK -> L.t("haulback.status.to_chest_restock");
			default -> L.t("haulback.status.to_chest");
		};
	}

	/** Picks the chest to visit depending on the purpose of the visit. */
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
				stop(L.t("haulback.msg.chest_unreachable") + fmt(chest) + ".");
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
					stop(L.t("haulback.msg.chest_at") + fmt(chest) + L.t("haulback.msg.chest_wont_open"));
					return;
				}
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false);
				mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
				p.swing(InteractionHand.MAIN_HAND);
			}
			return;
		}
		if (menuSeen == 0) menuSeen = ticks;
		if (ticks - menuSeen < 4) return; // let the server send the contents
		if (visit == Visit.SURVEY) {
			ChestCache.snapshot(chest, menu, p.getInventory());
			p.closeContainer();
			afterChest();
			return;
		}
		status = visit == Visit.RESTOCK ? L.t("haulback.status.taking") : L.t("haulback.status.storing");
		if (ticks % 2 != 0) return; // one action every 2 ticks, to stay discreet
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
		// everything movable has been moved: check whether anything is left to store
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
					say(L.t("haulback.msg.chest_full_next"), ChatFormatting.GRAY);
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
				if (finishReason == null) {
					nextStage();
				} else {
					goHome();
				}
			}
			case RESTOCK -> {
				boolean haveAny = buildItems.stream().anyMatch(it -> total(mc.player, it) > 0);
				if (withdrawn == 0 && !haveAny) {
					finishReason = L.t("haulback.msg.no_more_build_mats");
					goHome();
					return;
				}
				restockEmpty = withdrawn == 0 ? restockEmpty + 1 : 0;
				if (restockEmpty >= 2) {
					finishReason = L.t("haulback.msg.build_paused");
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
		status = L.t("haulback.status.resting") + (int) p.getHealth() + L.t("haulback.status.hp");
		if (p.getHealth() >= 16 || ticks > 2400) {
			enterMining();
		}
	}

	// ------------------------------------------------------------------ building

	private void tickBuilding(LocalPlayer p) {
		IBaritone b = baritone();
		if (!buildIssued) {
			if (!b.getBuilderProcess().build("haulback", buildFile, buildOrigin)) {
				stop(L.t("haulback.msg.schematic_unreadable") + buildFile.getName());
				return;
			}
			buildIssued = true;
			ticks = 0;
			return;
		}
		status = L.t("haulback.status.building");
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
			// we have the blocks but Baritone cannot find a spot to place them in the right orientation:
			// accept the closest orientation instead of stalling
			boolean haveItems = buildItems.stream().anyMatch(it -> total(p, it) > 0);
			if (haveItems && !BaritoneAPI.getSettings().buildIgnoreDirection.value) {
				say(L.t("haulback.msg.orientation_fallback"), ChatFormatting.GOLD);
				BaritoneAPI.getSettings().buildIgnoreDirection.value = true;
				b.getBuilderProcess().resume();
				ticks = 0;
				return;
			}
			// misplaced blocks may have been broken and dropped: pick them up before the chest
			say(L.t("haulback.msg.build_pickup"), ChatFormatting.GRAY);
			startPickup(Visit.RESTOCK);
			return;
		}
		if (!b.getBuilderProcess().isActive()) {
			finishReason = L.t("haulback.msg.build_done");
			buildDone = true;
			goHome();
		}
	}

	// ------------------------------------------------------------------ return home

	private void goHome() {
		baritone().getPathingBehavior().cancelEverything();
		baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(home));
		pathRetries = 0;
		setPhase(Phase.TO_HOME);
		status = L.t("haulback.status.going_home");
	}

	private void tickToHome(LocalPlayer p) {
		Vec3 c = Vec3.atBottomCenterOf(home);
		if (p.position().distanceTo(c) < 1.5) {
			baritone().getPathingBehavior().cancelEverything();
			String msg = finishReason != null ? finishReason : L.t("haulback.msg.mission_done");
			phase = Phase.IDLE;
			status = msg;
			endedAt = System.currentTimeMillis();
			endedWell = finishReason == null || buildDone;
			say(msg + L.t("haulback.msg.back_home"), ChatFormatting.GREEN);
			return;
		}
		IBaritone b = baritone();
		if (!b.getCustomGoalProcess().isActive() && !b.getPathingBehavior().isPathing() && ticks > 40) {
			if (++pathRetries > 6) {
				stop(L.t("haulback.msg.home_unreachable") + fmt(home) + ".");
				return;
			}
			b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(home));
			ticks = 0;
		}
	}

	// ------------------------------------------------------------------ utilities

	private void reset() {
		targets.clear();
		startCounts.clear();
		buildItems.clear();
		buildFile = null;
		buildOrigin = null;
		finishReason = null;
		buildDone = false;
		mineGiveUps = 0;
		stuckCount = 0;
		restockEmpty = 0;
		restAfterDeposit = false;
		chests.clear();
		fullChests.clear();
		surveyQueue.clear();
		stages.clear();
		stage = null;
		toolsMidGather = false;
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
		s.buildIgnoreDirection.value = false;
		// "human" behavior: the camera follows walking and mining, smoothed rotations
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
