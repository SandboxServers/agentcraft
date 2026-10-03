package dev.agentcraft.mp.state;

import java.util.*;
import org.jspecify.annotations.Nullable;

public record PublicAgent(String id, String name, String skin, AgentStateWire state, StationWire station, boolean active, boolean paused, boolean awaitingUser, @Nullable String activity) {
}
