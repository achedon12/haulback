package fr.achedon12.haulback.ui;

import fr.achedon12.haulback.L;
import baritone.api.BaritoneAPI;
import fr.achedon12.haulback.ChestCache;
import fr.achedon12.haulback.Collector;
import fr.achedon12.haulback.HaulbackClient;
import fr.achedon12.haulback.HaulbackConfig;
import fr.achedon12.haulback.job.Materials;
import fr.achedon12.haulback.job.SchematicPlanner;
import fr.achedon12.haulback.job.SchematicReader;
import fr.achedon12.haulback.job.Target;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Interface principale (touche H par defaut). */
public final class HaulbackScreen extends Screen {

	private enum Tab {BLOCKS, SCHEMATIC, OPTIONS}

	private enum Kind {NORMAL, PRIMARY, DANGER, GHOST}

	/** Categories du catalogue de blocs. */
	private enum Category {
		ALL("haulback.cat.all", b -> true),
		ORES("haulback.cat.ores", b -> path(b).endsWith("_ore") || path(b).equals("ancient_debris")),
		WOOD("haulback.cat.wood", b -> is(b, BlockTags.LOGS) || is(b, BlockTags.PLANKS) || is(b, BlockTags.LEAVES)),
		STONE("haulback.cat.stone", b -> is(b, BlockTags.BASE_STONE_OVERWORLD) || is(b, BlockTags.BASE_STONE_NETHER)
				|| path(b).contains("cobble") || path(b).equals("obsidian") || path(b).equals("calcite")),
		EARTH("haulback.cat.earth", b -> is(b, BlockTags.DIRT) || is(b, BlockTags.SAND) || path(b).equals("gravel")
				|| path(b).equals("clay") || path(b).contains("snow") || path(b).contains("terracotta")),
		MODDED("haulback.cat.modded", b -> !"minecraft".equals(BuiltInRegistries.BLOCK.getKey(b).getNamespace()));

		final String label;
		final Predicate<Block> test;

		Category(String label, Predicate<Block> test) {
			this.label = label;
			this.test = test;
		}
	}

	private record Hit(int x0, int y0, int x1, int y1, Consumer<MouseButtonEvent> action) {
		boolean contains(double x, double y) {
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		}
	}

	// etat conserve entre deux ouvertures
	private static Tab tab = Tab.BLOCKS;
	private static Category category = Category.ALL;
	private static final Map<Block, Integer> selection = new LinkedHashMap<>();
	private static File schematic;
	private static SchematicReader.Result schematicData;
	private static String schematicError;
	private static final Set<Item> forceDirect = new HashSet<>();
	/** Mode schematic : 0 = materiaux seulement, 1 = construction seulement, 2 = les deux. */
	private static int schemMode = 2;
	private static BlockPos origin;
	private static String search = "";

	private static List<Block> allBlocks;

	private final List<Hit> hits = new ArrayList<>();
	private EditBox searchBox;
	private List<Block> filtered = List.of();
	private List<File> files = List.of();
	private Materials.Plan plan;
	private int leftScroll, rightScroll, leftMax, rightMax;
	private String flash;
	private int flashColor;
	private long flashUntil;
	private File pending;
	private final long openedAt = System.currentTimeMillis();
	private float tabAnim = tab.ordinal();

	// geometrie
	private int px, py, pw, ph, bodyTop, bodyBottom, leftX, leftW, rightX, rightW;

	public HaulbackScreen() {
		super(Component.literal("Haulback"));
	}

	/** Ouvre directement sur l'onglet schematic (utilise aussi par les tests). */
	public static HaulbackScreen onSchematicTab(File file) {
		tab = Tab.SCHEMATIC;
		HaulbackScreen s = new HaulbackScreen();
		s.pending = file;
		return s;
	}

	/** Ouvre sur l'onglet options (utilise aussi par les tests). */
	public static HaulbackScreen onOptionsTab() {
		tab = Tab.OPTIONS;
		return new HaulbackScreen();
	}

	/** Ouvre sur l'onglet blocs avec une selection donnee (utilise aussi par les tests). */
	public static HaulbackScreen onBlocksTab(Map<Block, Integer> sel) {
		tab = Tab.BLOCKS;
		selection.clear();
		selection.putAll(sel);
		return new HaulbackScreen();
	}

	private static String path(Block b) {
		return BuiltInRegistries.BLOCK.getKey(b).getPath();
	}

	private static boolean is(Block b, TagKey<Block> tag) {
		return b.defaultBlockState().is(tag);
	}

	// ------------------------------------------------------------------ cycle de vie

	@Override
	protected void init() {
		pw = Math.min(500, width - 16);
		ph = Math.min(300, height - 16);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		bodyTop = py + 44;
		bodyBottom = py + ph - 46;

		if (allBlocks == null) {
			allBlocks = new ArrayList<>();
			for (Block b : BuiltInRegistries.BLOCK) {
				if (b.asItem() != Items.AIR) allBlocks.add(b);
			}
			allBlocks.sort(Comparator.comparing(b -> b.getName().getString().toLowerCase(Locale.ROOT)));
		}
		int inner = pw - 28;
		leftX = px + 14;
		leftW = (int) (inner * (tab == Tab.BLOCKS ? 0.58 : tab == Tab.SCHEMATIC ? 0.38 : 0.5));
		rightX = leftX + leftW + 12;
		rightW = px + pw - 14 - rightX;

		searchBox = new EditBox(font, leftX + 8, bodyTop + 6, leftW - 40, 12, Component.literal(L.t("haulback.ui.search")));
		searchBox.setBordered(false);
		searchBox.setMaxLength(64);
		searchBox.setHint(Component.literal(L.t("haulback.ui.search_hint")).withStyle(ChatFormatting.DARK_GRAY));
		searchBox.setTextColor(Theme.TEXT);
		searchBox.setValue(search);
		searchBox.setResponder(s -> {
			search = s;
			leftScroll = 0;
			refilter();
		});
		addRenderableWidget(searchBox);
		refilter();
		refreshFiles();
		if (pending != null) {
			loadSchematic(pending);
			pending = null;
		}
		recomputePlan();
		searchBox.setVisible(tab == Tab.BLOCKS);
		if (tab == Tab.BLOCKS) setFocused(searchBox);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void switchTab(Tab t) {
		if (tab == t) return;
		tab = t;
		leftScroll = rightScroll = 0;
		rebuildWidgets();
	}

	private void refilter() {
		String q = search.trim().toLowerCase(Locale.ROOT);
		List<Block> out = new ArrayList<>();
		for (Block b : allBlocks) {
			if (!category.test.test(b)) continue;
			if (q.isEmpty() || b.getName().getString().toLowerCase(Locale.ROOT).contains(q) || path(b).contains(q)) {
				out.add(b);
			}
		}
		filtered = out;
	}

	private static List<File> schematicDirs() {
		File game = Minecraft.getInstance().gameDirectory;
		List<File> dirs = new ArrayList<>();
		dirs.add(new File(game, "schematics"));
		dirs.add(new File(game, "config/worldedit/schematics"));
		return dirs;
	}

	private void refreshFiles() {
		List<String> exts;
		try {
			exts = BaritoneAPI.getProvider().getSchematicSystem().getFileExtensions();
		} catch (Throwable t) {
			exts = List.of("schem", "schematic", "litematic");
		}
		File main = schematicDirs().get(0);
		if (!main.exists()) main.mkdirs();
		List<File> out = new ArrayList<>();
		for (File d : schematicDirs()) {
			File[] list = d.listFiles();
			if (list == null) continue;
			for (File f : list) {
				String n = f.getName().toLowerCase(Locale.ROOT);
				int dot = n.lastIndexOf('.');
				if (f.isFile() && dot > 0 && exts.contains(n.substring(dot + 1))) out.add(f);
			}
		}
		out.sort(Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
		files = out;
	}

	private void loadSchematic(File f) {
		schematic = f;
		schematicError = null;
		schematicData = null;
		forceDirect.clear();
		rightScroll = 0;
		try {
			schematicData = SchematicReader.read(f);
		} catch (Exception e) {
			HaulbackClient.LOG.warn("Lecture du schematic {} impossible", f, e);
			schematicError = L.t("haulback.ui.read_error") + e.getMessage();
		}
		recomputePlan();
	}

	private void recomputePlan() {
		plan = schematicData == null ? null
				: Materials.resolve(schematicData.items(), forceDirect, ChestCache.stockOf(HaulbackConfig.get().chestList()));
	}

	private void flash(String msg, int color) {
		flash = msg;
		flashColor = color;
		flashUntil = System.currentTimeMillis() + 4000;
	}

	// ------------------------------------------------------------------ rendu

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 180f);
		g.fill(0, 0, width, height, mul(0xC0060708, t));
		for (int i = 6; i >= 1; i--) {
			round(g, px - i * 2, py - i * 2 + 4, pw + i * 4, ph + i * 4, mul(0x0C000000, t));
		}
		round(g, px, py, pw, ph, mul(0xF4131518, t));
		round(g, px + 1, py + 1, pw - 2, 36, mul(0xFF181B1F, t));
		g.fillGradient(px + 1, py + 1, px + pw - 1, py + 20, mul(0x10FFFFFF, t), 0);
		g.fill(px + 3, py, px + pw - 3, py + 1, mul(0x26FFFFFF, t));
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		hits.clear();
		tabAnim += (tab.ordinal() - tabAnim) * 0.35f;
		renderHeader(g, mouseX, mouseY);
		if (tab == Tab.BLOCKS) renderBlocksTab(g, mouseX, mouseY);
		else if (tab == Tab.SCHEMATIC) renderSchematicTab(g, mouseX, mouseY);
		else renderOptionsTab(g, mouseX, mouseY);
		renderFooter(g, mouseX, mouseY);
		super.render(g, mouseX, mouseY, delta);
	}

	private void renderHeader(GuiGraphics g, int mx, int my) {
		int ix = px + 12, iy = py + 9;
		round(g, ix, iy, 20, 20, 0x30F2A93B);
		g.renderItem(new ItemStack(Items.CHEST), ix + 2, iy + 2);
		g.drawString(font, "Haulback", ix + 28, iy + 2, Theme.TEXT, false);

		String[] labels = {L.t("haulback.ui.tab_blocks"), L.t("haulback.ui.tab_schematic"), L.t("haulback.ui.tab_options")};
		int segW = 0;
		for (String l : labels) segW = Math.max(segW, font.width(l) + 20);
		int n = labels.length;
		int sx = px + pw - 12 - segW * n - 4, sy = py + 10;
		String sub = font.plainSubstrByWidth(L.t("haulback.ui.tagline"), Math.max(0, sx - (ix + 28) - 8));
		g.drawString(font, sub, ix + 28, iy + 12, Theme.FAINT, false);
		round(g, sx, sy, segW * n + 4, 18, 0xFF0E1013);
		int knob = (int) (sx + 2 + tabAnim * segW);
		round(g, knob, sy + 2, segW, 14, 0xFF2A2F36);
		g.fill(knob + 6, sy + 15, knob + segW - 6, sy + 16, Theme.ACCENT);
		for (int i = 0; i < n; i++) {
			Tab t = Tab.values()[i];
			int x0 = sx + 2 + i * segW;
			boolean hover = in(mx, my, x0, sy, x0 + segW, sy + 18);
			g.drawString(font, labels[i], x0 + (segW - font.width(labels[i])) / 2, sy + 5,
					tab == t ? Theme.TEXT : hover ? 0xFFB4BAC2 : Theme.MUTED, false);
			hits.add(new Hit(x0, sy, x0 + segW, sy + 18, e -> switchTab(t)));
		}
		g.fill(px + 1, py + 37, px + pw - 1, py + 38, Theme.LINE);
	}

	// ---- onglet blocs

	private void renderBlocksTab(GuiGraphics g, int mx, int my) {
		round(g, leftX, bodyTop, leftW, 22, Theme.SURFACE);
		g.fill(leftX + 4, bodyTop + 21, leftX + leftW - 4, bodyTop + 22, searchBox.isFocused() ? Theme.ACCENT : Theme.LINE);
		String cnt = String.valueOf(filtered.size());
		g.drawString(font, cnt, leftX + leftW - 8 - font.width(cnt), bodyTop + 7, Theme.FAINT, false);

		int cx = leftX, cy = bodyTop + 27;
		for (Category c : Category.values()) {
			int w = font.width(L.t(c.label)) + 12;
			if (cx + w > leftX + leftW) break;
			boolean on = category == c;
			boolean hover = in(mx, my, cx, cy, cx + w, cy + 14);
			round(g, cx, cy, w, 14, on ? 0x33F2A93B : hover ? Theme.SURFACE_HI : Theme.SURFACE);
			g.drawString(font, L.t(c.label), cx + 6, cy + 3, on ? Theme.ACCENT : hover ? Theme.TEXT : Theme.MUTED, false);
			final Category target = c;
			hits.add(new Hit(cx, cy, cx + w, cy + 14, e -> {
				category = target;
				leftScroll = 0;
				refilter();
			}));
			cx += w + 4;
		}

		int gridTop = cy + 19;
		int cell = 20;
		int cols = Math.max(1, (leftW - 2) / cell);
		int gx = leftX + (leftW - cols * cell) / 2;
		int visibleRows = Math.max(1, (bodyBottom - gridTop) / cell);
		int totalRows = (filtered.size() + cols - 1) / cols;
		leftMax = Math.max(0, totalRows - visibleRows);
		leftScroll = clamp(leftScroll, 0, leftMax);
		Block hovered = null;
		for (int r = 0; r < visibleRows; r++) {
			for (int c = 0; c < cols; c++) {
				int idx = (r + leftScroll) * cols + c;
				if (idx >= filtered.size()) break;
				Block b = filtered.get(idx);
				int x = gx + c * cell, y = gridTop + r * cell;
				boolean sel = selection.containsKey(b);
				boolean hover = in(mx, my, x, y, x + cell, y + cell);
				if (sel) {
					round(g, x, y, cell - 1, cell - 1, 0x40F2A93B);
					outline(g, x, y, cell - 1, cell - 1, Theme.ACCENT);
				} else {
					round(g, x, y, cell - 1, cell - 1, hover ? Theme.SURFACE_HI : 0xFF191C20);
				}
				g.renderItem(new ItemStack(b), x + 1, y + 1);
				if (hover) hovered = b;
				hits.add(new Hit(x, y, x + cell, y + cell, e -> {
					if (selection.containsKey(b) && e.hasShiftDown()) selection.remove(b);
					else selection.merge(b, 64, (o, n) -> o);
				}));
			}
		}
		if (filtered.isEmpty()) centered(g, L.t("haulback.ui.no_match"), leftX + leftW / 2, gridTop + 20, Theme.FAINT);
		scrollbar(g, leftX + leftW + 4, gridTop, bodyBottom, leftScroll, leftMax, visibleRows);
		if (hovered != null) {
			g.setTooltipForNextFrame(font, Component.literal(hovered.getName().getString()
					+ (selection.containsKey(hovered) ? L.t("haulback.ui.shift_remove") : "")), mx, my);
		}

		// ---- colonne selection
		g.drawString(font, L.t("haulback.ui.selection"), rightX, bodyTop + 7, Theme.TEXT, false);
		String badge = String.valueOf(selection.size());
		int bx = rightX + font.width(L.t("haulback.ui.selection")) + 6;
		round(g, bx, bodyTop + 4, font.width(badge) + 8, 13, selection.isEmpty() ? Theme.SURFACE : 0x33F2A93B);
		g.drawString(font, badge, bx + 4, bodyTop + 7, selection.isEmpty() ? Theme.FAINT : Theme.ACCENT, false);
		if (!selection.isEmpty()) {
			String clear = L.t("haulback.ui.clear_all");
			int cw = font.width(clear);
			boolean hc = in(mx, my, rightX + rightW - cw, bodyTop + 4, rightX + rightW, bodyTop + 17);
			g.drawString(font, clear, rightX + rightW - cw, bodyTop + 7, hc ? Theme.DANGER : Theme.FAINT, false);
			hits.add(new Hit(rightX + rightW - cw, bodyTop + 4, rightX + rightW, bodyTop + 17, e -> selection.clear()));
		}

		int sTop = bodyTop + 24;
		if (selection.isEmpty()) {
			round(g, rightX, sTop, rightW, bodyBottom - sTop, 0xFF16191C);
			int midY = sTop + (bodyBottom - sTop) / 2 - 24;
			g.pose().pushMatrix();
			g.pose().translate(rightX + rightW / 2f - 16, midY - 8);
			g.pose().scale(2f, 2f);
			g.renderItem(new ItemStack(Items.IRON_PICKAXE), 0, 0);
			g.pose().popMatrix();
			g.fill(rightX + rightW / 2 - 16, midY - 8, rightX + rightW / 2 + 16, midY + 24, 0x9016191C);
			centered(g, L.t("haulback.ui.empty1"), rightX + rightW / 2, midY + 32, Theme.MUTED);
			centered(g, L.t("haulback.ui.empty2"), rightX + rightW / 2, midY + 44, Theme.FAINT);
			return;
		}
		List<Block> sel = new ArrayList<>(selection.keySet());
		int rowH = 28;
		int sVisible = Math.max(1, (bodyBottom - sTop - 14) / rowH);
		rightMax = Math.max(0, sel.size() - sVisible);
		rightScroll = clamp(rightScroll, 0, rightMax);
		for (int i = 0; i < sVisible && i + rightScroll < sel.size(); i++) {
			Block b = sel.get(i + rightScroll);
			int y = sTop + i * rowH;
			boolean hover = in(mx, my, rightX, y, rightX + rightW, y + rowH - 3);
			round(g, rightX, y, rightW, rowH - 3, hover ? 0xFF22262B : Theme.SURFACE);
			round(g, rightX + 4, y + 3, 19, 19, 0xFF15171A);
			g.renderItem(new ItemStack(b), rightX + 5, y + 4);
			int qty = selection.get(b);

			String q = String.valueOf(qty);
			int stW = 50;
			int stX = rightX + rightW - stW - 16;
			round(g, stX, y + 5, stW, 15, 0xFF15171A);
			stepButton(g, mx, my, stX, y + 5, "-", e -> selection.computeIfPresent(b, (k, v) -> Math.max(1, v - (e.hasShiftDown() ? 1 : 16))));
			stepButton(g, mx, my, stX + stW - 14, y + 5, "+", e -> selection.computeIfPresent(b, (k, v) -> Math.min(99999, v + (e.hasShiftDown() ? 1 : 16))));
			g.drawString(font, q, stX + (stW - font.width(q)) / 2, y + 9, Theme.ACCENT, false);
			boolean hx = in(mx, my, rightX + rightW - 14, y + 6, rightX + rightW - 3, y + 19);
			g.drawString(font, "x", rightX + rightW - 11, y + 9, hx ? Theme.DANGER : Theme.FAINT, false);
			hits.add(new Hit(rightX + rightW - 14, y + 6, rightX + rightW - 3, y + 19, e -> selection.remove(b)));

			int nameW = stX - (rightX + 28) - 4;
			g.drawString(font, font.plainSubstrByWidth(b.getName().getString(), nameW), rightX + 28, y + 4, Theme.TEXT, false);
			int inStock = ChestCache.stockOf(HaulbackConfig.get().chestList()).getOrDefault(b.asItem(), 0);
			if (inStock > 0) {
				String st = Math.min(inStock, qty) >= qty ? L.t("haulback.ui.in_stock") : L.t("haulback.ui.stock") + inStock + L.t("haulback.ui.left") + (qty - inStock);
				g.drawString(font, font.plainSubstrByWidth(st, nameW), rightX + 28, y + 14, Theme.OK, false);
			} else {
				g.drawString(font, stacks(qty), rightX + 28, y + 14, Theme.FAINT, false);
			}
		}
		scrollbar(g, rightX + rightW + 3, sTop, bodyBottom - 14, rightScroll, rightMax, sVisible);
		int total = selection.values().stream().mapToInt(Integer::intValue).sum();
		String sum = L.t("haulback.ui.total") + total + "  -  " + stacks(total) + L.t("haulback.ui.step_hint");
		g.drawString(font, font.plainSubstrByWidth(sum, rightW), rightX, bodyBottom - 9, Theme.FAINT, false);
	}

	private static String stacks(int n) {
		int s = n / 64, r = n % 64;
		if (s == 0) return n + (n > 1 ? L.t("haulback.ui.blocks") : L.t("haulback.ui.block"));
		return s + (s > 1 ? L.t("haulback.ui.stacks") : L.t("haulback.ui.stack")) + (r > 0 ? " + " + r : "");
	}

	// ---- onglet schematic

	private void renderSchematicTab(GuiGraphics g, int mx, int my) {
		int bw = font.width(L.t("haulback.ui.folder")) + 12;
		if (leftW - bw - font.width(L.t("haulback.ui.refresh")) - 20 > font.width(L.t("haulback.ui.files"))) {
			g.drawString(font, L.t("haulback.ui.files"), leftX, bodyTop + 6, Theme.TEXT, false);
		}
		button(g, mx, my, leftX + leftW - bw, bodyTop + 1, bw, 16, L.t("haulback.ui.folder"), Kind.GHOST, e -> Util.getPlatform().openFile(schematicDirs().get(0)));
		int bw2 = font.width(L.t("haulback.ui.refresh")) + 12;
		button(g, mx, my, leftX + leftW - bw - 4 - bw2, bodyTop + 1, bw2, 16, L.t("haulback.ui.refresh"), Kind.GHOST, e -> refreshFiles());

		int listTop = bodyTop + 22;
		int rowH = 26;
		if (files.isEmpty()) {
			round(g, leftX, listTop, leftW, bodyBottom - listTop, 0xFF16191C);
			int y = listTop + 14;
			centered(g, L.t("haulback.ui.no_schematic"), leftX + leftW / 2, y, Theme.MUTED);
			centered(g, L.t("haulback.ui.no_schematic1"), leftX + leftW / 2, y + 16, Theme.FAINT);
			centered(g, L.t("haulback.ui.no_schematic2"), leftX + leftW / 2, y + 28, Theme.FAINT);
			centered(g, L.t("haulback.ui.no_schematic3"), leftX + leftW / 2, y + 40, Theme.FAINT);
		} else {
			int visible = Math.max(1, (bodyBottom - listTop) / rowH);
			leftMax = Math.max(0, files.size() - visible);
			leftScroll = clamp(leftScroll, 0, leftMax);
			for (int i = 0; i < visible && i + leftScroll < files.size(); i++) {
				File f = files.get(i + leftScroll);
				int y = listTop + i * rowH;
				boolean on = f.equals(schematic);
				boolean hover = in(mx, my, leftX, y, leftX + leftW, y + rowH - 3);
				round(g, leftX, y, leftW, rowH - 3, on ? 0xFF262B31 : hover ? 0xFF1F2328 : 0xFF191C20);
				if (on) g.fill(leftX, y + 4, leftX + 2, y + rowH - 7, Theme.ACCENT);
				String name = f.getName();
				int dot = name.lastIndexOf('.');
				String base = dot > 0 ? name.substring(0, dot) : name;
				String ext = dot > 0 ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "";
				int ew = font.width(ext) + 8;
				round(g, leftX + leftW - ew - 6, y + 6, ew, 11, 0xFF15171A);
				g.drawString(font, ext, leftX + leftW - ew - 2, y + 8, on ? Theme.ACCENT : Theme.FAINT, false);
				g.drawString(font, font.plainSubstrByWidth(base, leftW - ew - 20), leftX + 9, y + 4, on ? Theme.TEXT : Theme.MUTED, false);
				g.drawString(font, (f.length() / 1024 + 1) + L.t("haulback.ui.kb"), leftX + 9, y + 13, Theme.FAINT, false);
				hits.add(new Hit(leftX, y, leftX + leftW, y + rowH - 3, e -> loadSchematic(f)));
			}
			scrollbar(g, leftX + leftW + 4, listTop, bodyBottom, leftScroll, leftMax, visible);
		}

		int x = rightX;
		if (schematic == null) {
			round(g, x, bodyTop, rightW, bodyBottom - bodyTop, 0xFF16191C);
			centered(g, L.t("haulback.ui.pick_file"), x + rightW / 2, bodyTop + (bodyBottom - bodyTop) / 2 - 4, Theme.FAINT);
			return;
		}
		if (schematicError != null) {
			g.drawString(font, font.plainSubstrByWidth(schematic.getName(), rightW), x, bodyTop + 6, Theme.TEXT, false);
			g.drawString(font, font.plainSubstrByWidth(schematicError, rightW), x, bodyTop + 20, Theme.DANGER, false);
			return;
		}
		SchematicReader.Result d = schematicData;

		int tileW = (rightW - 8) / 3;
		tile(g, x, bodyTop, tileW, L.t("haulback.ui.size"), d.width() + " x " + d.height() + " x " + d.length());
		tile(g, x + tileW + 4, bodyTop, tileW, L.t("haulback.ui.tab_blocks"), String.valueOf(d.totalBlocks()));
		tile(g, x + (tileW + 4) * 2, bodyTop, rightW - (tileW + 4) * 2, L.t("haulback.ui.types"), String.valueOf(d.items().size()));

		int oy = bodyTop + 31;
		String[] modes = {L.t("haulback.ui.mode_materials"), L.t("haulback.ui.mode_build"), L.t("haulback.ui.mode_both")};
		String[] hints = {L.t("haulback.ui.mode_materials_hint"), L.t("haulback.ui.mode_build_hint"), L.t("haulback.ui.mode_both_hint")};
		int segW = (rightW - 4) / 3;
		round(g, x, oy, rightW, 16, 0xFF0E1013);
		round(g, x + 2 + schemMode * segW, oy + 2, segW, 12, 0xFF2A2F36);
		g.fill(x + 2 + schemMode * segW + 6, oy + 13, x + 2 + schemMode * segW + segW - 6, oy + 14, Theme.ACCENT);
		for (int i = 0; i < 3; i++) {
			int sx0 = x + 2 + i * segW;
			boolean hov = in(mx, my, sx0, oy, sx0 + segW, oy + 16);
			String lab = font.plainSubstrByWidth(modes[i], segW - 4);
			g.drawString(font, lab, sx0 + (segW - font.width(lab)) / 2, oy + 4, schemMode == i ? Theme.TEXT : hov ? 0xFFB4BAC2 : Theme.MUTED, false);
			final int m = i;
			hits.add(new Hit(sx0, oy, sx0 + segW, oy + 16, e -> schemMode = m));
			if (hov) g.setTooltipForNextFrame(font, Component.literal(hints[i]), mx, my);
		}
		oy += 19;
		String o = origin == null ? L.t("haulback.ui.not_set") : origin.getX() + " " + origin.getY() + " " + origin.getZ();
		g.drawString(font, L.t("haulback.ui.origin"), x, oy + 3, Theme.FAINT, false);
		g.drawString(font, o, x + font.width(L.t("haulback.ui.origin")) + 6, oy + 3,
				schemMode == 0 ? Theme.FAINT : origin == null ? Theme.DANGER : Theme.TEXT, false);
		int iw = font.width(L.t("haulback.ui.my_position")) + 12;
		button(g, mx, my, x + rightW - iw, oy, iw, 14, L.t("haulback.ui.my_position"), Kind.GHOST, e -> {
			if (minecraft.player != null) origin = minecraft.player.blockPosition();
		});

		int top = oy + 19;
		g.fill(x, top - 2, x + rightW, top - 1, Theme.LINE);
		int max = 1;
		for (Target t : plan.targets) max = Math.max(max, t.needed);
		for (Materials.Step l : plan.steps) max = Math.max(max, l.count());
		List<Object[]> lines = new ArrayList<>();
		if (!plan.targets.isEmpty()) {
			lines.add(new Object[]{"h", L.t("haulback.ui.sec_gather"), Theme.ACCENT});
			for (Target t : plan.targets) lines.add(new Object[]{"t", t});
		}
		if (!plan.steps.isEmpty()) {
			lines.add(new Object[]{"h", L.t("haulback.ui.sec_workshop"), 0xFF5CC8FF});
			for (Materials.Step st : plan.steps) {
				lines.add(new Object[]{"c", new Materials.Line(st.item(), st.count(), st.smelt() ? L.t("haulback.note.smelt") : L.t("haulback.note.craft"))});
			}
		}
		if (!plan.fromStock.isEmpty()) {
			lines.add(new Object[]{"h", L.t("haulback.ui.sec_stock"), Theme.OK});
			for (Materials.Line l : plan.fromStock) lines.add(new Object[]{"s", l});
		}
		if (!plan.manual.isEmpty()) {
			lines.add(new Object[]{"h", L.t("haulback.ui.sec_manual"), Theme.DANGER});
			for (Materials.Line l : plan.manual) lines.add(new Object[]{"m", l});
		}
		if (!forceDirect.isEmpty()) {
			lines.add(new Object[]{"h", L.t("haulback.ui.sec_forced"), Theme.OK});
			for (Item it : forceDirect) lines.add(new Object[]{"f", it});
		}
		int lineH = 15;
		int visible = Math.max(1, (bodyBottom - top) / lineH);
		rightMax = Math.max(0, lines.size() - visible);
		rightScroll = clamp(rightScroll, 0, rightMax);
		for (int i = 0; i < visible && i + rightScroll < lines.size(); i++) {
			Object[] l = lines.get(i + rightScroll);
			int y = top + i * lineH;
			String type = (String) l[0];
			if (type.equals("h")) {
				int col = (int) l[2];
				g.fill(x, y + 6, x + 3, y + 9, col);
				g.drawString(font, font.plainSubstrByWidth((String) l[1], rightW - 8), x + 7, y + 4, col, false);
				continue;
			}
			ItemStack icon;
			String label;
			String count;
			int col;
			float frac = 0;
			Item clickItem = null;
			switch (type) {
				case "t" -> {
					Target t = (Target) l[1];
					icon = t.icon;
					label = t.label;
					count = String.valueOf(t.needed);
					col = Theme.TEXT;
					frac = t.needed / (float) max;
				}
				case "c" -> {
					Materials.Line c = (Materials.Line) l[1];
					icon = c.stack();
					label = c.item().getName().getString() + "  -  " + c.note();
					count = String.valueOf(c.count());
					col = Theme.MUTED;
					frac = c.count() / (float) max;
				}
				case "s" -> {
					Materials.Line c = (Materials.Line) l[1];
					icon = c.stack();
					label = c.item().getName().getString();
					count = "-" + c.count();
					col = 0xFF9BE8B0;
				}
				case "m" -> {
					Materials.Line m = (Materials.Line) l[1];
					icon = m.stack();
					label = m.item().getName().getString();
					count = String.valueOf(m.count());
					col = 0xFFF0A8A4;
					clickItem = m.item();
				}
				default -> {
					Item it = (Item) l[1];
					icon = new ItemStack(it);
					label = it.getName().getString();
					count = "";
					col = 0xFF9BE8B0;
					clickItem = it;
				}
			}
			boolean hover = clickItem != null && in(mx, my, x, y, x + rightW, y + lineH);
			if (hover) round(g, x, y, rightW, lineH, Theme.SURFACE);
			if (frac > 0) g.fill(x + 16, y + lineH - 2, x + 16 + (int) ((rightW - 16) * Math.min(1f, frac)), y + lineH - 1, 0x22FFFFFF);
			miniItem(g, icon, x + 1, y + 1);
			int cw = font.width(count);
			g.drawString(font, font.plainSubstrByWidth(label, rightW - 22 - cw - 6), x + 16, y + 4, col, false);
			g.drawString(font, count, x + rightW - 4 - cw, y + 4, Theme.ACCENT, false);
			if (clickItem != null) {
				final Item it = clickItem;
				hits.add(new Hit(x, y, x + rightW, y + lineH, e -> {
					if (!forceDirect.remove(it)) forceDirect.add(it);
					recomputePlan();
				}));
			}
		}
		scrollbar(g, x + rightW + 3, top, bodyBottom, rightScroll, rightMax, visible);
	}

	// ---- onglet options

	private void renderOptionsTab(GuiGraphics g, int mx, int my) {
		HaulbackConfig cfg = HaulbackConfig.get();
		boolean stone = cfg.toolTier >= 1;
		g.drawString(font, L.t("haulback.ui.tools_title"), leftX, bodyTop + 4, Theme.TEXT, false);
		int cardW = (leftW - 6) / 2, cardH = 34;
		Object[][] tools = {
				{L.t("haulback.ui.pickaxe"), stone ? Items.STONE_PICKAXE : Items.WOODEN_PICKAXE, cfg.toolPickaxe, (Runnable) () -> cfg.toolPickaxe = !cfg.toolPickaxe},
				{L.t("haulback.ui.axe"), stone ? Items.STONE_AXE : Items.WOODEN_AXE, cfg.toolAxe, (Runnable) () -> cfg.toolAxe = !cfg.toolAxe},
				{L.t("haulback.ui.sword"), stone ? Items.STONE_SWORD : Items.WOODEN_SWORD, cfg.toolSword, (Runnable) () -> cfg.toolSword = !cfg.toolSword},
				{L.t("haulback.ui.shovel"), stone ? Items.STONE_SHOVEL : Items.WOODEN_SHOVEL, cfg.toolShovel, (Runnable) () -> cfg.toolShovel = !cfg.toolShovel},
		};
		for (int i = 0; i < tools.length; i++) {
			int x = leftX + (i % 2) * (cardW + 6);
			int y = bodyTop + 18 + (i / 2) * (cardH + 6);
			boolean on = (boolean) tools[i][2];
			boolean hover = in(mx, my, x, y, x + cardW, y + cardH);
			round(g, x, y, cardW, cardH, on ? 0x2EF2A93B : hover ? Theme.SURFACE_HI : Theme.SURFACE);
			if (on) outline(g, x, y, cardW, cardH, 0xAAF2A93B);
			round(g, x + 6, y + 7, 20, 20, 0xFF15171A);
			g.renderItem(new ItemStack((Item) tools[i][1]), x + 8, y + 9);
			if (!on) g.fill(x + 6, y + 7, x + 26, y + 27, 0x9915171A);
			g.drawString(font, (String) tools[i][0], x + 32, y + 8, on ? Theme.TEXT : Theme.MUTED, false);
			g.drawString(font, font.plainSubstrByWidth(on ? L.t("haulback.ui.if_missing") : L.t("haulback.ui.ignored"), cardW - 36), x + 32, y + 19, on ? Theme.ACCENT : Theme.FAINT, false);
			Runnable r = (Runnable) tools[i][3];
			hits.add(new Hit(x, y, x + cardW, y + cardH, e -> {
				r.run();
				cfg.save();
			}));
		}
		int ty = bodyTop + 18 + 2 * (cardH + 6) + 2;
		g.drawString(font, L.t("haulback.ui.material"), leftX, ty + 4, Theme.FAINT, false);
		int segX = leftX + font.width(L.t("haulback.ui.material")) + 8, segW = 52;
		round(g, segX, ty, segW * 2 + 4, 16, 0xFF0E1013);
		round(g, segX + 2 + (stone ? segW : 0), ty + 2, segW, 12, 0xFF2A2F36);
		g.drawString(font, L.t("haulback.cat.wood"), segX + 2 + (segW - font.width(L.t("haulback.cat.wood"))) / 2, ty + 4, !stone ? Theme.TEXT : Theme.MUTED, false);
		g.drawString(font, L.t("haulback.cat.stone"), segX + 2 + segW + (segW - font.width(L.t("haulback.cat.stone"))) / 2, ty + 4, stone ? Theme.TEXT : Theme.MUTED, false);
		hits.add(new Hit(segX, ty, segX + 2 + segW, ty + 16, e -> { cfg.toolTier = 0; cfg.save(); }));
		hits.add(new Hit(segX + 2 + segW, ty, segX + segW * 2 + 4, ty + 16, e -> { cfg.toolTier = 1; cfg.save(); }));
		int ny = ty + 22;
		List<net.minecraft.util.FormattedCharSequence> note = font.split(Component.literal(
				L.t("haulback.ui.tools_note")), leftW);
		for (net.minecraft.util.FormattedCharSequence line : note) {
			if (ny + 9 > bodyBottom) break;
			g.drawString(font, line, leftX, ny, Theme.FAINT, false);
			ny += 10;
		}

		// comportement
		int x = rightX;
		g.drawString(font, L.t("haulback.ui.behavior"), x, bodyTop + 4, Theme.TEXT, false);
		int y = bodyTop + 18;
		y = stepperRow(g, mx, my, x, y, L.t("haulback.ui.max_radius"), String.valueOf(cfg.maxRadius),
				d -> cfg.maxRadius = clamp(cfg.maxRadius + d * 32, 32, 1024));
		y = stepperRow(g, mx, my, x, y, L.t("haulback.ui.retreat"), String.valueOf(cfg.retreatHealth / 2),
				d -> cfg.retreatHealth = clamp(cfg.retreatHealth + d * 2, 2, 18));
		y = stepperRow(g, mx, my, x, y, L.t("haulback.ui.eat"), String.valueOf(cfg.eatBelowFood / 2),
				d -> cfg.eatBelowFood = clamp(cfg.eatBelowFood + d * 2, 4, 18));
		y = stepperRow(g, mx, my, x, y, L.t("haulback.ui.reserve"), String.valueOf(cfg.keepThrowaway),
				d -> cfg.keepThrowaway = clamp(cfg.keepThrowaway + d * 16, 0, 256));
		round(g, x, y, rightW, 20, Theme.SURFACE);
		checkbox(g, mx, my, x + 6, y + 4, L.t("haulback.ui.fight"), cfg.fight, e -> {
			cfg.fight = !cfg.fight;
			cfg.save();
		});
		y += 24;
		if (y + 20 <= bodyBottom) {
			String keys = HaulbackClient.keyName("open") + L.t("haulback.ui.keys_open") + HaulbackClient.keyName("chest") + L.t("haulback.ui.keys_chest")
					+ HaulbackClient.keyName("toggle") + L.t("haulback.ui.keys_toggle");
			g.drawString(font, font.plainSubstrByWidth(keys, rightW), x, y + 2, Theme.MUTED, false);
			g.drawString(font, font.plainSubstrByWidth(L.t("haulback.ui.keys_note"), rightW), x, y + 12, Theme.FAINT, false);
		}
	}

	private int stepperRow(GuiGraphics g, int mx, int my, int x, int y, String label, String value, java.util.function.IntConsumer change) {
		round(g, x, y, rightW, 20, Theme.SURFACE);
		int stW = 52, stX = x + rightW - stW - 3;
		g.drawString(font, font.plainSubstrByWidth(label, stX - x - 10), x + 6, y + 6, Theme.MUTED, false);
		round(g, stX, y + 3, stW, 14, 0xFF15171A);
		stepButton(g, mx, my, stX, y + 2, "-", e -> {
			change.accept(-1);
			HaulbackConfig.get().save();
		});
		stepButton(g, mx, my, stX + stW - 14, y + 2, "+", e -> {
			change.accept(1);
			HaulbackConfig.get().save();
		});
		g.drawString(font, value, stX + (stW - font.width(value)) / 2, y + 6, Theme.ACCENT, false);
		return y + 23;
	}

	private void tile(GuiGraphics g, int x, int y, int w, String label, String value) {
		round(g, x, y, w, 26, Theme.SURFACE);
		g.drawString(font, label, x + 6, y + 4, Theme.FAINT, false);
		g.drawString(font, font.plainSubstrByWidth(value, w - 12), x + 6, y + 14, Theme.TEXT, false);
	}

	private void miniItem(GuiGraphics g, ItemStack icon, int x, int y) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(0.8f, 0.8f);
		g.renderItem(icon, 0, 0);
		g.pose().popMatrix();
	}

	// ---- pied de page

	private void renderFooter(GuiGraphics g, int mx, int my) {
		int fy = py + ph - 36;
		g.fill(px + 1, fy - 6, px + pw - 1, fy - 5, Theme.LINE);
		List<BlockPos> chests = HaulbackConfig.get().chestList();
		int known = ChestCache.knownCount(chests);
		String title = chests.isEmpty() ? L.t("haulback.ui.chests") : chests.size() + (chests.size() > 1 ? L.t("haulback.ui.n_chests") : L.t("haulback.ui.n_chest"));
		String cs;
		if (chests.isEmpty()) cs = L.t("haulback.ui.none_selected");
		else if (chests.size() == 1) cs = chests.get(0).getX() + " " + chests.get(0).getY() + " " + chests.get(0).getZ();
		else cs = known + "/" + chests.size() + L.t("haulback.ui.read_n");
		if (pw < 470 && chests.size() > 1) cs = known + "/" + chests.size() + L.t("haulback.ui.read_short");
		int chipW = 24 + Math.max(font.width(cs), font.width(title)) + 10;
		round(g, leftX, fy, chipW, 26, Theme.SURFACE);
		g.renderItem(new ItemStack(Items.CHEST), leftX + 4, fy + 5);
		g.drawString(font, title, leftX + 24, fy + 4, Theme.FAINT, false);
		g.drawString(font, cs, leftX + 24, fy + 14, chests.isEmpty() ? Theme.DANGER : Theme.TEXT, false);
		if (!chests.isEmpty()) {
			List<Component> tip = new ArrayList<>();
			tip.add(Component.literal(L.t("haulback.ui.selected_chests")));
			for (BlockPos p : chests) {
				tip.add(Component.literal(p.getX() + " " + p.getY() + " " + p.getZ()
						+ (ChestCache.known(p) ? "" : L.t("haulback.msg.unknown_contents"))).withStyle(ChatFormatting.GRAY));
			}
			if (in(mx, my, leftX, fy, leftX + chipW, fy + 26)) g.setComponentTooltipForNextFrame(font, tip, mx, my);
		}
		boolean compact = pw < 470;
		String addLabel = compact ? "+/-" : L.t("haulback.ui.add_remove");
		int cbw = font.width(addLabel) + 14;
		button(g, mx, my, leftX + chipW + 6, fy + 4, cbw, 18, addLabel, Kind.GHOST, e -> pickChest());
		int cbw2 = 0;
		if (!chests.isEmpty() && !compact) {
			cbw2 = font.width(L.t("haulback.ui.clear")) + 12;
			button(g, mx, my, leftX + chipW + 6 + cbw + 4, fy + 4, cbw2, 18, L.t("haulback.ui.clear"), Kind.GHOST, e -> {
				HaulbackConfig.get().clearChests();
				recomputePlan();
			});
			cbw2 += 4;
		}
		cbw += cbw2;

		Collector col = Collector.get();
		int rx = px + pw - 14;
		if (col.active()) {
			int w = font.width(L.t("haulback.ui.stop")) + 24;
			button(g, mx, my, rx - w, fy + 3, w, 20, L.t("haulback.ui.stop"), Kind.DANGER, e -> col.stop(L.t("haulback.msg.stopped_by_user")));
			float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 250.0);
			int sx = leftX + chipW + 6 + cbw + 12;
			g.fill(sx, fy + 11, sx + 4, fy + 15, mul(Theme.ACCENT, 0.4f + 0.6f * pulse));
			String st = font.plainSubstrByWidth(L.t("haulback.msg.running") + col.status(), Math.max(0, rx - w - 10 - sx - 8));
			g.drawString(font, st, sx + 8, fy + 10, Theme.MUTED, false);
		} else if (tab == Tab.OPTIONS) {
			String label = L.t("haulback.ui.autosave");
			if (rx - font.width(label) > leftX + chipW + 6 + cbw + 10) g.drawString(font, label, rx - font.width(label), fy + 9, Theme.FAINT, false);
		} else if (tab == Tab.BLOCKS) {
			String label = compact ? L.t("haulback.ui.start_short") : L.t("haulback.ui.start_gather");
			int w = font.width(label) + 28;
			button(g, mx, my, rx - w, fy + 3, w, 20, label, Kind.PRIMARY, e -> startBlocks());
		} else {
			String main = switch (schemMode) {
				case 0 -> L.t("haulback.ui.gather");
				case 1 -> L.t("haulback.ui.build");
				default -> compact ? L.t("haulback.ui.start_all") : L.t("haulback.ui.gather_build");
			};
			int w = font.width(main) + 28;
			button(g, mx, my, rx - w, fy + 3, w, 20, main, Kind.PRIMARY, e -> startSchematic());
		}
		if (flash != null && System.currentTimeMillis() < flashUntil) {
			int fw = font.width(flash) + 14;
			int fx = px + (pw - fw) / 2;
			int fyy = Math.min(height - 20, py + ph + 6);
			round(g, fx, fyy, fw, 16, 0xF0121418);
			g.fill(fx, fyy, fx + 2, fyy + 16, flashColor);
			g.drawString(font, flash, fx + 8, fyy + 4, flashColor, false);
		}
	}

	// ------------------------------------------------------------------ actions

	private void pickChest() {
		String msg = HaulbackClient.toggleChest(minecraft);
		if (msg == null) {
			flash(L.t("haulback.ui.look_at_chest"), Theme.DANGER);
		} else {
			flash(msg, Theme.OK);
			recomputePlan();
		}
	}

	private void startBlocks() {
		if (selection.isEmpty()) {
			flash(L.t("haulback.ui.add_block"), Theme.DANGER);
			return;
		}
		List<Target> ts = new ArrayList<>();
		selection.forEach((b, n) -> ts.add(Target.ofBlock(b, n)));
		Collector.get().start(ts, null, null, null);
		if (Collector.get().active()) onClose();
	}

	private void startSchematic() {
		if (plan == null) {
			flash(L.t("haulback.ui.pick_readable"), Theme.DANGER);
			return;
		}
		boolean buildOnly = schemMode == 1;
		boolean build = schemMode != 0;
		if (build && origin == null) {
			flash(L.t("haulback.ui.stand_at_origin"), Theme.DANGER);
			return;
		}
		Map<Item, Integer> wanted = schematicData.items();
		Set<Item> forced = new HashSet<>(forceDirect);
		boolean gather = !buildOnly;
		Collector.get().start(stock -> SchematicPlanner.plan(wanted, forced, buildOnly, stock),
				gather, build ? schematic : null, build ? origin : null, wanted.keySet());
		if (Collector.get().active()) onClose();
	}

	// ------------------------------------------------------------------ widgets dessines a la main

	private void button(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String label, Kind kind, Consumer<MouseButtonEvent> action) {
		boolean hover = in(mx, my, x, y, x + w, y + h);
		int bg, fg;
		switch (kind) {
			case PRIMARY -> {
				for (int i = 3; i >= 1; i--) round(g, x - i, y - i, w + i * 2, h + i * 2, hover ? 0x1CF2A93B : 0x0EF2A93B);
				bg = hover ? 0xFFFFBC55 : Theme.ACCENT;
				fg = 0xFF1A1407;
			}
			case DANGER -> {
				bg = hover ? Theme.DANGER : 0x33E5534B;
				fg = hover ? 0xFF1A0908 : Theme.DANGER;
			}
			case GHOST -> {
				bg = hover ? Theme.SURFACE_HI : Theme.SURFACE;
				fg = hover ? Theme.TEXT : Theme.MUTED;
			}
			default -> {
				bg = hover ? 0xFF30363D : Theme.SURFACE_HI;
				fg = Theme.TEXT;
			}
		}
		round(g, x, y, w, h, bg);
		if (kind == Kind.PRIMARY) g.fill(x + 2, y, x + w - 2, y + 1, 0x55FFFFFF);
		g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2 + 1, fg, false);
		hits.add(new Hit(x, y, x + w, y + h, action));
	}

	private void stepButton(GuiGraphics g, int mx, int my, int x, int y, String label, Consumer<MouseButtonEvent> action) {
		boolean hover = in(mx, my, x, y, x + 14, y + 15);
		if (hover) round(g, x, y, 14, 15, Theme.SURFACE_HI);
		g.drawString(font, label, x + (14 - font.width(label)) / 2 + 1, y + 4, hover ? Theme.TEXT : Theme.MUTED, false);
		hits.add(new Hit(x, y, x + 14, y + 15, action));
	}

	private void checkbox(GuiGraphics g, int mx, int my, int x, int y, String label, boolean on, Consumer<MouseButtonEvent> action) {
		int tw = 20;
		round(g, x, y + 1, tw, 10, on ? 0x55F2A93B : 0xFF0E1013);
		round(g, on ? x + tw - 9 : x + 1, y + 2, 8, 8, on ? Theme.ACCENT : Theme.MUTED);
		boolean hover = in(mx, my, x, y, x + tw + 6 + font.width(label), y + 12);
		g.drawString(font, label, x + tw + 6, y + 2, hover || on ? Theme.TEXT : Theme.MUTED, false);
		hits.add(new Hit(x, y, x + tw + 6 + font.width(label), y + 12, action));
	}

	private void scrollbar(GuiGraphics g, int x, int top, int bottom, int scroll, int max, int visible) {
		if (max <= 0) return;
		int track = bottom - top;
		int total = max + visible;
		int h = Math.max(14, track * visible / total);
		int y = top + (track - h) * scroll / max;
		g.fill(x, top, x + 1, bottom, 0x14FFFFFF);
		g.fill(x - 1, y, x + 2, y + h, 0x66FFFFFF);
	}

	private void centered(GuiGraphics g, String s, int cx, int y, int color) {
		g.drawString(font, s, cx - font.width(s) / 2, y, color, false);
	}

	private static void round(GuiGraphics g, int x, int y, int w, int h, int color) {
		if (w < 4 || h < 4) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		g.fill(x + 2, y, x + w - 2, y + h, color);
		g.fill(x + 1, y + 1, x + 2, y + h - 1, color);
		g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, color);
		g.fill(x, y + 2, x + 1, y + h - 2, color);
		g.fill(x + w - 1, y + 2, x + w, y + h - 2, color);
	}

	private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
		g.fill(x + 2, y, x + w - 2, y + 1, color);
		g.fill(x + 2, y + h - 1, x + w - 2, y + h, color);
		g.fill(x, y + 2, x + 1, y + h - 2, color);
		g.fill(x + w - 1, y + 2, x + w, y + h - 2, color);
		g.fill(x + 1, y + 1, x + 2, y + 2, color);
		g.fill(x + w - 2, y + 1, x + w - 1, y + 2, color);
		g.fill(x + 1, y + h - 2, x + 2, y + h - 1, color);
		g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, color);
	}

	private static int mul(int argb, float f) {
		int a = (int) ((argb >>> 24) * Math.max(0, Math.min(1, f)));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	private static boolean in(int mx, int my, int x0, int y0, int x1, int y1) {
		return mx >= x0 && mx < x1 && my >= y0 && my < y1;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	// ------------------------------------------------------------------ entrees

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() == 0) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit h = hits.get(i);
				if (h.contains(e.x(), e.y())) {
					h.action.accept(e);
					return true;
				}
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		int step = v > 0 ? -1 : 1;
		if (mx < rightX - 6) leftScroll = clamp(leftScroll + step, 0, leftMax);
		else rightScroll = clamp(rightScroll + step, 0, rightMax);
		return true;
	}
}
