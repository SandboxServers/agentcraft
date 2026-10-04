package dev.agentcraft.client.monitor;

import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.mp.Studios;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.BlockPos;

/**
 * Resolves a panel's studio once per position and caches it so the displays keep the
 * {@link Studios#at} walk (snapshots and boxes) off the steady frame.
 *
 * <p>The cache is invalidated two ways, and both are needed. The {@link Studios} listener clears it
 * the moment the registry publishes, updates or removes a view, so a resolved studio is correct in
 * the same client task. But {@link Studios#setPlot} and {@link Studios#setOverlay} change what
 * {@link Studios#at} returns without firing a listener, so a client tick clears the cache once a
 * second as a backstop; a caller that happened to look between the change and the next publish would
 * otherwise keep a stale resolution.
 */
public final class StudioDisplays {
	private static final Map<BlockPos, Optional<StudioView>> CACHE = new HashMap<>();
	private static boolean installed;

	private StudioDisplays() {
	}

	/** Install the invalidation listener and the tick backstop once (from any feature's {@code init()}). */
	public static void init() {
		if (installed) {
			return;
		}
		installed = true;
		Studios.addListener((id, view) -> CACHE.clear());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level != null && (mc.level.getGameTime() % 20) == 0) {
				CACHE.clear();
			}
		});
	}

	/** The studio a panel at {@code pos} belongs to, cached across frames. */
	public static Optional<StudioView> at(BlockPos pos) {
		return CACHE.computeIfAbsent(pos.immutable(), Studios::at);
	}
}
