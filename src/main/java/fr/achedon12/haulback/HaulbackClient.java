package fr.achedon12.haulback;

import com.mojang.blaze3d.platform.InputConstants;
import fr.achedon12.haulback.ui.HaulbackScreen;
import fr.achedon12.haulback.ui.Hud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.InventoryMenu;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HaulbackClient implements ClientModInitializer {
	public static final String MOD_ID = "haulback";
	public static final Logger LOG = LoggerFactory.getLogger("Haulback");

	private static KeyMapping openKey;
	private static KeyMapping chestKey;
	private static KeyMapping toggleKey;
	private static boolean openRequested;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.haulback.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, cat));
		chestKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.haulback.chest", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, cat));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.haulback.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, cat));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> HaulbackCommand.register(dispatcher));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openKey.consumeClick()) {
				if (mc.screen == null) mc.setScreen(new HaulbackScreen());
			}
			while (toggleKey.consumeClick()) {
				Collector.get().toggle();
			}
			if (openRequested && mc.screen == null) {
				openRequested = false;
				mc.setScreen(new HaulbackScreen());
			}
			while (chestKey.consumeClick()) {
				toggleChest(mc);
			}
			rememberOpenedChest(mc);
			try {
				Collector.get().tick();
			} catch (RuntimeException e) {
				LOG.error("Erreur dans la boucle Haulback", e);
				Collector.get().stop(L.t("haulback.msg.internal_error"));
			}
		});
		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> Collector.get().onBlockBroken(state));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			Collector.get().onDisconnect();
			ChestCache.clear();
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide()) lastUsed = hit.getBlockPos().immutable();
			return InteractionResult.PASS;
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), Hud::render);
		LOG.info("Haulback pret (touche H : interface, J : definir le coffre vise)");
	}

	private static BlockPos lastUsed;

	/** Ouvre l'interface au prochain tick (depuis une commande, le chat doit d'abord se fermer). */
	public static void requestOpen() {
		openRequested = true;
	}

	public static String keyName(String which) {
		KeyMapping k = switch (which) {
			case "chest" -> chestKey;
			case "toggle" -> toggleKey;
			default -> openKey;
		};
		return k == null ? "?" : k.getTranslatedKeyMessage().getString();
	}

	/** Quand le joueur (ou le bot) ouvre un des coffres choisis, on memorise son contenu. */
	private static void rememberOpenedChest(Minecraft mc) {
		if (lastUsed == null || mc.player == null) return;
		if (!(mc.screen instanceof AbstractContainerScreen<?> s) || s.getMenu() instanceof InventoryMenu) return;
		List<BlockPos> chests = HaulbackConfig.get().chestList();
		BlockPos key = chests.contains(lastUsed) ? lastUsed : null;
		BlockPos partner = ChestCache.partner(lastUsed);
		if (key == null && partner != null && chests.contains(partner)) key = partner;
		if (key != null && mc.player.tickCount % 5 == 0) ChestCache.snapshot(key, s.getMenu(), mc.player.getInventory());
	}

	/** Ajoute ou retire le coffre vise de la liste. Renvoie un message pour l'interface. */
	public static String toggleChest(Minecraft mc) {
		HitResult hr = mc.hitResult;
		if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK && mc.level != null) {
			BlockPos pos = bhr.getBlockPos().immutable();
			BlockEntity be = mc.level.getBlockEntity(pos);
			if (be instanceof Container) {
				boolean added = HaulbackConfig.get().toggleChest(pos, ChestCache.partner(pos));
				int n = HaulbackConfig.get().chestList().size();
				String msg = (added ? L.t("haulback.msg.chest_added") : L.t("haulback.msg.chest_removed")) + " (" + n + (n > 1 ? L.t("haulback.msg.chests_paren") : L.t("haulback.msg.chest_paren"));
				Collector.get().say(msg, added ? ChatFormatting.GREEN : ChatFormatting.GRAY);
				return msg;
			}
		}
		Collector.get().say(L.t("haulback.msg.look_at_chest"), ChatFormatting.RED);
		return null;
	}
}
