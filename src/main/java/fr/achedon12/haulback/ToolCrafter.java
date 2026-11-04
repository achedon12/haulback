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
 * Fabrique les outils qui manquent avant la recolte :
 * buches -> planches -> batons -> etabli -> pioche en bois -> pierre -> outils en pierre.
 * Un outil deja present dans l'inventaire n'est jamais refabrique.
 */
public final class ToolCrafter {

	public enum Result {RUNNING, DONE, FAILED}

	private enum Step {GET_LOGS, CRAFT_SMALL, PLACE_TABLE, OPEN_TABLE, CRAFT_TABLE, GET_STONE, TO_TABLE, PICK_TABLE, DONE}

	/** Une recette : grille (largeur 2 ou 3) + ingredient par case (null = vide). */
	private record Recipe(String name, Item output, int width, List<Predicate<ItemStack>> grid) {}

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

	private static final Recipe PLANKS = r2("planches", LOG, null, null, null);
	private static final Recipe STICKS = r2("bâtons", PLANK, null, PLANK, null);
	private static final Recipe TABLE = r2("établi", PLANK, PLANK, PLANK, PLANK);
	private static final Recipe WOOD_PICK = r3("pioche en bois", Items.WOODEN_PICKAXE, PLANK, PLANK, PLANK, null, STICK, null, null, STICK, null);
	private static final Recipe WOOD_AXE = r3("hache en bois", Items.WOODEN_AXE, PLANK, PLANK, null, PLANK, STICK, null, null, STICK, null);
	private static final Recipe WOOD_SWORD = r3("épée en bois", Items.WOODEN_SWORD, null, PLANK, null, null, PLANK, null, null, STICK, null);
	private static final Recipe WOOD_SHOVEL = r3("pelle en bois", Items.WOODEN_SHOVEL, null, PLANK, null, null, STICK, null, null, STICK, null);
	private static final Recipe STONE_PICK = r3("pioche en pierre", Items.STONE_PICKAXE, STONE, STONE, STONE, null, STICK, null, null, STICK, null);
	private static final Recipe STONE_AXE = r3("hache en pierre", Items.STONE_AXE, STONE, STONE, null, STONE, STICK, null, null, STICK, null);
	private static final Recipe STONE_SWORD = r3("épée en pierre", Items.STONE_SWORD, null, STONE, null, null, STONE, null, null, STICK, null);
	private static final Recipe STONE_SHOVEL = r3("pelle en pierre", Items.STONE_SHOVEL, null, STONE, null, null, STICK, null, null, STICK, null);

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

	/** Outils prevus pour cette fabrication (pour le HUD). */
	public List<Item> planned() {
		return planned;
	}

	public String failure() {
		return failure;
	}

	// ------------------------------------------------------------------ planification

	/**
	 * Prepare le plan. Renvoie false s'il n'y a rien a fabriquer.
	 * Si un outil en fer (ou mieux) est requis et absent, {@link #failure()} est rempli.
	 */
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
		if (requiredTier >= 2 && pickTier < requiredTier) {
			failure = "Il faut une pioche en " + (requiredTier == 3 ? "diamant" : "fer") + " pour : "
					+ String.join(", ", ironBlocks) + ". Mets-en une dans ton inventaire.";
			return false;
		}
		// un outil deja present n'est jamais refabrique, sauf si son niveau est insuffisant
		HaulbackConfig cfg = HaulbackConfig.get();
		boolean stoneTier = cfg.toolTier >= 1;
		boolean needPick = (cfg.toolPickaxe && pickTier < 0) || pickTier < requiredTier;
		woodRecipes.clear();
		if (needPick) {
			// en pierre si demande, ou si les blocs l'exigent
			if (stoneTier || requiredTier >= 1) stoneTools.add(STONE_PICK);
			else woodRecipes.add(WOOD_PICK);
		}
		if (cfg.toolAxe && !has(p, s -> s.is(ItemTags.AXES))) (stoneTier ? stoneTools : woodRecipes).add(stoneTier ? STONE_AXE : WOOD_AXE);
		if (cfg.toolSword && !has(p, s -> s.is(ItemTags.SWORDS))) (stoneTier ? stoneTools : woodRecipes).add(stoneTier ? STONE_SWORD : WOOD_SWORD);
		if (cfg.toolShovel && !has(p, s -> s.is(ItemTags.SHOVELS))) (stoneTier ? stoneTools : woodRecipes).add(stoneTier ? STONE_SHOVEL : WOOD_SHOVEL);
		// pour miner la pierre, il faut au moins une pioche : on en fait une en bois d'abord
		woodPick = !stoneTools.isEmpty() && pickTier < 0 && !woodRecipes.contains(WOOD_PICK);
		if (woodPick) woodRecipes.add(0, WOOD_PICK);
		if (stoneTools.isEmpty() && woodRecipes.isEmpty()) return false;

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
		stickCrafts = (int) Math.ceil(Math.max(0, sticks - count(p, STICK)) / 4.0);
		needTable = !has(p, s -> s.is(Items.CRAFTING_TABLE));
		int planks = (needTable ? 4 : 0) + stickCrafts * 2 + planksIn;
		plankCrafts = (int) Math.ceil(Math.max(0, planks - count(p, PLANK)) / 4.0);
		logsNeeded = Math.max(0, plankCrafts - count(p, LOG));
		stoneNeeded = stone;
		setStep(Step.GET_LOGS);
		planned.clear();
		List<String> names = new ArrayList<>();
		for (Recipe r : all) {
			planned.add(r.output);
			names.add(r.name);
		}
		Collector.get().say("Outils à fabriquer : " + String.join(", ", names) + ".", net.minecraft.ChatFormatting.GRAY);
		return true;
	}

	/** -1 : aucune pioche, 0 : bois/or, 1 : pierre/cuivre, 2 : fer, 3 : diamant/netherite. */
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
				status = "Outils : bûches " + Math.min(have, logsNeeded) + "/" + logsNeeded;
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
					if (ticks > 40 && ++retries > 3) return fail("Aucun arbre trouvé pour fabriquer les outils.");
					b.getMineProcess().mine(logsNeeded, logBlocks());
					ticks = 0;
				}
			}
			case CRAFT_SMALL -> {
				status = "Outils : fabrication (inventaire)";
				Result r = runQueue(p, p.inventoryMenu);
				if (r == Result.DONE) setStep(Step.PLACE_TABLE);
				return r == Result.FAILED ? Result.FAILED : Result.RUNNING;
			}
			case PLACE_TABLE -> {
				status = "Outils : pose de l'établi";
				if (ticks == 1) {
					tablePos = placeTable(p);
					if (tablePos == null) {
						if (++retries > 5) return fail("Impossible de poser l'établi ici.");
						ticks = -20;
					}
				} else if (ticks > 5) {
					if (mc.level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
						queue.clear();
						queue.addAll(woodRecipes);
						setStep(!woodRecipes.isEmpty() ? Step.OPEN_TABLE : Step.GET_STONE);
					} else {
						if (++retries > 5) return fail("Impossible de poser l'établi ici.");
						ticks = -20;
					}
				}
			}
			case OPEN_TABLE -> {
				status = "Outils : ouverture de l'établi";
				if (craftingMenu() != null) {
					craftStage = 0;
					setStep(Step.CRAFT_TABLE);
				} else if (Look.towards(p, Vec3.atCenterOf(tablePos)) && ticks % 20 >= 1) {
					if (ticks % 20 == 1 || retries == 0) {
						if (++retries > 6) return fail("L'établi ne s'ouvre pas.");
						useBlock(p, tablePos);
					}
				}
			}
			case CRAFT_TABLE -> {
				status = "Outils : fabrication à l'établi";
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
				status = "Outils : pierre " + Math.min(have, stoneNeeded) + "/" + stoneNeeded;
				if (have >= stoneNeeded) {
					b.getPathingBehavior().cancelEverything();
					b.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(tablePos));
					setStep(Step.TO_TABLE);
					return Result.RUNNING;
				}
				if (!b.getMineProcess().isActive()) {
					if (ticks > 40 && ++retries > 3) return fail("Pas de pierre trouvée pour les outils.");
					b.getMineProcess().mine(stoneNeeded + 4, Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE,
							Blocks.COBBLED_DEEPSLATE, Blocks.BLACKSTONE);
					ticks = 0;
				}
			}
			case TO_TABLE -> {
				status = "Outils : retour à l'établi";
				if (p.getEyePosition().distanceTo(Vec3.atCenterOf(tablePos)) < 4.2) {
					b.getPathingBehavior().cancelEverything();
					queue.clear();
					queue.addAll(stoneTools);
					stoneTools.clear();
					setStep(Step.OPEN_TABLE);
				} else if (!b.getCustomGoalProcess().isActive() && ticks > 40) {
					if (++retries > 6) return fail("Impossible de revenir à l'établi.");
					b.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(tablePos));
					ticks = 0;
				}
			}
			case PICK_TABLE -> {
				status = "Outils : récupération de l'établi";
				if (!mc.level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE) || ticks > 300) {
					mc.gameMode.stopDestroyBlock();
					b.getPathingBehavior().cancelEverything();
					setStep(Step.DONE);
					Collector.get().say("Outils prêts.", net.minecraft.ChatFormatting.GREEN);
					return Result.DONE;
				}
				// on casse uniquement NOTRE etabli (jamais celui d'un autre joueur)
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

	// ------------------------------------------------------------------ fabrication par clics

	/**
	 * Execute la file de recettes, une etape par appel :
	 * 0 = poser les ingredients, 1..3 = attendre la reponse du serveur, 4 = prendre le resultat, 5 = vider la grille.
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
						return fail("Ingrédient manquant pour : " + r.name + ".");
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
					return fail("La recette « " + r.name + " » n'a rien donné.");
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

	// ------------------------------------------------------------------ etabli

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
