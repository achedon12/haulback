package fr.achedon12.haulback;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import fr.achedon12.haulback.job.Materials;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Automatic workshop: runs the crafting and smelting steps of a plan
 * (planks, sticks, stairs, glass, ingots, iron pickaxe...). The crafting table and furnaces
 * are placed next to the first chest and reused afterwards.
 */
public final class Workshop {

	public enum Result {RUNNING, DONE, FAILED}

	private enum State {NEXT, FETCH, STATION, CRAFT, SMELT_LOAD, SMELT_WAIT, SMELT_COLLECT, DEPOSIT}

	private final Minecraft mc = Minecraft.getInstance();
	private final Deque<Materials.Step> steps = new ArrayDeque<>();
	private List<BlockPos> chests = List.of();
	private Materials.Step step;
	private State state;
	private int ticks;
	private int retries;
	private String status = "";
	private String failure;

	private int target;      // amount to hold at the end of the step
	private int startHave;   // amount already in the inventory at the start
	private final Set<BlockPos> fetchedFrom = new HashSet<>();
	private BlockPos goal;
	private int opened;
	private int furnaceIdx;
	private long waitUntil;
	private int craftStage;
	private final List<String> skipped = new ArrayList<>();
	private boolean extraFurnaceTried;

	public String status() {
		return status;
	}

	public String failure() {
		return failure;
	}

	public List<String> skipped() {
		return skipped;
	}

	public void start(List<Materials.Step> plan, List<BlockPos> chestList) {
		steps.clear();
		steps.addAll(plan);
		chests = chestList;
		extraFurnaceTried = false;
		parentOfSubStep = null;
		step = null;
		failure = null;
		skipped.clear();
		set(State.NEXT);
	}

	public boolean isEmpty() {
		return steps.isEmpty() && step == null;
	}

	// ------------------------------------------------------------------ main loop

	public Result tick(LocalPlayer p) {
		ticks++;
		flushInventoryCraft(p);
		switch (state) {
			case NEXT -> {
				step = steps.poll();
				if (step == null) {
					status = L.t("haulback.status.workshop_done");
					return Result.DONE;
				}
				startHave = count(p, step.item());
				target = startHave + step.count();
				HaulbackClient.LOG.info("Workshop: step {} x{} (in hand {}, target {}), inputs {}", step.item(), step.count(), startHave, target, step.inputs());
				fetchedFrom.clear();
				set(State.FETCH);
			}
			case FETCH -> tickFetch(p);
			case STATION -> tickStation(p);
			case CRAFT -> tickCraft(p);
			case SMELT_LOAD -> tickSmeltLoad(p);
			case SMELT_WAIT -> {
				long left = Math.max(0, (waitUntil - mc.level.getGameTime()) / 20);
				status = L.t("haulback.status.smelting") + name(step.item()) + " (" + left + " s)";
				if (mc.level.getGameTime() >= waitUntil) {
					furnaceIdx = 0;
					set(State.SMELT_COLLECT);
				}
			}
			case SMELT_COLLECT -> tickSmeltCollect(p);
			case DEPOSIT -> tickDeposit(p);
		}
		return failure != null ? Result.FAILED : Result.RUNNING;
	}

	private void set(State s) {
		state = s;
		ticks = 0;
		retries = 0;
		opened = 0;
		craftStage = 0;
	}

	private Materials.Step parentOfSubStep;

	private void skipStep(String why) {
		// a failing sub-step (the furnace) fails the step that needed it
		if (parentOfSubStep != null && steps.peek() == parentOfSubStep) {
			steps.poll();
			skipped.add(name(parentOfSubStep.item()) + L.t("haulback.msg.no_furnace_paren") + why + ")");
			Collector.get().say(L.t("haulback.status.workshop_prefix") + name(parentOfSubStep.item()) + L.t("haulback.msg.skipped_no_furnace") + why + ").", ChatFormatting.GOLD);
			parentOfSubStep = null;
		}
		skipped.add(name(step.item()) + " (" + why + ")");
		Collector.get().say(L.t("haulback.status.workshop_prefix") + name(step.item()) + L.t("haulback.msg.skipped") + why + ".", ChatFormatting.GOLD);
		closeScreen(p());
		set(State.DEPOSIT);
	}

	private LocalPlayer p() {
		return mc.player;
	}

	// ------------------------------------------------------------------ 1. fetch ingredients

	/** Amount of each ingredient (and fuel) needed in the inventory. */
	private Map<Item, Integer> needs(LocalPlayer p) {
		Map<Item, Integer> out = new LinkedHashMap<>();
		int toMake = Math.max(0, target - count(p, step.item()));
		for (Map.Entry<Item, Double> e : step.inputs().entrySet()) {
			out.put(e.getKey(), (int) Math.ceil(toMake * e.getValue() - 1e-9));
		}
		// the crafting table does not exist yet: it will use one extra log (or 4 planks)
		if (!step.smelt() && table() == null && count(p, Items.CRAFTING_TABLE) == 0) {
			for (Item it : out.keySet()) {
				if (it.getDefaultInstance().is(ItemTags.LOGS)) {
					out.merge(it, 1, Integer::sum);
					break;
				}
				if (it.getDefaultInstance().is(ItemTags.PLANKS)) {
					out.merge(it, 4, Integer::sum);
					break;
				}
			}
		}
		return out;
	}

	private void tickFetch(LocalPlayer p) {
		Map<Item, Integer> need = needs(p);
		boolean missing = false;
		for (Map.Entry<Item, Integer> e : need.entrySet()) {
			if (countInput(p, e.getKey()) < e.getValue()) missing = true;
		}
		boolean fuelMissing = step.smelt() && fuelUnits(p) < Math.max(0, target - count(p, step.item()));
		boolean tableMissing = needTableMaterial(p);
		if (tableMissing) missing = true;
		if ((!missing && !fuelMissing) || freeSlots(p) <= 1) {
			closeScreen(p);
			set(State.STATION);
			return;
		}
		// nearest chest holding something useful
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos c : chests) {
			if (fetchedFrom.contains(c)) continue;
			boolean useful = false;
			for (Item it : need.keySet()) if (ChestCache.count(c, it) > 0) useful = true;
			if (fuelMissing && hasFuelIn(c)) useful = true;
			if (tableMissing) useful = true;
			if (!ChestCache.known(c)) useful = true;
			if (!useful) continue;
			double d = c.distSqr(p.blockPosition());
			if (d < bestD) {
				bestD = d;
				best = c;
			}
		}
		if (best == null) {
			closeScreen(p);
			set(State.STATION);
			return;
		}
		goal = best;
		status = L.t("haulback.status.workshop_fetch") + name(step.item());
		AbstractContainerMenu menu = reachAndOpen(p, best, false);
		if (menu == null) return;
		if (ticks - opened < 4) return;
		// one action per tick: store what is useless, then take what is useful
		for (Slot s : menu.slots) {
			if (s.container != p.getInventory() || s.getContainerSlot() >= Inventory.INVENTORY_SIZE || !s.hasItem()) continue;
			ItemStack st = s.getItem();
			boolean plankNeed = st.is(ItemTags.PLANKS) && need.keySet().stream().anyMatch(i -> i.getDefaultInstance().is(ItemTags.PLANKS));
			if (keep(st) || need.containsKey(st.getItem()) || plankNeed || isFuel(st) || st.is(step.item())) continue;
			click(p, menu, s.index, 0, ClickType.QUICK_MOVE);
			return;
		}
		for (Slot s : menu.slots) {
			if (s.container == p.getInventory() || !s.hasItem()) continue;
			ItemStack st = s.getItem();
			Integer n = need.get(st.getItem());
			if (n == null && st.is(ItemTags.PLANKS)) {
				for (Map.Entry<Item, Integer> e : need.entrySet()) {
					if (e.getKey().getDefaultInstance().is(ItemTags.PLANKS)) n = e.getValue() - countInput(p, e.getKey()) + count(p, st.getItem());
				}
			}
			boolean take = n != null && count(p, st.getItem()) < n;
			if (!take && needTableMaterial(p) && (st.is(ItemTags.LOGS) || st.is(ItemTags.PLANKS)) && !need.containsKey(st.getItem())) take = true;
			boolean coalAvailable = false;
			for (Slot c : menu.slots) if (c.container != p.getInventory() && c.getItem().is(ItemTags.COALS)) coalAvailable = true;
			if (!take && fuelMissing && isFuel(st) && !need.containsKey(st.getItem()) && (st.is(ItemTags.COALS) || !coalAvailable)) take = true;
			if (take && freeSlots(p) > 1) {
				click(p, menu, s.index, 0, ClickType.QUICK_MOVE);
				return;
			}
		}
		ChestCache.snapshot(best, menu, p.getInventory());
		fetchedFrom.add(best);
		closeScreen(p);
		ticks = 0;
		opened = 0;
	}

	// ------------------------------------------------------------------ 2. workstation

	private void tickStation(LocalPlayer p) {
		Map<Item, Integer> need = needs(p);
		// craft what we can with what we have; if nothing at all, skip
		boolean any = true;
		for (Map.Entry<Item, Integer> e : need.entrySet()) {
			if (e.getValue() > 0 && countInput(p, e.getKey()) == 0) any = false;
		}
		if (count(p, step.item()) >= target) {
			set(State.DEPOSIT);
			return;
		}
		if (!any) {
			skipStep(L.t("haulback.reason.no_ingredients"));
			return;
		}
		if (step.smelt()) {
			List<BlockPos> furnaces = furnaces();
			int toMake = target - count(p, step.item());
			int desired = Math.min(3, Math.max(1, (int) Math.ceil(toMake / 24.0)));
			if (furnaces.isEmpty() || (furnaces.size() < desired && count(p, Items.FURNACE) > 0)) {
				placeStation(p, Items.FURNACE);
				return;
			}
			if (furnaces.size() < desired && !extraFurnaceTried) {
				// one more furnace to go faster, if we have the stone
				extraFurnaceTried = true;
				placeStation(p, Items.FURNACE);
				return;
			}
			if (fuelUnits(p) <= 0) {
				skipStep(L.t("haulback.reason.no_fuel"));
				return;
			}
			furnaceIdx = 0;
			set(State.SMELT_LOAD);
		} else {
			if (table() == null) {
				placeStation(p, Items.CRAFTING_TABLE);
				return;
			}
			set(State.CRAFT);
		}
	}

	private BlockPos table() {
		HaulbackConfig cfg = HaulbackConfig.get();
		if (cfg.workshopTable != null) {
			BlockPos t = new BlockPos(cfg.workshopTable[0], cfg.workshopTable[1], cfg.workshopTable[2]);
			if (mc.level.getBlockState(t).is(Blocks.CRAFTING_TABLE)) return t;
		}
		return null;
	}

	private List<BlockPos> furnaces() {
		List<BlockPos> out = new ArrayList<>();
		for (int[] f : HaulbackConfig.get().workshopFurnaces) {
			BlockPos b = new BlockPos(f[0], f[1], f[2]);
			if (mc.level.getBlockState(b).is(Blocks.FURNACE)) out.add(b);
		}
		return out;
	}

	/** Places a crafting table or a furnace next to the first chest (crafting it if needed). */
	private boolean placeStation(LocalPlayer p, Item item) {
		status = L.t("haulback.status.workshop_setup") + (item == Items.FURNACE ? L.t("haulback.word.the_furnace") : L.t("haulback.word.the_table"));
		if (pendingTake > 0) return false; // a 2x2 craft is in progress
		if (count(p, item) == 0) {
			// crafting: the table in the inventory grid (2x2), the furnace at the table
			if (item == Items.CRAFTING_TABLE) {
				if (!craftInInventory(p, Items.CRAFTING_TABLE)) {
					skipStep(L.t("haulback.reason.no_table"));
				}
				return false;
			}
			// furnace: crafting step inserted right before (mandatory when there is none)
			if (!furnaces().isEmpty() && Workshop.count(p, Items.COBBLESTONE) < 8 && ChestCache.stockOf(chests).getOrDefault(Items.COBBLESTONE, 0) < 8) {
				set(State.SMELT_LOAD);
				return false;
			}
			steps.push(step);
			parentOfSubStep = step;
			step = new Materials.Step(Items.FURNACE, 1, false, Map.of(Items.COBBLESTONE, 8.0));
			startHave = count(p, Items.FURNACE);
			target = startHave + 1;
			fetchedFrom.clear();
			set(State.FETCH);
			return false;
		}
		BlockPos anchor = chests.isEmpty() ? p.blockPosition() : chests.get(0);
		if (p.getEyePosition().distanceTo(Vec3.atCenterOf(anchor)) > 4.5) {
			goal = anchor;
			walkTo(anchor);
			return false;
		}
		BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
		if (ticks % 10 != 1) return false;
		BlockPos spot = findSpot(p);
		if (spot == null) {
			if (++retries > 5) skipStep(L.t("haulback.reason.no_room"));
			return false;
		}
		select(p, item);
		BlockPos below = spot.below();
		Vec3 hit = Vec3.atCenterOf(below).add(0, 0.5, 0);
		ToolCrafter.lookAt(p, hit);
		mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, below, false));
		p.swing(InteractionHand.MAIN_HAND);
		HaulbackConfig cfg = HaulbackConfig.get();
		if (item == Items.CRAFTING_TABLE) cfg.workshopTable = new int[]{spot.getX(), spot.getY(), spot.getZ()};
		else cfg.workshopFurnaces.add(new int[]{spot.getX(), spot.getY(), spot.getZ()});
		cfg.save();
		return true;
	}

	private BlockPos findSpot(LocalPlayer p) {
		BlockPos feet = p.blockPosition();
		Vec3 eye = p.getEyePosition();
		Set<BlockPos> reserved = new HashSet<>(chests);
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -2; dy <= 1; dy++) {
			BlockPos pos = feet.offset(dx, dy, dz);
			BlockPos below = pos.below();
			if (reserved.contains(pos) || !mc.level.getBlockState(pos).canBeReplaced()) continue;
			if (!mc.level.getBlockState(below).isFaceSturdy(mc.level, below, Direction.UP)) continue;
			if (new AABB(pos).intersects(p.getBoundingBox())) continue;
			// do not block access to a chest
			boolean nextToChest = false;
			for (BlockPos c : chests) if (c.distManhattan(pos) <= 1) nextToChest = true;
			if (nextToChest) continue;
			double d = eye.distanceTo(Vec3.atCenterOf(pos));
			if (d > 4.0 || d < 1.5) continue;
			if (d < bestD) {
				bestD = d;
				best = pos;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ 3. crafting

	private RecipeDisplayEntry findRecipe(LocalPlayer p, Item out, boolean grid2) {
		ContextMap ctx = SlotDisplayContext.fromLevel(mc.level);
		StackedItemContents have = new StackedItemContents();
		p.getInventory().fillStackedContents(have);
		RecipeDisplayEntry fallback = null;
		for (var coll : p.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry e : coll.getRecipes()) {
				boolean crafting = e.display() instanceof ShapedCraftingRecipeDisplay || e.display() instanceof ShapelessCraftingRecipeDisplay;
				if (!crafting) continue;
				if (grid2 && e.display() instanceof ShapedCraftingRecipeDisplay sh && (sh.width() > 2 || sh.height() > 2)) continue;
				if (grid2 && e.display() instanceof ShapelessCraftingRecipeDisplay sl && sl.ingredients().size() > 4) continue;
				List<ItemStack> res = e.resultItems(ctx);
				if (res.isEmpty() || !res.get(0).is(out)) continue;
				if (e.canCraft(have)) return e;
				fallback = e;
			}
		}
		return fallback;
	}

	private boolean craftInInventory(LocalPlayer p, Item out) {
		// planks first if needed
		if (count(p, s -> s.is(ItemTags.PLANKS)) < 4) {
			RecipeDisplayEntry planks = null;
			ContextMap ctx = SlotDisplayContext.fromLevel(mc.level);
			StackedItemContents have = new StackedItemContents();
			p.getInventory().fillStackedContents(have);
			for (var coll : p.getRecipeBook().getCollections()) {
				for (RecipeDisplayEntry e : coll.getRecipes()) {
					List<ItemStack> res = e.resultItems(ctx);
					if (!res.isEmpty() && res.get(0).is(ItemTags.PLANKS) && e.canCraft(have)) planks = e;
				}
			}
			if (planks == null) return false;
			mc.gameMode.handlePlaceRecipe(p.inventoryMenu.containerId, planks.id(), false);
			pendingTake = 3;
			return true;
		}
		RecipeDisplayEntry e = findRecipe(p, out, true);
		if (e == null) return false;
		mc.gameMode.handlePlaceRecipe(p.inventoryMenu.containerId, e.id(), false);
		pendingTake = 3;
		return true;
	}

	private int pendingTake;

	/** Called every tick: collects the result of a 2x2 craft started earlier. */
	public void flushInventoryCraft(LocalPlayer p) {
		if (pendingTake <= 0) return;
		if (--pendingTake == 0 && p.inventoryMenu.getSlot(0).hasItem()) {
			click(p, p.inventoryMenu, 0, 0, ClickType.QUICK_MOVE);
			for (int i = 1; i <= 4; i++) {
				if (p.inventoryMenu.getSlot(i).hasItem()) click(p, p.inventoryMenu, i, 0, ClickType.QUICK_MOVE);
			}
		}
	}

	private void tickCraft(LocalPlayer p) {
		BlockPos t = table();
		if (t == null) {
			set(State.STATION);
			return;
		}
		int now = count(p, step.item());
		status = L.t("haulback.status.workshop_prefix") + name(step.item()) + " " + Math.max(0, now - startHave) + "/" + step.count();
		AbstractContainerMenu menu = reachAndOpen(p, t, true);
		if (!(menu instanceof CraftingMenu cm)) return;
		if (ticks - opened < 3) return;
		if (now >= target) {
			clearGrid(p, cm);
			closeScreen(p);
			set(State.DEPOSIT);
			return;
		}
		switch (craftStage) {
			case 0 -> {
				RecipeDisplayEntry e = findRecipe(p, step.item(), false);
				StackedItemContents have = new StackedItemContents();
				p.getInventory().fillStackedContents(have);
				if (e == null || !e.canCraft(have)) {
					clearGrid(p, cm);
					closeScreen(p);
					// out of ingredients: back to the chests if any are left, otherwise move on
					if (now > startHave && fetchedFrom.size() < chests.size()) {
						set(State.FETCH);
					} else if (now > startHave) {
						set(State.DEPOSIT);
					} else {
						skipStep(e == null ? L.t("haulback.reason.unknown_recipe") : L.t("haulback.reason.not_enough"));
					}
					return;
				}
				boolean bulk = target - now >= 32;
				HaulbackClient.LOG.info("Workshop: recipe {} for {} (in hand {} / target {})", e.id(), step.item(), now, target);
				mc.gameMode.handlePlaceRecipe(cm.containerId, e.id(), bulk);
				craftStage = 1;
				retries = 0;
			}
			case 1, 2 -> craftStage++;
			case 3 -> {
				if (cm.getSlot(0).hasItem()) {
					click(p, cm, 0, 0, ClickType.QUICK_MOVE);
					craftStage = 0;
				} else if (++retries > 20) {
					clearGrid(p, cm);
					craftStage = 0;
				}
			}
			default -> craftStage = 0;
		}
	}

	private void clearGrid(LocalPlayer p, CraftingMenu cm) {
		for (int i = 1; i <= 9; i++) {
			if (cm.getSlot(i).hasItem()) click(p, cm, i, 0, ClickType.QUICK_MOVE);
		}
	}

	// ------------------------------------------------------------------ 4. smelting

	private int loadedThisRound;

	private void tickSmeltLoad(LocalPlayer p) {
		List<BlockPos> furnaces = furnaces();
		int toMake = Math.max(0, target - count(p, step.item()));
		Item input = step.inputs().keySet().iterator().next();
		if (furnaceIdx >= furnaces.size() || toMake <= 0 || count(p, input) == 0) {
			if (loadedThisRound == 0) {
				if (count(p, step.item()) > startHave) set(State.DEPOSIT);
				else skipStep(L.t("haulback.reason.nothing_to_smelt"));
				return;
			}
			// smelting time: 10 s per item, in parallel across furnaces
			int perFurnace = (int) Math.ceil(loadedThisRound / (double) Math.max(1, Math.min(furnaces.size(), loadedThisRound)));
			waitUntil = mc.level.getGameTime() + perFurnace * 200L + 30;
			set(State.SMELT_WAIT);
			return;
		}
		BlockPos f = furnaces.get(furnaceIdx);
		status = L.t("haulback.status.workshop_load") + (furnaceIdx + 1);
		AbstractContainerMenu menu = reachAndOpen(p, f, true);
		if (!(menu instanceof AbstractFurnaceMenu fm)) return;
		if (ticks - opened < 3) return;
		int share = (int) Math.ceil(toMake / (double) (furnaces.size() - furnaceIdx));
		share = Math.min(share, 64 - fm.getSlot(0).getItem().getCount());
		int put = putExact(p, fm, 0, s -> s.is(input), Math.min(share, count(p, input)));
		int fuelNeeded = fuelUnitsFor(put) - fuelInSlot(fm);
		if (fuelNeeded > 0) putFuel(p, fm, put);
		loadedThisRound += put;
		closeScreen(p);
		furnaceIdx++;
		set(State.SMELT_LOAD);
	}

	private void tickSmeltCollect(LocalPlayer p) {
		List<BlockPos> furnaces = furnaces();
		if (furnaceIdx >= furnaces.size()) {
			loadedThisRound = 0;
			furnaceIdx = 0;
			// more to smelt? reload, otherwise store
			Item input = step.inputs().keySet().iterator().next();
			if (count(p, step.item()) < target && count(p, input) > 0 && fuelUnits(p) > 0) set(State.SMELT_LOAD);
			else if (count(p, step.item()) < target && fetchedFrom.size() < chests.size()) set(State.FETCH);
			else set(State.DEPOSIT);
			return;
		}
		BlockPos f = furnaces.get(furnaceIdx);
		status = L.t("haulback.status.workshop_collect");
		AbstractContainerMenu menu = reachAndOpen(p, f, true);
		if (!(menu instanceof AbstractFurnaceMenu fm)) return;
		if (ticks - opened < 3) return;
		if (fm.getSlot(2).hasItem()) {
			click(p, fm, 2, 0, ClickType.QUICK_MOVE);
			return;
		}
		// if smelting is not done (furnaces slower than expected), wait a little longer
		if (fm.getSlot(0).hasItem() && fm.isLit() && ticks < 600) return;
		closeScreen(p);
		furnaceIdx++;
		set(State.SMELT_COLLECT);
	}

	private static boolean isFuel(ItemStack s) {
		return s.is(ItemTags.COALS) || s.is(ItemTags.LOGS_THAT_BURN) || s.is(ItemTags.PLANKS);
	}

	private static double burnValue(ItemStack s) {
		return s.is(ItemTags.COALS) ? 8 : 1.5;
	}

	private int fuelUnits(LocalPlayer p) {
		double n = 0;
		Inventory inv = p.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			// planks/logs used as ingredients are not fuel
			if (isFuel(s) && !step.inputs().containsKey(s.getItem())) n += s.getCount() * burnValue(s);
		}
		return (int) n;
	}

	private static int fuelUnitsFor(int items) {
		return items;
	}

	private int fuelInSlot(AbstractFurnaceMenu fm) {
		ItemStack s = fm.getSlot(1).getItem();
		return s.isEmpty() ? 0 : (int) (s.getCount() * burnValue(s));
	}

	private void putFuel(LocalPlayer p, AbstractFurnaceMenu fm, int items) {
		ItemStack inSlot = fm.getSlot(1).getItem();
		// same fuel type as the one already there, otherwise coal first
		for (int pass = 0; pass < 2; pass++) {
			for (Slot s : fm.slots) {
				if (s.container != p.getInventory() || !s.hasItem()) continue;
				ItemStack st = s.getItem();
				if (!isFuel(st) || step.inputs().containsKey(st.getItem())) continue;
				if (pass == 0 && !st.is(ItemTags.COALS)) continue;
				if (!inSlot.isEmpty() && !ItemStack.isSameItemSameComponents(inSlot, st)) continue;
				int units = (int) Math.ceil((items - fuelInSlot(fm)) / burnValue(st));
				if (units <= 0) return;
				putExact(p, fm, 1, x -> ItemStack.isSameItemSameComponents(x, st), Math.min(units, st.getCount()));
				return;
			}
		}
	}

	/** Puts exactly {@code n} items matching {@code pred} into slot {@code target}. */
	private int putExact(LocalPlayer p, AbstractContainerMenu menu, int target, java.util.function.Predicate<ItemStack> pred, int n) {
		int left = n;
		for (Slot s : menu.slots) {
			if (left <= 0) break;
			if (s.container != p.getInventory() || !s.hasItem() || !pred.test(s.getItem())) continue;
			int c = s.getItem().getCount();
			click(p, menu, s.index, 0, ClickType.PICKUP);
			if (c <= left) {
				click(p, menu, target, 0, ClickType.PICKUP);
				left -= c;
			} else {
				for (int i = 0; i < left; i++) click(p, menu, target, 1, ClickType.PICKUP);
				left = 0;
			}
			if (!menu.getCarried().isEmpty()) click(p, menu, s.index, 0, ClickType.PICKUP);
		}
		return n - left;
	}

	// ------------------------------------------------------------------ 5. store

	private void tickDeposit(LocalPlayer p) {
		BlockPos best = chests.stream().min(Comparator.comparingDouble(c -> c.distSqr(p.blockPosition()))).orElse(null);
		if (best == null) {
			set(State.NEXT);
			return;
		}
		status = L.t("haulback.status.workshop_store") + name(step.item());
		AbstractContainerMenu menu = reachAndOpen(p, best, false);
		if (menu == null) return;
		if (ticks - opened < 3) return;
		for (Slot s : menu.slots) {
			if (s.container != p.getInventory() || s.getContainerSlot() >= Inventory.INVENTORY_SIZE || !s.hasItem()) continue;
			if (keep(s.getItem())) continue;
			click(p, menu, s.index, 0, ClickType.QUICK_MOVE);
			return;
		}
		ChestCache.snapshot(best, menu, p.getInventory());
		HaulbackClient.LOG.info("Workshop: stored after {}, chest = {}", step.item(), ChestCache.stockOf(List.of(best)));
		closeScreen(p);
		step = null;
		set(State.NEXT);
	}

	/** Items kept on the player: tools, armor, food, and workshop equipment. */
	private static boolean keep(ItemStack s) {
		return s.isDamageableItem() || s.has(DataComponents.FOOD) || s.has(DataComponents.EQUIPPABLE)
				|| s.is(Items.CRAFTING_TABLE) || s.is(Items.FURNACE) || s.is(Items.TOTEM_OF_UNDYING);
	}

	// ------------------------------------------------------------------ utilities

	private void walkTo(BlockPos pos) {
		IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
		if (!b.getCustomGoalProcess().isActive() && !b.getPathingBehavior().isPathing()) {
			if (++retries > 400) {
				failure = L.t("haulback.msg.unreachable") + pos.toShortString() + ".";
				return;
			}
			b.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(pos));
		}
	}

	/** Walks to the block, looks at it and opens it. Returns the menu once open. */
	private AbstractContainerMenu reachAndOpen(LocalPlayer p, BlockPos pos, boolean station) {
		if (mc.screen instanceof AbstractContainerScreen<?> s && !(s.getMenu() instanceof InventoryMenu)) {
			if (opened == 0) opened = ticks;
			return s.getMenu();
		}
		if (p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 4.3) {
			walkTo(pos);
			return null;
		}
		IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
		if (b.getPathingBehavior().isPathing()) b.getPathingBehavior().cancelEverything();
		if (!Look.towards(p, Vec3.atCenterOf(pos)) && ticks % 40 != 39) return null;
		if (ticks % 15 == 0 || retries == 0) {
			if (++retries > 8) {
				failure = L.t("haulback.msg.cannot_open") + (station ? L.t("haulback.word.the_station") : L.t("haulback.word.the_chest")) + L.t("haulback.word.at") + pos.toShortString() + ".";
				return null;
			}
			mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
			p.swing(InteractionHand.MAIN_HAND);
		}
		return null;
	}

	private void closeScreen(LocalPlayer p) {
		if (p != null && p.containerMenu != p.inventoryMenu) p.closeContainer();
	}

	private void click(LocalPlayer p, AbstractContainerMenu menu, int slot, int button, ClickType type) {
		mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, p);
	}

	private void select(LocalPlayer p, Item item) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			if (!inv.getItem(i).is(item)) continue;
			int slot = i;
			if (slot >= Inventory.SELECTION_SIZE) {
				mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, slot, 8, ClickType.SWAP, p);
				slot = 8;
			}
			inv.setSelectedSlot(slot);
			return;
		}
	}

	private boolean hasFuelIn(BlockPos c) {
		return ChestCache.count(c, Items.COAL) + ChestCache.count(c, Items.CHARCOAL) > 0;
	}

	/** The step needs a crafting table that does not exist yet, and there is nothing to make one. */
	private boolean needTableMaterial(LocalPlayer p) {
		if (step == null || step.smelt() || table() != null || count(p, Items.CRAFTING_TABLE) > 0) return false;
		return count(p, s -> s.is(ItemTags.LOGS)) == 0 && count(p, s -> s.is(ItemTags.PLANKS)) < 4;
	}

	/** Like count, but any planks will do when the ingredient is a plank (sticks, crafting table). */
	private static int countInput(LocalPlayer p, Item it) {
		if (it.getDefaultInstance().is(ItemTags.PLANKS)) return count(p, s -> s.is(ItemTags.PLANKS));
		return count(p, it);
	}

	static int count(LocalPlayer p, Item it) {
		return count(p, s -> s.is(it));
	}

	static int count(LocalPlayer p, java.util.function.Predicate<ItemStack> pred) {
		int n = 0;
		Inventory inv = p.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && pred.test(s)) n += s.getCount();
		}
		return n;
	}

	private static int freeSlots(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
		return n;
	}

	private static String name(Item it) {
		return it.getName().getString();
	}
}
