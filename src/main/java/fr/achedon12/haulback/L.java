package fr.achedon12.haulback;

import net.minecraft.client.resources.language.I18n;

/** Traductions : textes dans assets/haulback/lang (en_us de base, fr_fr, ...). */
public final class L {
	private L() {}

	public static String t(String key) {
		return I18n.get(key);
	}
}
