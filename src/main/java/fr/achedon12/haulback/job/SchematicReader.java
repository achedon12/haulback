package fr.achedon12.haulback.job;

import baritone.api.BaritoneAPI;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lit un schematic (.litematic, .schem, .schematic) via le lecteur integre a Baritone. */
public final class SchematicReader {

	public record Result(Map<Item, Integer> items, int width, int height, int length, int totalBlocks) {}

	private SchematicReader() {}

	public static Result read(File file) throws IOException {
		Optional<ISchematicFormat> format = BaritoneAPI.getProvider().getSchematicSystem().getByFile(file);
		if (format.isEmpty()) {
			throw new IOException("Format non reconnu : " + file.getName());
		}
		IStaticSchematic s;
		try (InputStream in = new FileInputStream(file)) {
			s = format.get().parse(in);
		}
		Map<Item, Integer> counts = new LinkedHashMap<>();
		int total = 0;
		for (int x = 0; x < s.widthX(); x++) {
			for (int y = 0; y < s.heightY(); y++) {
				for (int z = 0; z < s.lengthZ(); z++) {
					BlockState st = s.getDirect(x, y, z);
					if (st == null || st.isAir()) continue;
					int n = itemsFor(st);
					if (n <= 0) continue;
					Item it = st.getBlock().asItem();
					if (it == Items.AIR) continue;
					counts.merge(it, n, Integer::sum);
					total += n;
				}
			}
		}
		// tri par quantite decroissante
		List<Map.Entry<Item, Integer>> list = new ArrayList<>(counts.entrySet());
		list.sort(Map.Entry.<Item, Integer>comparingByValue(Comparator.reverseOrder()));
		Map<Item, Integer> sorted = new LinkedHashMap<>();
		list.forEach(e -> sorted.put(e.getKey(), e.getValue()));
		return new Result(sorted, s.widthX(), s.heightY(), s.lengthZ(), total);
	}

	private static int itemsFor(BlockState st) {
		if (!st.getFluidState().isEmpty() && st.getBlock().asItem() == Items.AIR) return 0;
		if (st.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& st.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return 0;
		if (st.hasProperty(BlockStateProperties.BED_PART)
				&& st.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return 0;
		if (st.hasProperty(BlockStateProperties.SLAB_TYPE)
				&& st.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) return 2;
		return 1;
	}
}
