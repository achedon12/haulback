package fr.achedon12.haulback;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Memory of the selected chests' contents. The client only knows a chest after opening
 * it: its contents are recorded every time it is opened (by the bot or by the player).
 */
public final class ChestCache {
	private static final Map<BlockPos, Map<Item, Integer>> CONTENTS = new HashMap<>();
	private static final Map<BlockPos, Long> SEEN = new HashMap<>();

	private ChestCache() {}

	public static void snapshot(BlockPos pos, AbstractContainerMenu menu, Inventory player) {
		Map<Item, Integer> m = new LinkedHashMap<>();
		for (Slot s : menu.slots) {
			if (s.container == player) continue;
			ItemStack st = s.getItem();
			if (!st.isEmpty()) m.merge(st.getItem(), st.getCount(), Integer::sum);
		}
		CONTENTS.put(pos.immutable(), m);
		SEEN.put(pos.immutable(), System.currentTimeMillis());
	}

	public static boolean known(BlockPos pos) {
		return CONTENTS.containsKey(pos);
	}

	/** Total known stock across a list of chests. */
	public static Map<Item, Integer> stockOf(List<BlockPos> chests) {
		Map<Item, Integer> out = new HashMap<>();
		for (BlockPos p : chests) {
			Map<Item, Integer> c = CONTENTS.get(p);
			if (c != null) c.forEach((k, v) -> out.merge(k, v, Integer::sum));
		}
		return out;
	}

	public static int count(BlockPos pos, Item item) {
		Map<Item, Integer> c = CONTENTS.get(pos);
		return c == null ? 0 : c.getOrDefault(item, 0);
	}

	public static int knownCount(List<BlockPos> chests) {
		int n = 0;
		for (BlockPos p : chests) if (CONTENTS.containsKey(p)) n++;
		return n;
	}

	/** Other half of a double chest, or null. */
	public static BlockPos partner(BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		BlockState st = mc.level.getBlockState(pos);
		if (!(st.getBlock() instanceof ChestBlock) || st.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
		Direction d = ChestBlock.getConnectedDirection(st);
		return pos.relative(d);
	}

	public static void clear() {
		CONTENTS.clear();
		SEEN.clear();
	}
}
