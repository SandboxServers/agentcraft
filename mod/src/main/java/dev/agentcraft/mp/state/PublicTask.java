package dev.agentcraft.mp.state;

import java.util.*;
import org.jspecify.annotations.Nullable;

public record PublicTask(String id, String title, TaskStatusWire status, @Nullable String assignee) {
}
