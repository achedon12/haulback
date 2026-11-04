package fr.achedon12.haulback.ui;

import fr.achedon12.haulback.Collector;
import fr.achedon12.haulback.job.Target;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * HUD de mission : carte graphite en haut a gauche.
 * Voyant d'etat qui respire, pourcentage global, barres animees avec reflet,
 * distances vers le coffre et le point A, chrono. Reste affiche quelques secondes
 * a la fin avec le resultat, puis s'efface.
 */
public final class Hud {
	private static final int W = 150;
	private static final int PAD = 8;
	private static final int MAX_ROWS = 3;
	private static final long LINGER_MS = 7000;

	/** Valeurs affichees (lissees) par cible, pour que les barres glissent au lieu de sauter. */
	private static final Map<Target, Float> shown = new IdentityHashMap<>();
	private static float shownTotal;
	private static float appear;
	private static long lastFrame;

	private Hud() {}

	public static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Collector c = Collector.get();
		long now = System.currentTimeMillis();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;

		boolean lingering = !c.active() && c.endedAt() > 0 && now - c.endedAt() < LINGER_MS;
		boolean visible = (c.active() || lingering) && !mc.options.hideGui && mc.player != null;
		appear = approach(appear, visible ? 1f : 0f, dt * 6f);
		if (appear <= 0.01f) {
			if (!visible) shown.clear();
			return;
		}
		if (lingering && now - c.endedAt() > LINGER_MS - 600) {
			appear = Math.min(appear, (LINGER_MS - (now - c.endedAt())) / 600f);
		}

		Font f = mc.font;
		List<Target> ts = c.targets();
		int rows = Math.min(ts.size(), MAX_ROWS);
		boolean building = c.phase() == Collector.Phase.BUILDING || (ts.isEmpty() && c.hasBuild());
		List<net.minecraft.world.item.Item> toolPlan = c.phase() == Collector.Phase.TOOLS ? c.tools().planned() : List.of();

		int h = 6 + 10 + 4 + 10 + 5;
		if (!toolPlan.isEmpty()) h += 14;
		if (rows > 0) h += 3 + rows * 13;
		if (ts.size() > rows) h += 10;
		h += 3 + 10 + 3;

		float ease = easeOut(appear);
		int x = (int) (4 - (1 - ease) * 24);
		int y = 4;
		int alpha = (int) (255 * ease);
		int right = x + W - 7;

		shadow(g, x, y, W, h, alpha);
		roundRect(g, x, y, W, h, mul(0xE6121418, alpha));
		g.fill(x + 2, y, x + W - 2, y + 1, mul(0x26FFFFFF, alpha));
		int accent = phaseColor(c, lingering);
		g.fill(x, y + 3, x + 1, y + h - 3, mul(accent, alpha));

		// en-tete
		int cx = x + 7;
		int cy = y + 6;
		float pulse = lingering ? 1f : 0.55f + 0.45f * (float) Math.sin(now / 260.0);
		dot(g, cx + 2, cy + 3, mul(glow(accent, (int) (0x55 * pulse)), alpha), mul(accent, alpha));
		drawSpaced(g, f, "HAULBACK", cx + 9, cy, mul(0xFFE9EBEE, alpha));
		String phase = lingering ? (c.endedWell() ? "terminé" : "arrêté") : phaseLabel(c.phase());
		int pw = f.width(phase) + 8;
		roundRect(g, right - pw, cy - 2, pw, 11, mul(glow(accent, 0x2E), alpha));
		g.drawString(f, phase, right - pw + 4, cy, mul(accent, alpha), false);

		// statut + pourcentage
		int sy = cy + 14;
		shownTotal = approach(shownTotal, overall(ts), dt * 2.5f);
		String pct;
		if (lingering && c.endedWell()) pct = "OK";
		else if (building && ts.stream().allMatch(Target::done)) pct = "";
		else pct = Math.round(shownTotal * 100) + "%";
		String st = lingering ? (c.endedWell() ? "Revenu au point A" : c.status()) : c.status();
		g.drawString(f, f.plainSubstrByWidth(st, W - 20 - f.width(pct) - 6), cx, sy, mul(0xFFC9CDD3, alpha), false);
		g.drawString(f, pct, right - f.width(pct), sy, mul(0xFFFFFFFF, alpha), false);
		int by = sy + 11;
		if (building && !lingering) indeterminate(g, cx, by, right - cx, accent, alpha, now);
		else bar(g, cx, by, right - cx, 2, lingering && c.endedWell() ? 1f : shownTotal, accent, alpha, now);
		int ry = by + 6;

		// outils en fabrication
		if (!toolPlan.isEmpty()) {
			int ix = cx;
			for (net.minecraft.world.item.Item it : toolPlan) {
				boolean ok = mc.player.getInventory().contains(stack -> stack.is(it));
				mini(g, new ItemStack(it), ix, ry, 0.625f);
				if (!ok) g.fill(ix, ry, ix + 10, ry + 10, mul(0xA0121418, alpha));
				g.fill(ix, ry + 11, ix + 10, ry + 12, mul(ok ? 0xFF5BD67A : 0x22FFFFFF, alpha));
				ix += 14;
			}
			ry += 14;
		}

		// objectifs
		if (rows > 0) {
			ry += 3;
			for (int i = 0; i < rows; i++) {
				Target t = ts.get(i);
				float target = t.needed == 0 ? 1 : Math.min(1f, t.got / (float) t.needed);
				float v = approach(shown.getOrDefault(t, 0f), target, dt * 3f);
				shown.put(t, v);
				ItemStack icon = t.icon == null || t.icon.isEmpty() ? new ItemStack(Items.BARRIER) : t.icon;
				mini(g, icon, cx, ry, 0.625f);
				String count = Math.min(t.got, t.needed) + "/" + t.needed;
				int lx = cx + 14;
				g.drawString(f, f.plainSubstrByWidth(t.label, right - lx - f.width(count) - 6), lx, ry + 1,
						mul(t.done() ? 0xFF7FE39A : 0xFFE6E8EB, alpha), false);
				g.drawString(f, count, right - f.width(count), ry + 1, mul(t.done() ? 0xFF7FE39A : 0xFF8B929C, alpha), false);
				bar(g, lx, ry + 11, right - lx, 1, v, t.done() ? 0xFF5BD67A : accent, alpha, now + i * 140L);
				ry += 13;
			}
			if (ts.size() > rows) {
				g.drawString(f, "+ " + (ts.size() - rows) + " autres", cx + 14, ry, mul(0xFF5C636D, alpha), false);
				ry += 10;
			}
		}

		// pied
		ry += 3;
		String left = distances(mc.player, c);
		String chrono = chrono(c, now, lingering);
		g.drawString(f, left, cx, ry, mul(0xFF6B737E, alpha), false);
		g.drawString(f, chrono, right - f.width(chrono), ry, mul(0xFF6B737E, alpha), false);
	}

	private static void mini(GuiGraphics g, ItemStack stack, int x, int y, float scale) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.renderItem(stack, 0, 0);
		g.pose().popMatrix();
	}

	// ------------------------------------------------------------------ contenu

	private static float overall(List<Target> ts) {
		int done = 0, total = 0;
		for (Target t : ts) {
			done += Math.min(t.got, t.needed);
			total += t.needed;
		}
		return total == 0 ? 0 : done / (float) total;
	}

	private static String subline(LocalPlayer p, boolean building) {
		if (p == null) return "";
		if (building) return "Baritone pose les blocs";
		int free = 0;
		for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) free++;
		return free + " cases libres";
	}

	private static String distances(LocalPlayer p, Collector c) {
		if (p == null) return "";
		StringBuilder sb = new StringBuilder();
		BlockPos chest = c.chest();
		BlockPos home = c.home();
		if (chest != null) sb.append("coffre ").append(dist(p, chest)).append("m");
		if (home != null) {
			if (!sb.isEmpty()) sb.append("  ");
			sb.append("A ").append(dist(p, home)).append("m");
		}
		return sb.toString();
	}

	private static int dist(LocalPlayer p, BlockPos pos) {
		return (int) Math.round(Math.sqrt(p.blockPosition().distSqr(pos)));
	}

	private static String chrono(Collector c, long now, boolean lingering) {
		if (c.startedAt() == 0) return "";
		long end = lingering ? c.endedAt() : now;
		long s = Math.max(0, (end - c.startedAt()) / 1000);
		return String.format("%d:%02d", s / 60, s % 60);
	}

	static String phaseLabel(Collector.Phase p) {
		return switch (p) {
			case IDLE -> "inactif";
			case MINING -> "récolte";
			case PICKUP -> "ramassage";
			case EATING -> "repas";
			case TO_CHEST -> "vers coffre";
			case AT_CHEST -> "coffre";
			case RESTING -> "repos";
			case BUILDING -> "construction";
			case TO_HOME -> "retour A";
			default -> "outils";
		};
	}

	private static int phaseColor(Collector c, boolean lingering) {
		if (lingering) return c.endedWell() ? 0xFF5BD67A : 0xFFE5534B;
		return switch (c.phase()) {
			case TO_CHEST, AT_CHEST, TO_HOME -> 0xFF5CC8FF;
			case BUILDING -> 0xFF5BD67A;
			case EATING, RESTING -> 0xFFFF7A59;
			default -> 0xFFF2A93B;
		};
	}

	// ------------------------------------------------------------------ primitives

	private static void bar(GuiGraphics g, int x, int y, int w, int h, float v, int color, int alpha, long now) {
		g.fill(x, y, x + w, y + h, mul(0x1FFFFFFF, alpha));
		int fw = Math.round(w * Mth.clamp(v, 0, 1));
		if (fw <= 0) return;
		g.fill(x, y, x + fw, y + h, mul(color, alpha));
		g.fill(x, y, x + fw, y + 1, mul(0x40FFFFFF, alpha));
		if (v < 1f && fw > 12) {
			int sw = 10;
			int sx = (int) ((now / 9) % (fw + sw * 2)) - sw;
			int a = Math.max(x, x + sx), b = Math.min(x + fw, x + sx + sw);
			if (b > a) g.fill(a, y, b, y + h, mul(0x55FFFFFF, alpha));
		}
		g.fill(x + fw - 1, y - 1, x + fw, y + h + 1, mul(0xCCFFFFFF, alpha));
	}

	private static void indeterminate(GuiGraphics g, int x, int y, int w, int color, int alpha, long now) {
		g.fill(x, y, x + w, y + 3, mul(0x1FFFFFFF, alpha));
		int seg = w / 3;
		int sx = (int) ((now / 6) % (w + seg)) - seg;
		int a = Math.max(x, x + sx), b = Math.min(x + w, x + sx + seg);
		if (b > a) {
			g.fill(a, y, b, y + 3, mul(color, alpha));
			g.fill(a, y, b, y + 1, mul(0x40FFFFFF, alpha));
		}
	}

	private static void dot(GuiGraphics g, int cx, int cy, int halo, int core) {
		g.fill(cx - 3, cy - 2, cx + 3, cy + 2, halo);
		g.fill(cx - 2, cy - 3, cx + 2, cy + 3, halo);
		g.fill(cx - 1, cy - 2, cx + 1, cy + 2, core);
		g.fill(cx - 2, cy - 1, cx + 2, cy + 1, core);
	}

	private static void roundRect(GuiGraphics g, int x, int y, int w, int h, int color) {
		g.fill(x + 2, y, x + w - 2, y + h, color);
		g.fill(x + 1, y + 1, x + 2, y + h - 1, color);
		g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, color);
		g.fill(x, y + 2, x + 1, y + h - 2, color);
		g.fill(x + w - 1, y + 2, x + w, y + h - 2, color);
	}

	private static void shadow(GuiGraphics g, int x, int y, int w, int h, int alpha) {
		for (int i = 1; i <= 3; i++) {
			roundRect(g, x - i + 1, y - i + 2, w + i * 2 - 2, h + i * 2 - 2, mul(0x10000000, alpha));
		}
	}

	/** Texte en capitales espacees. */
	private static void drawSpaced(GuiGraphics g, Font f, String s, int x, int y, int color) {
		int cx = x;
		for (int i = 0; i < s.length(); i++) {
			String ch = String.valueOf(s.charAt(i));
			g.drawString(f, ch, cx, y, color, false);
			cx += f.width(ch) + 1;
		}
	}

	private static int glow(int color, int a) {
		return (a << 24) | (color & 0xFFFFFF);
	}

	/** Applique l'opacite globale (apparition/disparition) a une couleur ARGB. */
	private static int mul(int argb, int alpha) {
		int a = (argb >>> 24) * alpha / 255;
		return (a << 24) | (argb & 0xFFFFFF);
	}

	private static float approach(float from, float to, float step) {
		if (step <= 0) return from;
		float d = to - from;
		if (Math.abs(d) < 0.002f) return to;
		return from + d * Math.min(1f, step);
	}

	private static float easeOut(float t) {
		float u = 1 - t;
		return 1 - u * u * u;
	}
}
