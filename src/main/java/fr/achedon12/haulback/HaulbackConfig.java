package fr.achedon12.haulback;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persistent settings, stored in config/haulback.json. */
public final class HaulbackConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("haulback.json");

	/** Legacy field (single chest), migrated to {@link #chests}. */
	public int[] chest;
	/** Storage / stock chests. */
	public List<int[]> chests = new ArrayList<>();
	/** Tools to craft when missing (a tool already present is never crafted again). */
	public boolean toolPickaxe = true;
	public boolean toolAxe = true;
	public boolean toolSword = true;
	public boolean toolShovel = false;
	/** Material of crafted tools: 0 = wood, 1 = stone. */
	public int toolTier = 1;
	/** Fight hostile mobs. */
	public boolean fight = true;

	/** Workshop placed by the bot (crafting table and furnaces), reused from one mission to the next. */
	public int[] workshopTable;
	public List<int[]> workshopFurnaces = new ArrayList<>();

	/** Maximum radius around the starting point, in blocks. */
	public int maxRadius = 192;
	/** Below this health (out of 20), the bot goes home. */
	public int retreatHealth = 10;
	/** Below this hunger level (out of 20), the bot eats if it has food. */
	public int eatBelowFood = 14;
	/** Number of "throwaway" blocks (dirt, stone...) kept so Baritone can bridge. */
	public int keepThrowaway = 64;

	private static HaulbackConfig instance;

	public static HaulbackConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static HaulbackConfig load() {
		try {
			if (Files.exists(FILE)) {
				HaulbackConfig c = GSON.fromJson(Files.readString(FILE), HaulbackConfig.class);
				if (c != null) {
					if (c.chests == null) c.chests = new ArrayList<>();
					if (c.workshopFurnaces == null) c.workshopFurnaces = new ArrayList<>();
					if (c.chest != null && c.chests.isEmpty()) c.chests.add(c.chest);
					c.chest = null;
					return c;
				}
			}
		} catch (IOException | RuntimeException e) {
			HaulbackClient.LOG.warn("Cannot read {}, using defaults", FILE, e);
		}
		return new HaulbackConfig();
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			HaulbackClient.LOG.warn("Cannot write {}", FILE, e);
		}
	}

	public List<BlockPos> chestList() {
		List<BlockPos> out = new ArrayList<>();
		for (int[] c : chests) {
			if (c != null && c.length == 3) out.add(new BlockPos(c[0], c[1], c[2]));
		}
		return out;
	}

	/** First chest (or null). */
	public BlockPos chestPos() {
		List<BlockPos> l = chestList();
		return l.isEmpty() ? null : l.get(0);
	}

	/** Replaces the list with a single chest. */
	public void setChest(BlockPos pos) {
		chests.clear();
		if (pos != null) chests.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
		save();
	}

	/**
	 * Adds the chest if it is not in the list, removes it otherwise.
	 * {@code partner} is the other half of a double chest (or null): it counts as the same chest.
	 *
	 * @return true if the chest was added
	 */
	public boolean toggleChest(BlockPos pos, BlockPos partner) {
		List<BlockPos> l = chestList();
		if (l.contains(pos) || (partner != null && l.contains(partner))) {
			chests.removeIf(c -> {
				BlockPos b = new BlockPos(c[0], c[1], c[2]);
				return b.equals(pos) || b.equals(partner);
			});
			save();
			return false;
		}
		chests.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
		save();
		return true;
	}

	public void clearChests() {
		chests.clear();
		save();
	}
}
