package fr.achedon12.haulback.job;

import fr.achedon12.haulback.Collector;
import fr.achedon12.haulback.HaulbackConfig;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan complet pour un schematic : matieres premieres a recolter + etapes d'atelier
 * (fabrications et cuissons), en comptant le combustible, l'etabli et les fours.
 */
public final class SchematicPlanner {
	private SchematicPlanner() {}

	public static Collector.MissionPlan plan(Map<Item, Integer> wanted, Set<Item> forced, boolean buildOnly, Map<Item, Integer> stock) {
		// 1er passage : combien de cuissons ?
		Materials.Plan first = Materials.resolve(wanted, forced, new HashMap<>(stock));
		int smelts = first.smeltCount();
		boolean crafts = first.steps.stream().anyMatch(s -> !s.smelt());

		Map<Item, Integer> all = new LinkedHashMap<>(wanted);
		HaulbackConfig cfg = HaulbackConfig.get();
		if (smelts > 0) {
			all.merge(Items.COAL, (int) Math.ceil(smelts / 8.0) + 1, Integer::sum);
			int furnaces = Math.min(3, Math.max(1, (int) Math.ceil(smelts / 24.0)));
			int existing = cfg.workshopFurnaces.size();
			if (furnaces > existing) all.merge(Items.COBBLESTONE, 8 * (furnaces - existing), Integer::sum);
		}
		if ((smelts > 0 || crafts) && cfg.workshopTable == null) {
			all.merge(Items.OAK_LOG, 1, Integer::sum);
		}
		Materials.Plan plan = Materials.resolve(all, forced, new HashMap<>(stock));
		List<Materials.Step> steps = new ArrayList<>();
		for (Materials.Step s : plan.steps) {
			// le materiel d'atelier est fabrique a la demande par l'atelier lui-meme
			if (s.item() == Items.CRAFTING_TABLE || s.item() == Items.FURNACE) continue;
			steps.add(s);
		}
		return new Collector.MissionPlan(buildOnly ? List.of() : plan.targets, steps);
	}
}
