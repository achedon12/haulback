package fr.achedon12.haulback;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import fr.achedon12.haulback.job.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/**
 * Crafts the missing tools before gathering:
 * logs -> planks -> sticks -> crafting table -> wooden pickaxe -> stone -> stone tools.
 * A tool already in the inventory is never crafted again.
 */
public final class ToolCrafter {

	public enum Result {RUNNING, DONE, FAILED}

	private enum Step {GET_LOGS, CRAFT_SMALL, PLACE_TABLE, OPEN_TABLE, CRAFT_TABLE, GET_STONE, TO_TABLE, PICK_TABLE, DONE}

	/** A recipe: grid (width 2 or 3) + ingredient per cell (null = empty). */
	private record Recipe(String name, Item output, int width, List<Predicate<ItemStack>> grid) {
		/** Display name: the produced item's name, otherwise the translation key. */
		String label() {
			return output != Items.AIR ? output.getName().getString() : L.t(name);
		}
	}

	private static final Predicate<ItemStack> LOG = s -> s.is(ItemTags.LOGS);
	private static final Predicate<ItemStack> PLANK = s -> s.is(ItemTags.PLANKS);
	private static final Predicate<ItemStack> STICK = s -> s.is(Items.STICK);
	private static final Predicate<ItemStack> STONE = s -> s.is(ItemTags.STONE_TOOL_MATERIALS);

	private static Recipe r2(String name, Predicate<ItemStack> a, Predicate<ItemStack> b, Predicate<ItemStack> c, Predicate<ItemStack> d) {
		List<Predicate<ItemStack>> g = new ArrayList<>();
		g.add(a); g.add(b); g.add(c); g.add(d);
		return new Recipe(name, Items.AIR, 2, g);
	}

	@SafeVarargs
	private static Recipe r3(String name, Item out, Predicate<ItemStack>... cells) {
		List<Predicate<ItemStack>> g = new ArrayList<>();
		for (Predicate<ItemStack> c : cells) g.add(c);
		return new Recipe(name, out, 3, g);
	}

	private static final Recipe PLANKS = r2("haulback.tool.planks", LOG, null, null, null);
	private static final Recipe STICKS = r2("haulback.tool.sticks", PLANK, null, PLANK, null);
	private static final Recipe TABLE = r2("haulback.tool.table", PLANK, PLANK, PLANK, PLANK);
	private static final Recipe WOOD_PICK = r3(null, Items.WOODEN_PICKAXE, PLANK, PLANK, PLANK, null, STICK, null, null, STICK, null);
	private static final Recipe WOOD_AXE = r3(null, Items.WOODEN_AXE, PLANK, PLANK, null, PLANK, STICK, null, null, STICK, null);
	private static final Recipe WOOD_SWORD = r3(null, Items.WOODEN_SWORD, null, PLANK, null, null, PLANK, null, null, STICK, null);
	private static final Recipe WOOD_SHOVEL = r3(null, Items.WOODEN_SHOVEL, null, PLANK, null, null, STICK, null, null, STICK, null);
	private static final Recipe STONE_PICK = r3(null, Items.STONE_PICKAXE, STONE, STONE, STONE, null, STICK, null, null, STICK, null);
	private static final Recipe STONE_AXE = r3(null, Items.STONE_AXE, STONE, STONE, null, STONE, STICK, null, null, STICK, null);
	private static final Recipe STONE_SWORD = r3(null, Items.STONE_SWORD, null, STONE, null, null, STONE, null, null, STICK, null);
	private static final Recipe STONE_SHOVEL = r3(null, Items.STONE_SHOVEL, null, STONE, null, null, STICK, null, null, STICK, null);

	private final Minecraft mc = Minecraft.getInstance();

	private Step step;
	private String status = "";
	private String failure;
	private int ticks;
	private int retries;

	private boolean woodPick;
	private final List<Recipe> woodRecipes = new ArrayList<>();
	private final List<Recipe> stoneTools = new ArrayList<>();
	private int logsNeeded;
	private int stoneNeeded;
	private int plankCrafts;
	private int stickCrafts;
	private boolean needTable;

	private final Deque<Recipe> queue = new ArrayDeque<>();
	private int craftStage;
	private BlockPos tablePos;

	public String status() {
		return status;
	}

	private final List<Item> planned = new ArrayList<>();

	/** Tools planned for this run (for the HUD). */
	public List<Item> planned() {
		return planned;
	}

	public String failure() {
		return failure;
	}

	// ------------------------------------------------------------------ planning

	/**
	 * Prepares the plan. Returns false if there is nothing to craft.
	 * If an iron (or better) tool is required and missing, {@link #failure()} is set.
	 */
	/** Extra sticks to craft (for a later iron / diamond pickaxe made at the workshop). */
	public int extraSticks;

	/** Pickaxe tier required by the targets: 0 wood, 1 stone, 2 iron, 3 diamond. */
	public static int requiredTier(List<Target> targets) {
		int t = 0;
		for (Target x : targets) for (Block b : x.sources) {
			BlockState st = b.defaultBlockState();
			t = Math.max(t, st.is(BlockTags.NEEDS_DIAMOND_TOOL) ? 3 : st.is(BlockTags.NEEDS_IRON_TOOL) ? 2 : st.is(BlockTags.NEEDS_STONE_TOOL) ? 1 : 0);
		}
		return t;
	}

	public boolean plan(LocalPlayer p, List<Target> targets) {
		failure = null;
		stoneTools.clear();
		queue.clear();
		int requiredTier = 0;
		boolean shovelUseful = false;
		List<String> ironBlocks = new ArrayList<>();
		for (Target t : targets) {
			for (Block b : t.sources) {
				BlockState st = b.defaultBlockState();
				int tier = st.is(BlockTags.NEEDS_DIAMOND_TOOL) ? 3 : st.is(BlockTags.NEEDS_IRON_TOOL) ? 2 : st.is(BlockTags.NEEDS_STONE_TOOL) ? 1 : 0;
				if (tier >= 2 && !ironBlocks.contains(t.label)) ironBlocks.add(t.label);
				requiredTier = Math.max(requiredTier, tier);
				if (st.is(BlockTags.MINEABLE_WITH_SHOVEL)) shovelUseful = true;
			}
		}
		int pickTier = bestPickTier(p);
		// iron and diamond: handled by the workshop after the stone tools (see Collector)
		int stoneNeed = Math.min(requiredTier, 1);
		// a tool already present is never crafted again, unless its tier is too low
		HaulbackConfig cfg = HaulbackConfig.get();
		boolean stoneTier = cfg.toolTier >= 1;
		boolean needPick = (cfg.toolPickaxe && pickTier < 0) || pickTier < stoneNeed || (requiredTier >= 2 && pickTier < 1);
		woodRecipes.clear();
		if (needPick) {
			// stone if requested, or if the blocks require it
			if (stoneTier || requiredTier >= 1) stoneTools.add(STONE_PICK);
			else woodRecipes.add(WOOD_PICK);
		}
		if (cfg.toolAxe && !has(p, s -> s.is(ItemTags.AXES))) (stoneTier ? stoneTools : woodRecipes).add(stoneTier ? STONE_AXE : WOOD_AXE);
		if (cfg.toolSword && !has(p, s -> s.is(ItemTags.SWORDS))) (stoneTier ? stoneTools : woodRecipes).add(stoneTier ? STONE_SWORD : WOOD_SWORD);
		if (cfg.toolShovel && !has(p, s -> s.is(ItemTags.SHOVELS))) (stoneTier ? stoneTools : woodRecipes).add(stoneTier ? STONE_SHOVEL : WOOD_SHOVEL);
		// mining stone needs at least a pickaxe: make a wooden one first
		woodPick = !stoneTools.isEmpty() && pickTier < 0 && !woodRecipes.contains(WOOD_PICK);
		if (woodPick) woodRecipes.add(0, WOOD_PICK);
		if (stoneTools.isEmpty() && woodRecipes.isEmpty() && extraSticks <= 0) return false;

		int sticks = 0, planksIn = 0, stone = 0;
		List<Recipe> all = new ArrayList<>(woodRecipes);
		all.addAll(stoneTools);
		for (Recipe r : all) {
			for (Predicate<ItemStack> c : r.grid) {
				if (c == STICK) sticks++;
				else if (c == PLANK) planksIn++;
				else if (c == STONE) stone++;
			}
		}
		sticks += extraSticks;
		stickCrafts = (int) Math.ceil(Math.max(0, sticks - count(p, STICK)) / 4.0);
		needTable = !all.isEmpty() && !has(p, s -> s.is(Items.CRAFTING_TABLE));
		int planks = (needTable ? 4 : 0) + stickCrafts * 2 + planksIn;
		plankCrafts = (int) Math.ceil(Math.max(0, planks - count(p, PLANK)) / 4.0);
		logsNeeded = Math.max(0, plankCrafts - count(p, LOG));
		stoneNeeded = stone;
		setStep(Step.GET_LOGS);
		planned.clear();
		List<String> names = new ArrayList<>();
		for (Recipe r : all) {
			planned.add(r.output);
			names.add(r.label());
		}
		Collector.get().say(L.t("haulback.msg.tools_to_craft") + String.join(", ", names) + ".", net.minecraft.ChatFormatting.GRAY);
		return true;
	}

	/** -1: no pickaxe, 0: wood/gold, 1: stone/copper, 2: iron, 3: diamond/netherite. */
	static int bestPickTier(LocalPlayer p) {
		int best = -1;
		Inventory inv = p.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.is(ItemTags.PICKAXES)) continue;
			int t;
			if (s.is(Items.NETHERITE_PICKAXE) || s.is(Items.DIAMOND_PICKAXE)) t = 3;
			else if (s.is(Items.IRON_PICKAXE)) t = 2;
			else if (s.is(Items.WOODEN_PICKAXE) || s.is(Items.GOLDEN_PICKAXE)) t = 0;
			else t = 1;
			best = Math.max(best, t);
		}
		return best;
	}

	static boolean has(LocalPlayer p, Predicate<ItemStack> pred) {
		return count(p, pred) > 0;
	}

	static int count(LocalPlayer p, Predicate<ItemStack> pred) {
		int n = 0;
		Inventory inv = p.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && pred.test(s)) n += s.getCount();
		}
		return n;
	}

	// ------------------------------------------------------------------ execution

	public Result tick(LocalPlayer p) {
		ticks++;
		IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
		switch (step) {
			case GET_LOGS -> {
				int have = count(p, LOG);
				status = L.t("haulback.status.tools_logs") + Math.min(have, logsNeeded) + "/" + logsNeeded;
				if (have >= logsNeeded) {
					b.getPathingBehavior().cancelEverything();
					queue.clear();
					for (int i = 0; i < plankCrafts; i++) queue.add(PLANKS);
					for (int i = 0; i < stickCrafts; i++) queue.add(STICKS);
					if (needTable) queue.add(TABLE);
					setStep(Step.CRAFT_SMALL);
					return Result.RUNNING;
				}
				if (!b.getMineProcess().isActive()) {
					if (ticks > 40 && ++retries > 3) return fail(L.t("haulback.msg.no_trees"));
					b.getMineProcess().mine(logsNeeded, logBlocks());
					ticks = 0;
				}
			}
			case CRAFT_SMALL -> {
				status = L.t("haulback.status.tools_crafting_inv");
				Result r = runQueue(p, p.inventoryMenu);
				if (r == Result.DONE && woodRecipes.isEmpty() && stoneTools.isEmpty()) {
					setStep(Step.DONE);
					return Result.DONE;
				}
				if (r == Result.DONE) setStep(Step.PLACE_TABLE);
				return r == Result.FAILED ? Result.FAILED : Result.RUNNING;
			}
			case PLACE_TABLE -> {
				status = L.t("haulback.status.tools_place_table");
				if (ticks == 1) {
					tablePos = placeTable(p);
					if (tablePos == null) {
						if (++retries > 5) return fail(L.t("haulback.msg.cannot_place_table"));
						ticks = -20;
					}
				} else if (ticks > 5) {
					if (mc.level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
						queue.clear();
						queue.addAll(woodRecipes);
						setStep(!woodRecipes.isEmpty() ? Step.OPEN_TABLE : Step.GET_STONE);
					} else {
						if (++retries > 5) return fail(L.t("haulback.msg.cannot_place_table"));
						ticks = -20;
					}
				}
			}
			case OPEN_TABLE -> {
				status = L.t("haulback.status.tools_open_table");
				if (craftingMenu() != null) {
					craftStage = 0;
					setStep(Step.CRAFT_TABLE);
				} else if (Look.towards(p, Vec3.atCenterOf(tablePos)) && ticks % 20 >= 1) {
					if (ticks % 20 == 1 || retries == 0) {
						if (++retries > 6) return fail(L.t("haulback.msg.table_wont_open"));
						useBlock(p, tablePos);
					}
				}
			}
			case CRAFT_TABLE -> {
				status = L.t("haulback.status.tools_crafting_table");
				CraftingMenu menu = craftingMenu();
				if (menu == null) {
					setStep(Step.OPEN_TABLE);
					return Result.RUNNING;
				}
				Result r = runQueue(p, menu);
				if (r == Result.FAILED) return Result.FAILED;
				if (r == Result.DONE) {
					p.closeContainer();
					if (count(p, STONE) < stoneNeeded) {
						setStep(Step.GET_STONE);
					} else if (!stoneTools.isEmpty()) {
						queue.addAll(stoneTools);
						stoneTools.clear();
						setStep(Step.OPEN_TABLE);
					} else {
						setStep(Step.PICK_TABLE);
					}
				}
			}
			case GET_STONE -> {
				int have = count(p, STONE);
				status = L.t("haulback.status.tools_stone") + Math.min(have, stoneNeeded) + "/" + stoneNeeded;
				if (have >= stoneNeeded) {
					b.getPathingBehavior().cancelEverything();
					b.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(tablePos));
					setStep(Step.TO_TABLE);
					return Result.RUNNING;
				}
				if (!b.getMineProcess().isActive()) {
					if (ticks > 40 && ++retries > 3) return fail(L.t("haulback.msg.no_stone"));
					b.getMineProcess().mine(stoneNeeded + 4, Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE,
							Blocks.COBBLED_DEEPSLATE, Blocks.BLACKSTONE);
					ticks = 0;
				}
			}
			case TO_TABLE -> {
				status = L.t("haulback.status.tools_back_to_table");
				if (p.getEyePosition().distanceTo(Vec3.atCenterOf(tablePos)) < 4.2) {
					b.getPathingBehavior().cancelEverything();
					queue.clear();
					queue.addAll(stoneTools);
					stoneTools.clear();
					setStep(Step.OPEN_TABLE);
				} else if (!b.getCustomGoalProcess().isActive() && ticks > 40) {
					if (++retries > 6) return fail(L.t("haulback.msg.table_unreachable"));
					b.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(tablePos));
					ticks = 0;
				}
			}
			case PICK_TABLE -> {
				status = L.t("haulback.status.tools_pick_table");
				if (!mc.level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE) || ticks > 300) {
					mc.gameMode.stopDestroyBlock();
					b.getPathingBehavior().cancelEverything();
					setStep(Step.DONE);
					Collector.get().say(L.t("haulback.msg.tools_ready"), net.minecraft.ChatFormatting.GREEN);
					return Result.DONE;
				}
				// only break OUR crafting table (never another player's)
				if (p.getEyePosition().distanceTo(Vec3.atCenterOf(tablePos)) < 4.5) {
					if (!Look.towards(p, Vec3.atCenterOf(tablePos))) return Result.RUNNING;
					mc.gameMode.continueDestroyBlock(tablePos, Direction.UP);
					p.swing(InteractionHand.MAIN_HAND);
				} else if (!b.getCustomGoalProcess().isActive()) {
					b.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(tablePos));
				}
			}
			case DONE -> {
				return Result.DONE;
			}
		}
		return Result.RUNNING;
	}

	private Result fail(String msg) {
		failure = msg;
		BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
		return Result.FAILED;
	}

	private void setStep(Step s) {
		step = s;
		ticks = 0;
		retries = 0;
		craftStage = 0;
	}

	private static Block[] logBlocks() {
		List<Block> out = new ArrayList<>();
		for (Block b : BuiltInRegistries.BLOCK) {
			if (b.defaultBlockState().is(BlockTags.LOGS) && !BuiltInRegistries.BLOCK.getKey(b).getPath().startsWith("stripped_")) {
				out.add(b);
			}
		}
		return out.toArray(new Block[0]);
	}

	private CraftingMenu craftingMenu() {
		if (mc.screen instanceof AbstractContainerScreen<?> s && s.getMenu() instanceof CraftingMenu m) return m;
		return null;
	}

	// ------------------------------------------------------------------ crafting by clicks

	/**
	 * Runs the recipe queue, one stage per call:
	 * 0 = place ingredients, 1..3 = wait for the server, 4 = take the result, 5 = clear the grid.
	 */
	private Result runQueue(LocalPlayer p, AbstractContainerMenu menu) {
		Recipe r = queue.peek();
		if (r == null) return Result.DONE;
		switch (craftStage) {
			case 0 -> {
				if (!menu.getCarried().isEmpty()) {
					dropCursor(p, menu);
					return Result.RUNNING;
				}
				for (int i = 0; i < r.grid.size(); i++) {
					Predicate<ItemStack> need = r.grid.get(i);
					if (need == null) continue;
					int row = i / r.width, col = i % r.width;
					int gridSlot = 1 + row * (menu instanceof CraftingMenu ? 3 : 2) + col;
					Slot src = findInventorySlot(p, menu, need);
					if (src == null) {
						clearGrid(p, menu);
						return fail(L.t("haulback.msg.missing_ingredient") + r.label() + ".");
					}
					click(p, menu, src.index, 0, ClickType.PICKUP);
					click(p, menu, gridSlot, 1, ClickType.PICKUP);
					click(p, menu, src.index, 0, ClickType.PICKUP);
				}
				craftStage = 1;
			}
			case 1, 2, 3 -> craftStage++;
			case 4 -> {
				if (menu.getSlot(0).hasItem()) {
					click(p, menu, 0, 0, ClickType.QUICK_MOVE);
					craftStage = 5;
				} else if (++retries > 20) {
					clearGrid(p, menu);
					return fail(L.t("haulback.msg.recipe_q") + r.label() + L.t("haulback.msg.recipe_nothing"));
				}
			}
			case 5 -> {
				clearGrid(p, menu);
				queue.poll();
				craftStage = 0;
				retries = 0;
			}
			default -> craftStage = 0;
		}
		return Result.RUNNING;
	}

	private void click(LocalPlayer p, AbstractContainerMenu menu, int slot, int button, ClickType type) {
		mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, p);
	}

	private Slot findInventorySlot(LocalPlayer p, AbstractContainerMenu menu, Predicate<ItemStack> need) {
		for (Slot s : menu.slots) {
			if (s.container == p.getInventory() && s.getContainerSlot() < Inventory.INVENTORY_SIZE
					&& s.hasItem() && need.test(s.getItem())) return s;
		}
		return null;
	}

	private void clearGrid(LocalPlayer p, AbstractContainerMenu menu) {
		int gridSize = menu instanceof CraftingMenu ? 9 : 4;
		for (int i = 1; i <= gridSize; i++) {
			if (menu.getSlot(i).hasItem()) click(p, menu, i, 0, ClickType.QUICK_MOVE);
		}
		dropCursor(p, menu);
	}

	private void dropCursor(LocalPlayer p, AbstractContainerMenu menu) {
		if (menu.getCarried().isEmpty()) return;
		for (Slot s : menu.slots) {
			if (s.container == p.getInventory() && s.getContainerSlot() < Inventory.INVENTORY_SIZE && !s.hasItem()) {
				click(p, menu, s.index, 0, ClickType.PICKUP);
				return;
			}
		}
	}

	// ------------------------------------------------------------------ crafting table

	private BlockPos placeTable(LocalPlayer p) {
		int slot = -1;
		Inventory inv = p.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			if (inv.getItem(i).is(Items.CRAFTING_TABLE)) {
				slot = i;
				break;
			}
		}
		if (slot < 0) return null;
		if (slot >= Inventory.SELECTION_SIZE) {
			mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, slot, 8, ClickType.SWAP, p);
			slot = 8;
		}
		inv.setSelectedSlot(slot);
		BlockPos feet = p.blockPosition();
		Vec3 eye = p.getEyePosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dy = -2; dy <= 1; dy++) {
					BlockPos pos = feet.offset(dx, dy, dz);
					BlockPos below = pos.below();
					if (!mc.level.getBlockState(pos).canBeReplaced()) continue;
					if (!mc.level.getBlockState(below).isFaceSturdy(mc.level, below, Direction.UP)) continue;
					if (new AABB(pos).intersects(p.getBoundingBox())) continue;
					double d = eye.distanceTo(Vec3.atCenterOf(pos));
					if (d > 4.0 || d < 1.2) continue;
					if (d < bestD) {
						bestD = d;
						best = pos;
					}
				}
			}
		}
		if (best != null) {
			BlockPos below = best.below();
			Vec3 hitVec = Vec3.atCenterOf(below).add(0, 0.5, 0);
			lookAt(p, hitVec);
			mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, Direction.UP, below, false));
			p.swing(InteractionHand.MAIN_HAND);
			return best;
		}
		return null;
	}

	private void useBlock(LocalPlayer p, BlockPos pos) {
		mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
		p.swing(InteractionHand.MAIN_HAND);
	}

	static void lookAt(LocalPlayer p, Vec3 target) {
		Vec3 eye = p.getEyePosition();
		double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		p.setYRot((float) Math.toDegrees(Math.atan2(dz, dx)) - 90f);
		p.setXRot((float) -Math.toDegrees(Math.atan2(dy, h)));
	}

	@SuppressWarnings("unused")
	private static boolean isItem(ItemStack s, Item it) {
		return s.is(it);
	}
}
