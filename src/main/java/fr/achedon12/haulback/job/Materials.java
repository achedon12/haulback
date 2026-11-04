package fr.achedon12.haulback.job;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a list of wanted items (e.g. from a schematic) into blocks to mine
 * in the world, following common recipes back (planks -> logs, glass -> sand...).
 * It is not a full recipe engine: anything unknown is listed as "provide yourself",
 * and the user can force a direct search in the world.
 */
public final class Materials {

	/** A displayable line: item + amount + note. */
	public record Line(Item item, int count, String note) {
		public ItemStack stack() {
			return new ItemStack(item);
		}
	}

	/**
	 * A processing step for the workshop (in order: ingredients first).
	 *
	 * @param inputs amount of each ingredient per produced item
	 */
	public record Step(Item item, int count, boolean smelt, Map<Item, Double> inputs) {}

	/** Result of the resolution. */
	public static final class Plan {
		/** Crafting and smelting steps, in the order to run them. */
		public final List<Step> steps = new ArrayList<>();

		public int smeltCount() {
			int n = 0;
			for (Step st : steps) if (st.smelt()) n += st.count();
			return n;
		}

		public final List<Target> targets = new ArrayList<>();
		public final List<Line> toCraft = new ArrayList<>();
		public final List<Line> manual = new ArrayList<>();
		/** What is already in the chests and was subtracted. */
		public final List<Line> fromStock = new ArrayList<>();
	}

	private record Terminal(Item display, Set<Block> sources, double yieldPerBlock) {}

	private record Ingredient(String item, double ratio) {}

	private record Recipe(List<Ingredient> ingredients, String note) {}

	private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak"};
	private static final String[] STEMS = {"crimson", "warped"};

	private static final Map<String, Terminal> TERMINALS = new LinkedHashMap<>();
	private static final Map<String, Recipe> RECIPES = new LinkedHashMap<>();
	/** Natural blocks that drop themselves by hand or with the right tool. */
	private static final Set<String> NATURAL = Set.of(
			"coarse_dirt", "rooted_dirt", "sand", "red_sand", "gravel", "andesite", "diorite", "granite", "tuff",
			"calcite", "netherrack", "end_stone", "basalt", "smooth_basalt", "blackstone", "soul_sand", "soul_soil",
			"magma_block", "obsidian", "crying_obsidian", "dripstone_block", "mud", "moss_block", "pumpkin", "cactus",
			"sugar_cane", "bamboo", "amethyst_block", "nether_wart_block", "warped_wart_block", "shroomlight",
			"white_terracotta", "orange_terracotta", "yellow_terracotta", "brown_terracotta", "red_terracotta",
			"light_gray_terracotta", "sculk_vein", "pointed_dripstone", "cobweb", "mushroom_stem");

	static {
		terminal("cobblestone", Items.COBBLESTONE, 1, Blocks.STONE, Blocks.COBBLESTONE);
		terminal("cobbled_deepslate", Items.COBBLED_DEEPSLATE, 1, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE);
		terminal("dirt", Items.DIRT, 1, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM);
		terminal("raw_iron", Items.RAW_IRON, 1, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
		terminal("raw_gold", Items.RAW_GOLD, 1, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE);
		terminal("raw_copper", Items.RAW_COPPER, 2, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
		terminal("coal", Items.COAL, 1, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
		terminal("diamond", Items.DIAMOND, 1, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
		terminal("emerald", Items.EMERALD, 1, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
		terminal("lapis_lazuli", Items.LAPIS_LAZULI, 4, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
		terminal("redstone", Items.REDSTONE, 4, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
		terminal("quartz", Items.QUARTZ, 1, Blocks.NETHER_QUARTZ_ORE);
		terminal("clay_ball", Items.CLAY_BALL, 4, Blocks.CLAY);
		terminal("snowball", Items.SNOWBALL, 4, Blocks.SNOW_BLOCK);
		terminal("glowstone_dust", Items.GLOWSTONE_DUST, 3, Blocks.GLOWSTONE);

		String smelt = "smelt";
		String craft = "craft";
		recipe("stone", smelt, "cobblestone", 1);
		recipe("smooth_stone", smelt, "stone", 1);
		recipe("stone_bricks", craft, "stone", 1);
		recipe("cracked_stone_bricks", smelt, "stone_bricks", 1);
		recipe("chiseled_stone_bricks", craft, "stone", 1);
		recipe("deepslate", smelt, "cobbled_deepslate", 1);
		for (String s : new String[]{"polished_deepslate", "deepslate_bricks", "deepslate_tiles", "chiseled_deepslate"}) {
			recipe(s, craft, "cobbled_deepslate", 1);
		}
		recipe("polished_andesite", craft, "andesite", 1);
		recipe("polished_diorite", craft, "diorite", 1);
		recipe("polished_granite", craft, "granite", 1);
		recipe("polished_tuff", craft, "tuff", 1);
		recipe("tuff_bricks", craft, "tuff", 1);
		recipe("polished_blackstone", craft, "blackstone", 1);
		recipe("polished_blackstone_bricks", craft, "blackstone", 1);
		recipe("polished_basalt", craft, "basalt", 1);
		recipe("glass", smelt, "sand", 1);
		recipe("glass_pane", craft, "glass", 0.375);
		recipe("sandstone", craft, "sand", 4);
		recipe("cut_sandstone", craft, "sandstone", 1);
		recipe("chiseled_sandstone", craft, "sandstone", 1);
		recipe("smooth_sandstone", smelt, "sandstone", 1);
		recipe("red_sandstone", craft, "red_sand", 4);
		recipe("cut_red_sandstone", craft, "red_sandstone", 1);
		recipe("chiseled_red_sandstone", craft, "red_sandstone", 1);
		recipe("smooth_red_sandstone", smelt, "red_sandstone", 1);
		recipe("clay", craft, "clay_ball", 4);
		recipe("brick", smelt, "clay_ball", 1);
		recipe("bricks", craft, "brick", 4);
		recipe("terracotta", smelt, "clay", 1);
		recipe("nether_brick", smelt, "netherrack", 1);
		recipe("nether_bricks", craft, "nether_brick", 4);
		recipe("quartz_block", craft, "quartz", 4);
		recipe("quartz_bricks", craft, "quartz_block", 1);
		recipe("quartz_pillar", craft, "quartz_block", 1);
		recipe("smooth_quartz", smelt, "quartz_block", 1);
		recipe("snow_block", craft, "snowball", 4);
		recipe("glowstone", craft, "glowstone_dust", 4);
		recipe("iron_ingot", smelt, "raw_iron", 1);
		recipe("gold_ingot", smelt, "raw_gold", 1);
		recipe("copper_ingot", smelt, "raw_copper", 1);
		recipe("iron_block", craft, "iron_ingot", 9);
		recipe("gold_block", craft, "gold_ingot", 9);
		recipe("copper_block", craft, "copper_ingot", 9);
		recipe("diamond_block", craft, "diamond", 9);
		recipe("emerald_block", craft, "emerald", 9);
		recipe("lapis_block", craft, "lapis_lazuli", 9);
		recipe("redstone_block", craft, "redstone", 9);
		recipe("coal_block", craft, "coal", 9);
		recipe("iron_bars", craft, "iron_ingot", 0.375);
		recipe("iron_chain", craft, "iron_ingot", 1.23);
		recipe("stick", craft, "oak_planks", 0.5);
		RECIPES.put("torch", new Recipe(List.of(new Ingredient("stick", 0.25), new Ingredient("coal", 0.25)), craft));
		RECIPES.put("lantern", new Recipe(List.of(new Ingredient("iron_ingot", 0.9), new Ingredient("torch", 1)), craft));
		// copper family (oxidized and waxed variants need time or honeycomb: provide yourself)
		recipe("cut_copper", craft, "copper_block", 1);
		recipe("chiseled_copper", craft, "cut_copper", 1);
		recipe("copper_grate", craft, "copper_block", 1);
		recipe("copper_door", craft, "copper_ingot", 2);
		recipe("copper_trapdoor", craft, "copper_ingot", 4);
		recipe("copper_bars", craft, "copper_ingot", 0.375);
		// mud (wheat comes from farming: provide yourself)
		RECIPES.put("packed_mud", new Recipe(List.of(new Ingredient("mud", 1), new Ingredient("wheat", 1)), craft));
		recipe("mud_bricks", craft, "packed_mud", 1);
		// prismarine (shards and crystals are mob drops: provide yourself)
		recipe("prismarine", craft, "prismarine_shard", 4);
		recipe("prismarine_bricks", craft, "prismarine_shard", 9);
		RECIPES.put("dark_prismarine", new Recipe(List.of(new Ingredient("prismarine_shard", 8), new Ingredient("ink_sac", 1)), craft));
		RECIPES.put("sea_lantern", new Recipe(List.of(new Ingredient("prismarine_shard", 4), new Ingredient("prismarine_crystals", 5)), craft));
		recipe("end_stone_bricks", craft, "end_stone", 1);
		// dyed glass and concrete powder (dyes: provide yourself)
		for (String c : new String[]{"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
				"light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"}) {
			RECIPES.put(c + "_stained_glass", new Recipe(List.of(new Ingredient("glass", 1), new Ingredient(c + "_dye", 0.125)), craft));
			recipe(c + "_stained_glass_pane", craft, c + "_stained_glass", 0.375);
			RECIPES.put(c + "_concrete_powder", new Recipe(List.of(new Ingredient("sand", 0.5), new Ingredient("gravel", 0.5),
					new Ingredient(c + "_dye", 0.125)), craft));
		}
		recipe("crafting_table", craft, "oak_planks", 4);
		recipe("chest", craft, "oak_planks", 8);
		recipe("barrel", craft, "oak_planks", 7);
		recipe("furnace", craft, "cobblestone", 8);
		recipe("ladder", craft, "stick", 7.0 / 3);

		for (String w : WOODS) {
			wood(w, w + "_log", w + "_wood");
		}
		for (String w : STEMS) {
			wood(w, w + "_stem", w + "_hyphae");
		}
	}

	private static void wood(String w, String log, String wood) {
		TERMINALS.put(log, new Terminal(item(log), Set.of(block(log)), 1));
		String planks = w + "_planks";
		recipe(planks, "craft", log, 0.25);
		recipe(wood, "craft", log, 4.0 / 3);
		recipe(w + "_fence", "craft", planks, 5.0 / 3);
		recipe(w + "_fence_gate", "craft", planks, 4);
		recipe(w + "_door", "craft", planks, 2);
		recipe(w + "_trapdoor", "craft", planks, 3);
		recipe(w + "_button", "craft", planks, 1);
		recipe(w + "_pressure_plate", "craft", planks, 2);
		recipe(w + "_sign", "craft", planks, 13.0 / 6);
	}

	private static void terminal(String key, Item display, double yield, Block... sources) {
		TERMINALS.put(key, new Terminal(display, new LinkedHashSet<>(List.of(sources)), yield));
	}

	private static void recipe(String out, String note, String in, double ratio) {
		RECIPES.put(out, new Recipe(List.of(new Ingredient(in, ratio)), note));
	}

	private static Identifier id(String path) {
		return Identifier.withDefaultNamespace(path);
	}

	private static Item item(String path) {
		return BuiltInRegistries.ITEM.getValue(id(path));
	}

	private static Block block(String path) {
		return BuiltInRegistries.BLOCK.getValue(id(path));
	}

	private static boolean itemExists(String path) {
		return BuiltInRegistries.ITEM.containsKey(id(path));
	}

	// ------------------------------------------------------------------ resolution

	private static final class Acc {
		final Map<String, Double> terminals = new LinkedHashMap<>();
		final Map<Item, Integer> craft = new LinkedHashMap<>();
		final Map<Item, String> craftNote = new LinkedHashMap<>();
		final Map<Item, Double> stepQty = new LinkedHashMap<>();
		final Map<Item, Boolean> stepSmelt = new LinkedHashMap<>();
		final Map<Item, Map<Item, Double>> stepInputs = new LinkedHashMap<>();

		void step(Item out, double qty, boolean smelt, Map<Item, Double> inputs) {
			// re-inserting at the end keeps the "ingredients first" order
			Double prev = stepQty.remove(out);
			stepQty.put(out, (prev == null ? 0 : prev) + qty);
			stepSmelt.put(out, smelt);
			stepInputs.put(out, inputs);
		}
		final Map<Item, Integer> manual = new LinkedHashMap<>();
		final Map<Item, Integer> forcedDirect = new LinkedHashMap<>();
		final Map<Item, Integer> stock = new LinkedHashMap<>();
		final Map<Item, Integer> usedStock = new LinkedHashMap<>();

		/** Consumes the available stock for {@code item}; returns the amount still to obtain. */
		double consume(Item item, double qty) {
			int have = stock.getOrDefault(item, 0);
			if (have <= 0 || qty <= 0) return qty;
			int take = (int) Math.min(have, Math.ceil(qty - 1e-9));
			stock.put(item, have - take);
			usedStock.merge(item, take, Integer::sum);
			return Math.max(0, qty - take);
		}
	}

	/**
	 * @param wanted wanted items and amounts
	 * @param forceDirect items the user wants searched as is in the world
	 */
	public static Plan resolve(Map<Item, Integer> wanted, Set<Item> forceDirect) {
		return resolve(wanted, forceDirect, Map.of());
	}

	/**
	 * @param stock known chest contents: subtracted at every level (final item,
	 *              intermediates such as planks, then raw materials such as logs)
	 */
	public static Plan resolve(Map<Item, Integer> wanted, Set<Item> forceDirect, Map<Item, Integer> stock) {
		Acc acc = new Acc();
		acc.stock.putAll(stock);
		for (Map.Entry<Item, Integer> e : wanted.entrySet()) {
			Item it = e.getKey();
			if (forceDirect.contains(it)) {
				int left = (int) Math.ceil(acc.consume(it, e.getValue()));
				if (left > 0) acc.forcedDirect.merge(it, left, Integer::sum);
				continue;
			}
			resolveItem(acc, it, e.getValue(), 0);
		}

		Plan plan = new Plan();
		for (Map.Entry<String, Double> e : acc.terminals.entrySet()) {
			Terminal t = terminalFor(e.getKey());
			int blocks = (int) Math.ceil(e.getValue() / t.yieldPerBlock - 1e-9);
			if (blocks <= 0) continue;
			plan.targets.add(new Target(t.display.getName().getString(), new ItemStack(t.display), t.sources, blocks));
		}
		for (Map.Entry<Item, Integer> e : acc.forcedDirect.entrySet()) {
			Block b = Block.byItem(e.getKey());
			if (b == Blocks.AIR) {
				plan.manual.add(new Line(e.getKey(), e.getValue(), "not a block"));
				continue;
			}
			plan.targets.add(new Target(e.getKey().getName().getString(), new ItemStack(e.getKey()), Set.of(b), e.getValue()));
		}
		acc.craft.forEach((it, n) -> plan.toCraft.add(new Line(it, n, acc.craftNote.getOrDefault(it, ""))));
		acc.stepQty.forEach((it, q) -> plan.steps.add(new Step(it, (int) Math.ceil(q - 1e-9), acc.stepSmelt.get(it), acc.stepInputs.get(it))));
		acc.usedStock.forEach((it, n) -> plan.fromStock.add(new Line(it, n, "in stock")));
		acc.manual.forEach((it, n) -> plan.manual.add(new Line(it, n, "")));
		return plan;
	}

	private static Terminal terminalFor(String key) {
		Terminal t = TERMINALS.get(key);
		if (t != null) return t;
		// natural block that drops itself
		return new Terminal(item(key), Set.of(block(key)), 1);
	}

	private static void resolveItem(Acc acc, Item item, double qty, int depth) {
		qty = acc.consume(item, qty);
		if (qty <= 1e-9) return;
		Identifier key = BuiltInRegistries.ITEM.getKey(item);
		if (!"minecraft".equals(key.getNamespace()) || depth > 8) {
			if (depth == 0) acc.manual.merge(item, (int) Math.ceil(qty), Integer::sum);
			return;
		}
		if (!resolvePath(acc, key.getPath(), qty, depth)) {
			if (depth == 0) {
				acc.manual.merge(item, (int) Math.ceil(qty), Integer::sum);
			} else {
				// an unknown intermediate ingredient: report it too
				acc.manual.merge(item, (int) Math.ceil(qty), Integer::sum);
			}
		}
	}

	private static boolean resolvePath(Acc acc, String path, double qty, int depth) {
		if (TERMINALS.containsKey(path)) {
			acc.terminals.merge(path, qty, Double::sum);
			return true;
		}
		if (NATURAL.contains(path)) {
			acc.terminals.merge(path, qty, Double::sum);
			return true;
		}
		Recipe r = RECIPES.get(path);
		if (r != null) {
			Map<Item, Double> inputs = new LinkedHashMap<>();
			for (Ingredient ing : r.ingredients) {
				if (!itemExists(ing.item)) return false;
				inputs.put(item(ing.item), ing.ratio);
				resolveItem(acc, item(ing.item), qty * ing.ratio, depth + 1);
			}
			acc.step(item(path), qty, r.note.equals("smelt"), inputs);
			if (depth == 0) {
				Item it = item(path);
				acc.craft.merge(it, (int) Math.ceil(qty), Integer::sum);
				acc.craftNote.put(it, r.note);
			}
			return true;
		}
		// stairs / slabs / walls: go back to the base block
		String[][] shapes = {{"_stairs", "1.5"}, {"_slab", "0.5"}, {"_wall", "1"}};
		for (String[] s : shapes) {
			if (!path.endsWith(s[0])) continue;
			String base = path.substring(0, path.length() - s[0].length());
			for (String cand : new String[]{base + "_planks", base + "s", base + "_block", base}) {
				if (!itemExists(cand) || Block.byItem(item(cand)) == Blocks.AIR) continue;
				resolveItem(acc, item(cand), qty * Double.parseDouble(s[1]), depth + 1);
				acc.step(item(path), qty, false, Map.of(item(cand), Double.parseDouble(s[1])));
				if (depth == 0) {
					Item it = item(path);
					acc.craft.merge(it, (int) Math.ceil(qty), Integer::sum);
					acc.craftNote.put(it, "craft");
				}
				return true;
			}
		}
		return false;
	}
}
