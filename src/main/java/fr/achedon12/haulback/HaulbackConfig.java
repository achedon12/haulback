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

/** Reglages persistants, stockes dans config/haulback.json. */
public final class HaulbackConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("haulback.json");

	/** Ancien champ (un seul coffre), migre vers {@link #chests}. */
	public int[] chest;
	/** Coffres de depot / de stock. */
	public List<int[]> chests = new ArrayList<>();
	/** Outils a fabriquer s'ils manquent (un outil deja present n'est jamais refabrique). */
	public boolean toolPickaxe = true;
	public boolean toolAxe = true;
	public boolean toolSword = true;
	public boolean toolShovel = false;
	/** Materiau des outils fabriques : 0 = bois, 1 = pierre. */
	public int toolTier = 1;
	/** Se defendre contre les monstres. */
	public boolean fight = true;

	/** Rayon maximum autour du point de depart, en blocs. */
	public int maxRadius = 192;
	/** En dessous de ces PV (sur 20), le bot rentre a la base. */
	public int retreatHealth = 10;
	/** En dessous de ce niveau de faim (sur 20), le bot mange s'il a de quoi. */
	public int eatBelowFood = 14;
	/** Nombre de blocs "jetables" (terre, pierre...) gardes pour que Baritone puisse construire des ponts. */
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
					if (c.chest != null && c.chests.isEmpty()) c.chests.add(c.chest);
					c.chest = null;
					return c;
				}
			}
		} catch (IOException | RuntimeException e) {
			HaulbackClient.LOG.warn("Lecture de {} impossible, valeurs par defaut", FILE, e);
		}
		return new HaulbackConfig();
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			HaulbackClient.LOG.warn("Ecriture de {} impossible", FILE, e);
		}
	}

	public List<BlockPos> chestList() {
		List<BlockPos> out = new ArrayList<>();
		for (int[] c : chests) {
			if (c != null && c.length == 3) out.add(new BlockPos(c[0], c[1], c[2]));
		}
		return out;
	}

	/** Premier coffre (ou null). */
	public BlockPos chestPos() {
		List<BlockPos> l = chestList();
		return l.isEmpty() ? null : l.get(0);
	}

	/** Remplace la liste par un seul coffre. */
	public void setChest(BlockPos pos) {
		chests.clear();
		if (pos != null) chests.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
		save();
	}

	/**
	 * Ajoute le coffre s'il n'est pas dans la liste, le retire sinon.
	 * {@code partner} est l'autre moitie d'un coffre double (ou null) : elle compte comme le meme coffre.
	 *
	 * @return true si le coffre a ete ajoute
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
