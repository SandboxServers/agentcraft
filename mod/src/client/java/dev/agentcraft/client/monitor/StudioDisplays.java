package dev.agentcraft.client.monitor;

import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.mp.Studios;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/**
 * Resolves a panel's studio once per position and caches it until the {@link Studios} registry
 * changes. The displays call this every frame during extraction; {@link Studios#at} walks its
 * snapshots and boxes, so caching keeps that off the steady-frame allocation path.
 */
public final class StudioDisplays {
	private static final Map<BlockPos, Optional<StudioView>> CACHE = new HashMap<>();
	private static boolean installed;

	private StudioDisplays() {
	}

	/** Install the invalidation listener once (from any feature's {@code init()}). */
	public static void init() {
		if (installed) {
			return;
		}
		installed = true;
		Studios.addListener((id, view) -> CACHE.clear());
	}

	/** The studio a panel at {@code pos} belongs to, cached across frames. */
	public static Optional<StudioView> at(BlockPos pos) {
		return CACHE.computeIfAbsent(pos.immutable(), Studios::at);
	}
}
