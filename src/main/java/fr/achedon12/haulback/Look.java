package fr.achedon12.haulback;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * "Human" head rotation: turn progressively towards the target, with a speed
 * that slows down on approach and a slight jitter, instead of an instant snap.
 */
public final class Look {
	private Look() {}

	/**
	 * Turns the head one step towards {@code target}.
	 *
	 * @return true once the view is on target (within a few degrees)
	 */
	public static boolean towards(LocalPlayer p, Vec3 target) {
		Vec3 eye = p.getEyePosition();
		double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		float wantYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
		float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, h));

		float dYaw = Mth.wrapDegrees(wantYaw - p.getYRot());
		float dPitch = wantPitch - p.getXRot();
		float dist = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
		if (dist < 4f) return true;

		ThreadLocalRandom r = ThreadLocalRandom.current();
		// speed proportional to the gap (damped), clamped, with a bit of noise
		float speed = Mth.clamp(dist * 0.35f, 3f, 28f) * (0.85f + r.nextFloat() * 0.3f);
		float k = Math.min(1f, speed / dist);
		p.setYRot(p.getYRot() + dYaw * k + (r.nextFloat() - 0.5f) * 0.6f);
		p.setXRot(Mth.clamp(p.getXRot() + dPitch * k + (r.nextFloat() - 0.5f) * 0.4f, -90f, 90f));
		return false;
	}
}
