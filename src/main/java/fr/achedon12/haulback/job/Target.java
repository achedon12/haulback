package fr.achedon12.haulback.job;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A gathering goal: break {@code needed} blocks among {@code sources}.
 * Progress is counted in blocks broken by the player (hence by Baritone).
 */
public final class Target {
	public final String label;
	public final ItemStack icon;
	public final Set<Block> sources;
	public int needed;
	public int got;
	/** Item that counts as "already obtained" when it is in the chests (null: no deduction). */
	public Item stockItem;
	/** Number of {@link #stockItem} items obtained per broken block. */
	public double yieldPerBlock = 1;
	/** Already in the chests at start (for display). */
	public int fromStock;

	public Target(String label, ItemStack icon, Set<Block> sources, int needed) {
		this.label = label;
		this.icon = icon;
		this.sources = new LinkedHashSet<>(sources);
		this.needed = needed;
	}

	/** Fresh copy (progress reset), to restart a mission. */
	public Target copy() {
		Target t = new Target(label, icon.copy(), sources, needed + fromStock);
		t.stockItem = stockItem;
		t.yieldPerBlock = yieldPerBlock;
		return t;
	}

	/** "Chosen blocks" version: a broken block yields the block's item. */
	public static Target ofBlock(Block b, int count) {
		Target t = new Target(b.getName().getString(), new ItemStack(b), Set.of(b), count);
		t.stockItem = b.asItem();
		return t;
	}

	/** Removes from the goal what is already in the chests (consumes the stock). */
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
