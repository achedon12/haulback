package fr.achedon12.haulback.job;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Un objectif de recolte : casser {@code needed} blocs parmi {@code sources}.
 * La progression est comptee en blocs casses par le joueur (donc par Baritone).
 */
public final class Target {
	public final String label;
	public final ItemStack icon;
	public final Set<Block> sources;
	public int needed;
	public int got;
	/** Objet qui compte comme "deja obtenu" s'il est dans les coffres (null : pas de deduction). */
	public Item stockItem;
	/** Nombre d'objets {@link #stockItem} obtenus par bloc casse. */
	public double yieldPerBlock = 1;
	/** Deja present dans les coffres au depart (pour l'affichage). */
	public int fromStock;

	public Target(String label, ItemStack icon, Set<Block> sources, int needed) {
		this.label = label;
		this.icon = icon;
		this.sources = new LinkedHashSet<>(sources);
		this.needed = needed;
	}

	/** Copie fraiche (progression a zero), pour relancer une mission. */
	public Target copy() {
		Target t = new Target(label, icon.copy(), sources, needed + fromStock);
		t.stockItem = stockItem;
		t.yieldPerBlock = yieldPerBlock;
		return t;
	}

	/** Version "blocs choisis" : un bloc casse rapporte l'objet du bloc. */
	public static Target ofBlock(Block b, int count) {
		Target t = new Target(b.getName().getString(), new ItemStack(b), Set.of(b), count);
		t.stockItem = b.asItem();
		return t;
	}

	/** Retire de l'objectif ce qui est deja dans les coffres (consomme le stock). */
	public void deduct(Map<Item, Integer> stock) {
		if (stockItem == null) return;
		int have = stock.getOrDefault(stockItem, 0);
		if (have <= 0) return;
		int blocks = (int) Math.floor(have / yieldPerBlock);
		int take = Math.min(blocks, needed);
		needed -= take;
		fromStock += take;
		stock.put(stockItem, have - (int) Math.ceil(take * yieldPerBlock));
	}

	public boolean done() {
		return got >= needed;
	}

	public int remaining() {
		return Math.max(0, needed - got);
	}
}
