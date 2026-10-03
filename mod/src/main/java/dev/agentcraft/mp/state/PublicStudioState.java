package dev.agentcraft.mp.state;

import java.util.*;
import org.jspecify.annotations.Nullable;

public record PublicStudioState(int rev, boolean foremanOnline, List<PublicAgent> agents, Counts counts, GoalSummary goal, List<CiSlot> ci, PublicPolicy policy, @Nullable List<PublicTask> tasks) {
    public PublicStudioState { agents = List.copyOf(agents); ci = List.copyOf(ci); tasks = tasks == null ? null : List.copyOf(tasks); }
}
