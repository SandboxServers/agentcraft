package dev.agentcraft.client.mp.publish;

import dev.agentcraft.client.agents.AgentManager;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.mp.state.AgentStateWire;
import dev.agentcraft.mp.state.CiSlot;
import dev.agentcraft.mp.state.CiStatusWire;
import dev.agentcraft.mp.state.Counts;
import dev.agentcraft.mp.state.GoalStatusWire;
import dev.agentcraft.mp.state.GoalSummary;
import dev.agentcraft.mp.state.MpText;
import dev.agentcraft.mp.state.PublicAgent;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import dev.agentcraft.mp.state.PublicTask;
import dev.agentcraft.mp.state.StationWire;
import dev.agentcraft.mp.state.TaskStatusWire;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Pure projection of a Foreman model onto the public allowlist. {@code rev} stays 0; the
 * scheduler stamps it when a state is sent. Strings are cut and sanitized here because the
 * codec refuses, rather than trims, and a refusal disconnects the owner.
 */
public final class Redactor {
    private Redactor() {}

    public static PublicStudioState redact(ForemanState state, PublicPolicy policy) {
        Map<String, String> ids = publicIds(state);
        List<PublicAgent> agents = new ArrayList<>(ids.size());
        for (Protocol.Agent agent : state.agents().values()) {
            String id = ids.get(agent.id());
            if (id == null) continue;
            String name = clip(agent.name(), 16);
            if (name.isBlank()) name = id;
            String skin = clip(agent.skin(), 16);
            if (skin.isBlank()) skin = id;
            boolean awaiting = false;
            for (Protocol.Decision decision : state.openDecisions()) {
                if (agent.id().equals(AgentManager.owner(state, decision))) {
                    awaiting = true;
                    break;
                }
            }
            agents.add(new PublicAgent(id, name, skin, agentState(agent.state()), station(agent.station()),
                agent.isActive(), agent.isPaused(), awaiting, optIn(agent.activity(), 48, policy.activityText())));
        }
        List<PublicTask> tasks = null;
        if (policy.taskTitles()) {
            tasks = new ArrayList<>();
            for (Protocol.Task task : state.tasks().values()) {
                if (tasks.size() == 32) break;
                TaskStatusWire status = taskStatus(task.status());
                if (status == null) continue;
                String id = clip(task.id(), 48);
                if (id.isEmpty()) continue;
                String title = clip(task.title(), 80);
                if (title.isBlank()) continue;
                String assignee = ids.get(task.assignee());
                tasks.add(new PublicTask(id, title, status, assignee));
            }
        }
        return new PublicStudioState(0, state.link().synced(), List.copyOf(agents), counts(state), goal(state, policy), ci(state), policy, tasks);
    }

    public static @Nullable PublicEvent say(ForemanState state, Protocol.AgentSay say, PublicPolicy policy) {
        if (say.agentId() == null) return null;
        String id = publicIds(state).get(say.agentId());
        if (id == null) return null;
        String to = say.to();
        String publishedTo = null;
        if ("user".equals(to) || "all".equals(to)) publishedTo = to;
        else if (to != null) publishedTo = publicIds(state).get(to);
        return new PublicEvent.Say(id, publishedTo, optIn(say.text(), 120, policy.sayText()), say.text().length());
    }

    /** A task-done event for the transition into {@code DONE} with an assignee who is a published agent. */
    public static @Nullable PublicEvent taskDone(ForemanState state, Protocol.@Nullable Task previous, Protocol.Task now) {
        if (now.status() != Protocol.TaskStatus.DONE) return null;
        if (previous != null && previous.status() == Protocol.TaskStatus.DONE) return null;
        if (now.assignee() == null) return null;
        String id = publicIds(state).get(now.assignee());
        return id == null ? null : new PublicEvent.TaskDone(id);
    }

    private static Map<String, String> publicIds(ForemanState state) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (Protocol.Agent agent : state.agents().values()) {
            if (ids.size() == 16 || agent.id() == null) continue;
            String id = clip(agent.id(), 16);
            if (id.isEmpty() || ids.containsValue(id)) continue;
            ids.put(agent.id(), id);
        }
        return ids;
    }

    private static Counts counts(ForemanState state) {
        int merges = 0;
        for (Protocol.Decision decision : state.openDecisions()) {
            if (decision.kind() == Protocol.DecisionKind.MERGE) merges++;
        }
        return new Counts(
            state.tasksWithStatus(Protocol.TaskStatus.TODO).size(),
            state.tasksWithStatus(Protocol.TaskStatus.DOING).size(),
            state.tasksWithStatus(Protocol.TaskStatus.REVIEW).size(),
            state.tasksWithStatus(Protocol.TaskStatus.DONE).size(),
            state.tasksWithStatus(Protocol.TaskStatus.BLOCKED).size(),
            state.openDecisions().size(), merges);
    }

    private static GoalSummary goal(ForemanState state, PublicPolicy policy) {
        Protocol.Goal goal = state.goal();
        if (goal == null || goal.status() == Protocol.GoalStatus.UNKNOWN) return new GoalSummary(GoalStatusWire.NONE, 0f, null);
        double rawProgress = goal.progress();
        float progress;
        if (!Double.isFinite(rawProgress) || rawProgress < 0d) progress = 0f;
        else if (rawProgress > 1d) progress = 1f;
        else progress = (float) rawProgress;
        return new GoalSummary(GoalStatusWire.valueOf(goal.status().name()), progress, optIn(goal.text(), 120, policy.goalText()));
    }

    private static List<CiSlot> ci(ForemanState state) {
        List<CiSlot> slots = new ArrayList<>();
        int slot = 0;
        for (Protocol.Repo repo : state.repos().values()) {
            if (slot == 8) break;
            slots.add(new CiSlot(slot, CiStatusWire.valueOf(repo.ci().name())));
            slot++;
        }
        return List.copyOf(slots);
    }

    private static AgentStateWire agentState(Protocol.AgentState state) {
        if (state == null || state == Protocol.AgentState.UNKNOWN) return AgentStateWire.IDLE;
        return AgentStateWire.valueOf(state.name());
    }

    private static StationWire station(Protocol.Station station) {
        if (station == null || station == Protocol.Station.UNKNOWN) return StationWire.DESK;
        return StationWire.valueOf(station.name());
    }

    private static @Nullable TaskStatusWire taskStatus(Protocol.TaskStatus status) {
        if (status == null || status == Protocol.TaskStatus.UNKNOWN) return null;
        return TaskStatusWire.valueOf(status.name());
    }

    private static String clip(String text, int cap) {
        if (text == null) return "";
        String cut = text;
        if (cut.length() > cap) {
            int end = cap;
            if (end > 0 && Character.isHighSurrogate(cut.charAt(end - 1))) end--;
            cut = cut.substring(0, Math.max(end, 0));
        }
        return MpText.sanitize(cut, cap);
    }

    private static @Nullable String optIn(String text, int cap, boolean enabled) {
        if (!enabled) return null;
        String clipped = clip(text, cap);
        return clipped.isBlank() ? null : clipped;
    }
}
