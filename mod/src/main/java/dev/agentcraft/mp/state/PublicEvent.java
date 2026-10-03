package dev.agentcraft.mp.state;

import org.jspecify.annotations.Nullable;

public sealed interface PublicEvent permits PublicEvent.Say, PublicEvent.TaskDone {
    record Say(String agentId, @Nullable String to, @Nullable String text, int length) implements PublicEvent {}
    record TaskDone(String agentId) implements PublicEvent {}
}
