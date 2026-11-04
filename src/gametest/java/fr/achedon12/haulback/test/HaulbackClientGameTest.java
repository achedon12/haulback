package fr.achedon12.haulback.test;

import fr.achedon12.haulback.Collector;
import fr.achedon12.haulback.HaulbackConfig;
import fr.achedon12.haulback.job.Materials;
import fr.achedon12.haulback.job.SchematicReader;
import fr.achedon12.haulback.job.Target;
import fr.achedon12.haulback.ui.HaulbackScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * End-to-end scenarios in a flat world:
 * 1. gather 6 logs placed 12 blocks away, store them in the chest, return to point A;
 * 2. build a 3x3 floor from a .schem, taking the planks from the chest.
 */
public class HaulbackClientGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try {
			run(ctx);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void run(ClientGameTestContext ctx) throws Exception {
		// light settings: CI runners render in software, a heavy first frame times the world load out
		ctx.runOnClient(mc -> {
			mc.options.renderDistance().set(2);
			mc.options.simulationDistance().set(5);
			mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
			mc.options.particles().set(net.minecraft.server.level.ParticleStatus.MINIMAL);
			mc.options.entityShadows().set(false);
			mc.options.framerateLimit().set(30);
		});
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			for (String cmd : List.of("difficulty peaceful", "gamemode survival @a", "time set day",
					"give @a bread 16", "give @a iron_axe", "give @a iron_pickaxe")) {
				sp.getServer().runCommand(cmd);
			}
			ctx.waitTicks(20);
			checkPlanning();
			BlockPos home = sp.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().get(0).blockPosition());
			BlockPos chest = home.offset(3, 0, 1);
			sp.getServer().runOnServer(s -> {
				ServerLevel lvl = s.overworld();
				lvl.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
				for (int z = -2; z <= 2; z++) {
					for (int y = 0; y < 2; y++) {
						lvl.setBlockAndUpdate(home.offset(12, y, z), Blocks.OAK_LOG.defaultBlockState());
					}
				}
			});
			ctx.waitTicks(20);

			// --- 0. no tools at all: it must craft a pickaxe, axe and sword before mining stone
			sp.getServer().runCommand("clear @a");
			sp.getServer().runCommand("give @a bread 16");
			sp.getServer().runOnServer(s -> {
				ServerLevel lvl = s.overworld();
				for (int t = 0; t < 2; t++) {
					for (int y = 0; y < 3; y++) lvl.setBlockAndUpdate(home.offset(-8, y, -3 + t * 6), Blocks.OAK_LOG.defaultBlockState());
				}
				for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) {
					lvl.setBlockAndUpdate(home.offset(-6 + x, -1, 6 + z), Blocks.STONE.defaultBlockState());
				}
			});
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> HaulbackConfig.get().setChest(chest));
			ctx.runOnClient(mc -> Collector.get().start(
					List.of(new Target("Stone", new ItemStack(Items.STONE), Set.of(Blocks.STONE), 6)), null, null, null));
			ctx.waitTicks(200);
			ctx.takeScreenshot("haulback-hud-outils");
			ctx.waitFor(mc -> !Collector.get().active(), 20 * 240);
			String status0 = ctx.computeOnClient(mc -> Collector.get().status());
			String tools = ctx.computeOnClient(mc -> {
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < 36; i++) {
					ItemStack st = mc.player.getInventory().getItem(i);
					if (!st.isEmpty()) sb.append(st.getItem()).append(" x").append(st.getCount()).append(", ");
				}
				return sb.toString();
			});
			int cobble = sp.getServer().computeOnServer(s -> count(s, chest, Items.COBBLESTONE));
			System.out.println("[HAULBACK-TEST] tools: status=\"" + status0 + "\" stone_in_chest=" + cobble + " inventory=" + tools);
			boolean hasPick = ctx.computeOnClient(mc -> mc.player.getInventory().contains(st -> st.is(Items.STONE_PICKAXE)));
			boolean hasAxe = ctx.computeOnClient(mc -> mc.player.getInventory().contains(st -> st.is(Items.STONE_AXE)));
			boolean hasSword = ctx.computeOnClient(mc -> mc.player.getInventory().contains(st -> st.is(Items.STONE_SWORD)));
			if (!hasPick || !hasAxe || !hasSword) throw new AssertionError("Tools not crafted: " + tools + " (status: " + status0 + ")");
			if (cobble < 6) throw new AssertionError("Only " + cobble + " stone in the chest (status: " + status0 + ")");
			sp.getServer().runOnServer(s -> {
				if (s.overworld().getBlockEntity(chest) instanceof ChestBlockEntity c) c.clearContent();
			});

			// --- 1. gathering with two chests, one already holding 4 logs: it must cut only 2
			BlockPos chest2 = home.offset(3, 0, -3);
			sp.getServer().runOnServer(s -> {
				s.overworld().setBlockAndUpdate(chest2, Blocks.CHEST.defaultBlockState());
				if (s.overworld().getBlockEntity(chest2) instanceof ChestBlockEntity c) {
					c.setItem(0, new ItemStack(Items.OAK_LOG, 4));
					c.setChanged();
				}
			});
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> {
				HaulbackConfig.get().setChest(chest);
				HaulbackConfig.get().toggleChest(chest2, null);
			});
			ctx.setScreen(() -> HaulbackScreen.onBlocksTab(Map.of(Blocks.OAK_LOG, 6, Blocks.STONE, 32)));
			ctx.waitTicks(5);
			ctx.takeScreenshot("haulback-blocs");
			ctx.setScreen(HaulbackScreen::onOptionsTab);
			ctx.waitTicks(5);
			ctx.takeScreenshot("haulback-options");
			ctx.setScreen(() -> null);
			int logsBefore = sp.getServer().computeOnServer(s -> countLogs(s, home));
			ctx.runOnClient(mc -> Collector.get().start(List.of(Target.ofBlock(Blocks.OAK_LOG, 6)), null, null, null));
			ctx.waitTicks(80);
			ctx.takeScreenshot("haulback-hud-recolte");
			ctx.waitFor(mc -> !Collector.get().active(), 20 * 180);
			String status1 = ctx.computeOnClient(mc -> Collector.get().status());
			int needed = ctx.computeOnClient(mc -> Collector.get().targets().isEmpty() ? -1 : Collector.get().targets().get(0).needed);
			int logs = sp.getServer().computeOnServer(s -> count(s, chest, Items.OAK_LOG) + count(s, chest2, Items.OAK_LOG));
			int cut = logsBefore - sp.getServer().computeOnServer(s -> countLogs(s, home));
			System.out.println("[HAULBACK-TEST] stock: goal_after_deduction=" + needed + " logs_cut=" + cut);
			if (needed != 2) throw new AssertionError("Stock deduction failed: goal " + needed + " instead of 2");
			if (cut > 3) throw new AssertionError("Too many logs cut: " + cut);
			double dist = sp.getServer().computeOnServer(s -> Math.sqrt(s.getPlayerList().getPlayers().get(0).blockPosition().distSqr(home)));
			System.out.println("[HAULBACK-TEST] gathering: status=\"" + status1 + "\" logs_in_chests=" + logs + " distance_to_A=" + dist);
			if (logs < 6) throw new AssertionError("The chests only hold " + logs + " logs (status: " + status1 + ")");
			if (dist > 2) throw new AssertionError("The bot did not return to point A (distance " + dist + ")");

			// --- 2. building
			File schem = ctx.computeOnClient(mc -> writeFloorSchematic(new File(mc.gameDirectory, "schematics/haulback_test.schem")));
			SchematicReader.Result r = ctx.computeOnClient(mc -> SchematicReader.read(schem));
			Materials.Plan plan = Materials.resolve(r.items(), Set.of());
			System.out.println("[HAULBACK-TEST] schematic: " + r.items() + " -> targets="
					+ plan.targets.stream().map(t -> t.label + " x" + t.needed).toList()
					+ " to_craft=" + plan.toCraft.stream().map(l -> l.item() + " x" + l.count()).toList());
			sp.getServer().runOnServer(s -> {
				if (s.overworld().getBlockEntity(chest) instanceof ChestBlockEntity c) {
					c.setItem(10, new ItemStack(Items.OAK_PLANKS, 16));
					c.setChanged();
				}
			});
			BlockPos origin = home.offset(-1, 0, -7);
			File demo = ctx.computeOnClient(mc -> writeDemoSchematic(new File(mc.gameDirectory, "schematics/maison_demo.schem")));
			ctx.setScreen(() -> HaulbackScreen.onSchematicTab(demo));
			ctx.waitTicks(5);
			ctx.takeScreenshot("haulback-schematic-demo");
			ctx.setScreen(() -> HaulbackScreen.onSchematicTab(schem));
			ctx.waitTicks(5);
			ctx.takeScreenshot("haulback-schematic");
			ctx.setScreen(() -> null);
			ctx.runOnClient(mc -> Collector.get().start(List.of(), schem, origin, r.items().keySet()));
			ctx.waitTicks(100);
			ctx.takeScreenshot("haulback-hud-construction");
			ctx.waitFor(mc -> !Collector.get().active(), 20 * 180);
			String status2 = ctx.computeOnClient(mc -> Collector.get().status());
			int placed = sp.getServer().computeOnServer(s -> {
				int n = 0;
				for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
					if (s.overworld().getBlockState(origin.offset(x, 0, z)).is(Blocks.OAK_PLANKS)) n++;
				}
				return n;
			});
			System.out.println("[HAULBACK-TEST] build: status=\"" + status2 + "\" planks_placed=" + placed + "/9");
			ctx.takeScreenshot("haulback-fin");
			if (placed < 9) throw new AssertionError("Only " + placed + "/9 planks placed (status: " + status2 + ")");

			// --- 2b. workshop: glass (smelting) + stairs (crafting) from raw materials
			ctx.runOnClient(mc -> {
				HaulbackConfig.get().workshopTable = null;
				HaulbackConfig.get().workshopFurnaces.clear();
			});
			sp.getServer().runOnServer(s -> {
				for (BlockPos c : List.of(chest, chest2)) {
					if (s.overworld().getBlockEntity(c) instanceof ChestBlockEntity ce) ce.clearContent();
				}
				if (s.overworld().getBlockEntity(chest) instanceof ChestBlockEntity c) {
					c.setItem(0, new ItemStack(Items.SAND, 8));
					c.setItem(1, new ItemStack(Items.COAL, 3));
					c.setItem(2, new ItemStack(Items.OAK_LOG, 6));
					c.setItem(3, new ItemStack(Items.COBBLESTONE, 10));
					c.setChanged();
				}
			});
			File shop = ctx.computeOnClient(mc -> writeShopSchematic(new File(mc.gameDirectory, "schematics/vitrine.schem")));
			SchematicReader.Result shopData = ctx.computeOnClient(mc -> SchematicReader.read(shop));
			BlockPos shopOrigin = home.offset(4, 0, -9);
			ctx.runOnClient(mc -> Collector.get().start(
					stock -> fr.achedon12.haulback.job.SchematicPlanner.plan(shopData.items(), Set.of(), false, stock),
					true, shop, shopOrigin, shopData.items().keySet()));
			ctx.waitTicks(200);
			ctx.takeScreenshot("haulback-hud-atelier");
			ctx.waitFor(mc -> !Collector.get().active(), 20 * 300);
			String statusA = ctx.computeOnClient(mc -> Collector.get().status());
			int[] shopPlaced = sp.getServer().computeOnServer(s -> {
				int glass = 0, stairs = 0;
				for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
					var st = s.overworld().getBlockState(shopOrigin.offset(x, 0, z));
					if (st.is(Blocks.GLASS)) glass++;
					if (st.is(Blocks.OAK_STAIRS)) stairs++;
				}
				return new int[]{glass, stairs};
			});
			System.out.println("[HAULBACK-TEST] workshop: status=\"" + statusA + "\" glass=" + shopPlaced[0] + "/5 stairs=" + shopPlaced[1] + "/4");
			if (shopPlaced[0] < 5 || shopPlaced[1] < 4) throw new AssertionError("Workshop incomplete: glass " + shopPlaced[0] + "/5, stairs " + shopPlaced[1] + "/4 (status: " + statusA + ")");

			// --- 2c. iron pickaxe: ask for diamond with only stone tools
			sp.getServer().runOnServer(s -> {
				ServerLevel lvl = s.overworld();
				for (int i = 0; i < 4; i++) lvl.setBlockAndUpdate(home.offset(-10 + i, -1, -12), Blocks.IRON_ORE.defaultBlockState());
				lvl.setBlockAndUpdate(home.offset(-5, -1, -12), Blocks.COAL_ORE.defaultBlockState());
				lvl.setBlockAndUpdate(home.offset(-4, -1, -12), Blocks.COAL_ORE.defaultBlockState());
				lvl.setBlockAndUpdate(home.offset(-2, -1, -12), Blocks.DIAMOND_ORE.defaultBlockState());
				for (int y = 0; y < 3; y++) lvl.setBlockAndUpdate(home.offset(-12, y, -6), Blocks.OAK_LOG.defaultBlockState());
			});
			ctx.runOnClient(mc -> Collector.get().start(List.of(Target.ofBlock(Blocks.DIAMOND_ORE, 1)), null, null, null));
			ctx.waitFor(mc -> !Collector.get().active(), 20 * 300);
			String statusB = ctx.computeOnClient(mc -> Collector.get().status());
			boolean ironPick = ctx.computeOnClient(mc -> mc.player.getInventory().contains(st -> st.is(Items.IRON_PICKAXE)));
			int diamonds = sp.getServer().computeOnServer(s -> count(s, chest, Items.DIAMOND) + count(s, chest2, Items.DIAMOND));
			System.out.println("[HAULBACK-TEST] iron: status=\"" + statusB + "\" iron_pickaxe=" + ironPick + " diamonds_in_chest=" + diamonds);
			if (!ironPick || diamonds < 1) throw new AssertionError("Iron pickaxe chain incomplete (status: " + statusB + ")");

			// --- 3. combat: a zombie up close and a skeleton at range
			sp.getServer().runCommand("time set 18000");
			sp.getServer().runCommand("difficulty easy");
			sp.getServer().runCommand("give @a bread 8");
			BlockPos here = sp.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().get(0).blockPosition());
			sp.getServer().runCommand("summon zombie " + (here.getX() + 4) + " " + here.getY() + " " + here.getZ());
			sp.getServer().runCommand("summon skeleton " + (here.getX() - 12) + " " + here.getY() + " " + here.getZ());
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> Collector.get().start(
					List.of(new Target("Oak log", new ItemStack(Items.OAK_LOG), Set.of(Blocks.OAK_LOG), 3)), null, null, null));
			ctx.waitTicks(40);
			ctx.takeScreenshot("haulback-combat");
			ctx.waitFor(mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
					mc.player.getBoundingBox().inflate(32)).isEmpty() || !Collector.get().active(), 20 * 60);
			String aliveList = sp.getServer().computeOnServer(s -> {
				StringBuilder sb = new StringBuilder();
				var pl = s.getPlayerList().getPlayers().get(0);
				for (var m : s.overworld().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, new net.minecraft.world.phys.AABB(here).inflate(40))) {
					sb.append(m.getType().toShortString()).append(" at ").append((int) m.distanceTo(pl)).append("m seen=").append(pl.hasLineOfSight(m)).append("; ");
				}
				return sb.toString();
			});
			System.out.println("[HAULBACK-TEST] left: " + aliveList);
			int alive = sp.getServer().computeOnServer(s -> s.overworld().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
					new net.minecraft.world.phys.AABB(here).inflate(40)).size());
			float hp = ctx.computeOnClient(mc -> mc.player.getHealth());
			System.out.println("[HAULBACK-TEST] combat: mobs_left=" + alive + " hp=" + hp);
			ctx.waitFor(mc -> !Collector.get().active(), 20 * 120);
			if (alive > 0) throw new AssertionError("Still " + alive + " mob(s) left after combat");

			// --- 4. a lone skeleton, at range
			BlockPos here2 = sp.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().get(0).blockPosition());
			sp.getServer().runCommand("summon skeleton " + (here2.getX() - 12) + " " + here2.getY() + " " + here2.getZ()
					+ " {ArmorItems:[{},{},{},{id:\"minecraft:leather_helmet\",count:1}]}");
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> Collector.get().start(
					List.of(new Target("Oak log", new ItemStack(Items.OAK_LOG), Set.of(Blocks.OAK_LOG), 2)), null, null, null));
			ctx.waitFor(mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.skeleton.AbstractSkeleton.class,
					mc.player.getBoundingBox().inflate(40)).isEmpty(), 20 * 60);
			int skel = sp.getServer().computeOnServer(s -> s.overworld().getEntitiesOfClass(net.minecraft.world.entity.monster.skeleton.AbstractSkeleton.class,
					new net.minecraft.world.phys.AABB(here2).inflate(40)).size());
			float hp2 = ctx.computeOnClient(mc -> mc.player.getHealth());
			System.out.println("[HAULBACK-TEST] skeleton: left=" + skel + " hp=" + hp2);
			ctx.waitFor(mc -> !Collector.get().active(), 20 * 120);
			if (skel > 0) throw new AssertionError("The skeleton is still alive");

			// --- 5. netherite planning, and piglins are left alone when wearing gold
			checkNetherPlanning();
			sp.getServer().runCommand("item replace entity @a armor.head with golden_helmet");
			BlockPos here3 = sp.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().get(0).blockPosition());
			sp.getServer().runCommand("summon piglin " + (here3.getX() + 3) + " " + here3.getY() + " " + here3.getZ()
					+ " {IsImmuneToZombification:1b,PersistenceRequired:1b}");
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> Collector.get().start(List.of(Target.ofBlock(Blocks.OAK_LOG, 1)), null, null, null));
			ctx.waitTicks(200);
			int piglinHurt = sp.getServer().computeOnServer(s -> {
				var list = s.overworld().getEntitiesOfClass(net.minecraft.world.entity.monster.piglin.Piglin.class,
						new net.minecraft.world.phys.AABB(here3).inflate(30));
				if (list.isEmpty()) return -1;
				return list.get(0).getHealth() < list.get(0).getMaxHealth() ? 1 : 0;
			});
			ctx.runOnClient(mc -> Collector.get().stop("test"));
			System.out.println("[HAULBACK-TEST] piglin: hurt=" + piglinHurt);
			if (piglinHurt != 0) throw new AssertionError("The bot attacked a piglin while wearing gold (" + piglinHurt + ")");
		}
	}

	/** Planning only: netherite ingots resolve to ancient debris (smelted) and gold. */
	private static void checkNetherPlanning() {
		Materials.Plan p = Materials.resolve(Map.of(Items.NETHERITE_INGOT, 1), Set.of());
		List<Item> steps = p.steps.stream().map(Materials.Step::item).toList();
		boolean debris = p.targets.stream().anyMatch(t -> t.sources.contains(Blocks.ANCIENT_DEBRIS) && t.needed == 4);
		boolean gold = p.targets.stream().anyMatch(t -> t.sources.contains(Blocks.GOLD_ORE));
		System.out.println("[HAULBACK-TEST] netherite planning: targets=" + p.targets.stream().map(t -> t.label + " x" + t.needed).toList() + " steps=" + steps);
		if (!debris || !gold || !steps.contains(Items.NETHERITE_SCRAP) || !steps.contains(Items.NETHERITE_INGOT)) {
			throw new AssertionError("Netherite planning is wrong");
		}
	}

	/** Planning only: the recipe table resolves the newer vanilla families down to raw materials. */
	private static void checkPlanning() {
		Materials.Plan p = Materials.resolve(Map.of(Items.CUT_COPPER, 4, Items.MUD_BRICKS, 4, Items.LIGHT_BLUE_STAINED_GLASS, 8), Set.of());
		List<String> targets = p.targets.stream().map(t -> t.stockItem == null ? t.label : t.label).toList();
		List<Item> steps = p.steps.stream().map(Materials.Step::item).toList();
		List<Item> manual = p.manual.stream().map(Materials.Line::item).toList();
		System.out.println("[HAULBACK-TEST] planning: targets=" + targets + " steps=" + steps + " manual=" + manual);
		boolean copper = p.targets.stream().anyMatch(t -> t.sources.contains(Blocks.COPPER_ORE));
		boolean mud = p.targets.stream().anyMatch(t -> t.sources.contains(Blocks.MUD));
		boolean sand = p.targets.stream().anyMatch(t -> t.sources.contains(Blocks.SAND));
		if (!copper || !mud || !sand) throw new AssertionError("Planning did not reach copper ore, mud and sand: " + targets);
		if (!steps.contains(Items.CUT_COPPER) || !steps.contains(Items.MUD_BRICKS) || !steps.contains(Items.LIGHT_BLUE_STAINED_GLASS)) {
			throw new AssertionError("Missing workshop steps: " + steps);
		}
		if (!manual.contains(Items.WHEAT) || !manual.contains(Items.LIGHT_BLUE_DYE)) {
			throw new AssertionError("Wheat and dye should be listed as provide-yourself: " + manual);
		}
	}

	private static int countLogs(MinecraftServer s, BlockPos home) {
		int n = 0;
		for (BlockPos p : BlockPos.betweenClosed(home.offset(-20, 0, -20), home.offset(20, 4, 20))) {
			if (s.overworld().getBlockState(p).is(Blocks.OAK_LOG)) n++;
		}
		return n;
	}

	private static int count(MinecraftServer s, BlockPos chest, Item item) {
		int n = 0;
		if (s.overworld().getBlockEntity(chest) instanceof ChestBlockEntity c) {
			for (int i = 0; i < c.getContainerSize(); i++) {
				if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
			}
		}
		return n;
	}

	/** 3x1x3 shop window: 5 glass and 4 oak stairs. */
	private static File writeShopSchematic(File f) throws Exception {
		f.getParentFile().mkdirs();
		byte[] data = new byte[9];
		for (int i = 0; i < 9; i++) data[i] = (byte) (i % 2 == 0 ? 0 : 1);
		CompoundTag root = new CompoundTag();
		root.putInt("Version", 2);
		root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
		root.putShort("Width", (short) 3);
		root.putShort("Height", (short) 1);
		root.putShort("Length", (short) 3);
		CompoundTag palette = new CompoundTag();
		palette.putInt("minecraft:glass", 0);
		palette.putInt("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]", 1);
		root.put("Palette", palette);
		root.putInt("PaletteMax", 2);
		root.putByteArray("BlockData", data);
		NbtIo.writeCompressed(root, f.toPath());
		return f;
	}

	/** Small demo house (several materials) for the screenshot. */
	private static File writeDemoSchematic(File f) throws Exception {
		f.getParentFile().mkdirs();
		String[] pal = {"minecraft:air", "minecraft:oak_planks", "minecraft:cobblestone", "minecraft:glass_pane",
				"minecraft:stone_bricks", "minecraft:oak_stairs", "minecraft:spruce_log", "minecraft:torch",
				"minecraft:white_wool", "minecraft:iron_block"};
		int w = 7, h = 5, l = 7;
		byte[] data = new byte[w * h * l];
		for (int y = 0; y < h; y++) for (int z = 0; z < l; z++) for (int x = 0; x < w; x++) {
			boolean edge = x == 0 || z == 0 || x == w - 1 || z == l - 1;
			boolean corner = (x == 0 || x == w - 1) && (z == 0 || z == l - 1);
			int v;
			if (y == 0) v = 2;
			else if (y == h - 1) v = 5;
			else if (corner) v = 6;
			else if (edge && y == 2 && (x == 3 || z == 3)) v = 3;
			else if (edge) v = y == 1 ? 4 : 1;
			else if (y == 1 && x == 3 && z == 3) v = 9;
			else if (y == 1 && x == 1 && z == 1) v = 8;
			else if (y == 2 && x == 1 && z == 5) v = 7;
			else v = 0;
			data[(y * l + z) * w + x] = (byte) v;
		}
		CompoundTag root = new CompoundTag();
		root.putInt("Version", 2);
		root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
		root.putShort("Width", (short) w);
		root.putShort("Height", (short) h);
		root.putShort("Length", (short) l);
		CompoundTag palette = new CompoundTag();
		for (int i = 0; i < pal.length; i++) palette.putInt(pal[i], i);
		root.put("Palette", palette);
		root.putInt("PaletteMax", pal.length);
		root.putByteArray("BlockData", data);
		NbtIo.writeCompressed(root, f.toPath());
		return f;
	}

	/** Sponge v2 schematic: 3x1x3 floor of oak planks. */
	private static File writeFloorSchematic(File f) throws Exception {
		f.getParentFile().mkdirs();
		CompoundTag root = new CompoundTag();
		root.putInt("Version", 2);
		root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
		root.putShort("Width", (short) 3);
		root.putShort("Height", (short) 1);
		root.putShort("Length", (short) 3);
		CompoundTag palette = new CompoundTag();
		palette.putInt("minecraft:oak_planks", 0);
		root.put("Palette", palette);
		root.putInt("PaletteMax", 1);
		root.putByteArray("BlockData", new byte[9]);
		NbtIo.writeCompressed(root, f.toPath());
		return f;
	}
}
