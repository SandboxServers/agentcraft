package dev.agentcraft.mp.state;

import java.util.*;
import org.jspecify.annotations.Nullable;

public record WorldIntent(int rev, Map<String, LampStatusWire> lamps, boolean podiumOpen, boolean mergeActive, Set<String> litMonitors) {
    public WorldIntent { lamps = Map.copyOf(lamps); litMonitors = Set.copyOf(litMonitors); }
}
