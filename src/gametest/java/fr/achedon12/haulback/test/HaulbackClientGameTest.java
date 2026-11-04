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
 * Scenario de bout en bout dans un monde plat :
 * 1. recolte de 6 buches posees a 12 blocs, depot dans le coffre, retour au point A ;
 * 2. construction d'un plancher 3x3 a partir d'un .schem, en prenant les planches dans le coffre.
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
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			for (String cmd : List.of("difficulty peaceful", "gamemode survival @à", "time set day",
					"give @à bread 16", "give @à iron_axe", "give @à iron_pickaxe")) {
				sp.getServer().runCommand(cmd);
			}
			ctx.waitTicks(20);
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

			// --- 0. sans aucun outil : il doit fabriquer pioche, hache, epee avant de miner la pierre
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
					List.of(new Target("Pierre", new ItemStack(Items.STONE), Set.of(Blocks.STONE), 6)), null, null, null));
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
			System.out.println("[HAULBACK-TEST] outils : statut=\"" + status0 + "\" pierre_dans_coffre=" + cobble + " inventaire=" + tools);
			boolean hasPick = ctx.computeOnClient(mc -> mc.player.getInventory().contains(st -> st.is(Items.STONE_PICKAXE)));
			boolean hasAxe = ctx.computeOnClient(mc -> mc.player.getInventory().contains(st -> st.is(Items.STONE_AXE)));
			boolean hasSword = ctx.computeOnClient(mc -> mc.player.getInventory().contains(st -> st.is(Items.STONE_SWORD)));
			if (!hasPick || !hasAxe || !hasSword) throw new AssertionError("Outils non fabriqués : " + tools + " (statut : " + status0 + ")");
			if (cobble < 6) throw new AssertionError("Seulement " + cobble + " pierres au coffre (statut : " + status0 + ")");
			sp.getServer().runOnServer(s -> {
				if (s.overworld().getBlockEntity(chest) instanceof ChestBlockEntity c) c.clearContent();
			});

			// --- 1. recolte avec deux coffres, dont un contient deja 4 buches : il ne doit en couper que 2
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
			System.out.println("[HAULBACK-TEST] stock : objectif_apres_deduction=" + needed + " buches_coupees=" + cut);
			if (needed != 2) throw new AssertionError("La deduction du stock n'a pas fonctionne : objectif " + needed + " au lieu de 2");
			if (cut > 3) throw new AssertionError("Trop de buches coupees : " + cut);
			double dist = sp.getServer().computeOnServer(s -> Math.sqrt(s.getPlayerList().getPlayers().get(0).blockPosition().distSqr(home)));
			System.out.println("[HAULBACK-TEST] récolte : statut=\"" + status1 + "\" buches_dans_coffre=" + logs + " distance_point_A=" + dist);
			if (logs < 6) throw new AssertionError("Les coffres ne contiennent que " + logs + " buches (statut : " + status1 + ")");
			if (dist > 2) throw new AssertionError("Le bot n'est pas revenu au point A (distance " + dist + ")");

			// --- 2. construction
			File schem = ctx.computeOnClient(mc -> writeFloorSchematic(new File(mc.gameDirectory, "schematics/haulback_test.schem")));
			SchematicReader.Result r = ctx.computeOnClient(mc -> SchematicReader.read(schem));
			Materials.Plan plan = Materials.resolve(r.items(), Set.of());
			System.out.println("[HAULBACK-TEST] schematic : " + r.items() + " -> cibles="
					+ plan.targets.stream().map(t -> t.label + " x" + t.needed).toList()
					+ " à_fabriquer=" + plan.toCraft.stream().map(l -> l.item() + " x" + l.count()).toList());
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
			System.out.println("[HAULBACK-TEST] construction : statut=\"" + status2 + "\" planches_posees=" + placed + "/9");
			ctx.takeScreenshot("haulback-fin");
			if (placed < 9) throw new AssertionError("Seulement " + placed + "/9 planches posees (statut : " + status2 + ")");

			// --- 3. combat : un zombie au contact et un squelette a distance
			sp.getServer().runCommand("time set 18000");
			sp.getServer().runCommand("difficulty easy");
			sp.getServer().runCommand("give @a bread 8");
			BlockPos here = sp.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().get(0).blockPosition());
			sp.getServer().runCommand("summon zombie " + (here.getX() + 4) + " " + here.getY() + " " + here.getZ());
			sp.getServer().runCommand("summon skeleton " + (here.getX() - 12) + " " + here.getY() + " " + here.getZ());
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> Collector.get().start(
					List.of(new Target("Buche de chene", new ItemStack(Items.OAK_LOG), Set.of(Blocks.OAK_LOG), 3)), null, null, null));
			ctx.waitTicks(40);
			ctx.takeScreenshot("haulback-combat");
			ctx.waitFor(mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
					mc.player.getBoundingBox().inflate(32)).isEmpty() || !Collector.get().active(), 20 * 60);
			String aliveList = sp.getServer().computeOnServer(s -> {
				StringBuilder sb = new StringBuilder();
				var pl = s.getPlayerList().getPlayers().get(0);
				for (var m : s.overworld().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, new net.minecraft.world.phys.AABB(here).inflate(40))) {
					sb.append(m.getType().toShortString()).append(" a ").append((int) m.distanceTo(pl)).append("m vue=").append(pl.hasLineOfSight(m)).append("; ");
				}
				return sb.toString();
			});
			System.out.println("[HAULBACK-TEST] restants : " + aliveList);
			int alive = sp.getServer().computeOnServer(s -> s.overworld().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
					new net.minecraft.world.phys.AABB(here).inflate(40)).size());
			float hp = ctx.computeOnClient(mc -> mc.player.getHealth());
			System.out.println("[HAULBACK-TEST] combat : monstres_restants=" + alive + " pv=" + hp);
			ctx.waitFor(mc -> !Collector.get().active(), 20 * 120);
			if (alive > 0) throw new AssertionError("Il reste " + alive + " monstre(s) apres le combat");

			// --- 4. squelette seul, a distance
			BlockPos here2 = sp.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().get(0).blockPosition());
			sp.getServer().runCommand("summon skeleton " + (here2.getX() - 12) + " " + here2.getY() + " " + here2.getZ()
					+ " {ArmorItems:[{},{},{},{id:\"minecraft:leather_helmet\",count:1}]}");
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> Collector.get().start(
					List.of(new Target("Buche de chene", new ItemStack(Items.OAK_LOG), Set.of(Blocks.OAK_LOG), 2)), null, null, null));
			ctx.waitFor(mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.skeleton.AbstractSkeleton.class,
					mc.player.getBoundingBox().inflate(40)).isEmpty(), 20 * 60);
			int skel = sp.getServer().computeOnServer(s -> s.overworld().getEntitiesOfClass(net.minecraft.world.entity.monster.skeleton.AbstractSkeleton.class,
					new net.minecraft.world.phys.AABB(here2).inflate(40)).size());
			float hp2 = ctx.computeOnClient(mc -> mc.player.getHealth());
			System.out.println("[HAULBACK-TEST] squelette : restants=" + skel + " pv=" + hp2);
			ctx.waitFor(mc -> !Collector.get().active(), 20 * 120);
			if (skel > 0) throw new AssertionError("Le squelette est toujours en vie");
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

	/** Petite maison de demo (plusieurs materiaux) pour la capture d'ecran. */
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

	/** Schematic Sponge v2 : plancher 3x1x3 de planches de chene. */
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
