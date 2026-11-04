package fr.achedon12.haulback;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Client command /haulback (alias /hb). */
public final class HaulbackCommand {
	private HaulbackCommand() {}

	public static void register(CommandDispatcher<FabricClientCommandSource> d) {
		for (String name : new String[]{"haulback", "hb"}) {
			d.register(literal(name)
					.executes(c -> help())
					.then(literal("start").executes(c -> {
						if (Collector.get().active()) Collector.get().say(L.t("haulback.msg.already_running"), ChatFormatting.GRAY);
						else Collector.get().relaunch();
						return 1;
					}))
					.then(literal("stop").executes(c -> {
						if (Collector.get().active()) Collector.get().stop(L.t("haulback.msg.stopped_by_user"));
						else Collector.get().say(L.t("haulback.msg.not_running"), ChatFormatting.GRAY);
						return 1;
					}))
					.then(literal("toggle").executes(c -> {
						Collector.get().toggle();
						return 1;
					}))
					.then(literal("status").executes(c -> {
						Collector col = Collector.get();
						Collector.get().say(col.active() ? L.t("haulback.msg.running") + col.status() : L.t("haulback.msg.idle") + col.status(), ChatFormatting.GRAY);
						return 1;
					}))
					.then(literal("open").executes(c -> {
						HaulbackClient.requestOpen();
						return 1;
					}))
					.then(literal("chest")
							.then(literal("add").executes(c -> {
								HaulbackClient.toggleChest(Minecraft.getInstance());
								return 1;
							}))
							.then(literal("list").executes(c -> {
								List<BlockPos> l = HaulbackConfig.get().chestList();
								if (l.isEmpty()) Collector.get().say(L.t("haulback.msg.no_chests"), ChatFormatting.GRAY);
								for (BlockPos p : l) {
									Collector.get().say(p.getX() + " " + p.getY() + " " + p.getZ()
											+ (ChestCache.known(p) ? "" : L.t("haulback.msg.unknown_contents")), ChatFormatting.GRAY);
								}
								return 1;
							}))
							.then(literal("clear").executes(c -> {
								HaulbackConfig.get().clearChests();
								Collector.get().say(L.t("haulback.msg.chests_cleared"), ChatFormatting.GRAY);
								return 1;
							}))));
		}
	}

	private static int help() {
		Collector c = Collector.get();
		c.say("/haulback start | stop | toggle | status | open | chest add|list|clear", ChatFormatting.GRAY);
		c.say(L.t("haulback.msg.keys") + HaulbackClient.keyName("open") + L.t("haulback.msg.keys_open") + HaulbackClient.keyName("chest")
				+ L.t("haulback.msg.keys_chest") + HaulbackClient.keyName("toggle") + L.t("haulback.msg.keys_toggle"), ChatFormatting.GRAY);
		return 1;
	}
}
