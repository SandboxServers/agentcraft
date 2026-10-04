package dev.agentcraft.mp.publish;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.ForemanStates;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.mp.publish.PublishFeature;
import dev.agentcraft.client.mp.publish.PublishScheduler;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PublishSchedulerTest {
    private final UUID player = UUID.nameUUIDFromBytes("publish-player".getBytes());
    private final UUID studio = UUID.nameUUIDFromBytes("publish-studio".getBytes());

    @Test void two_changes_in_one_flush_send_the_later_state_once() {
        Harness harness = new Harness(4);
        harness.scheduler.markDirty();
        harness.patch("thinking");
        harness.scheduler.markDirty();
        harness.scheduler.flush(0, true);
        assertEquals(1, harness.states.size());
        assertEquals("thinking", harness.states.get(0).agents().stream().filter(agent -> agent.id().equals("marlow")).findFirst().orElseThrow().state().wire());
        assertEquals(1, harness.states.get(0).rev());
    }

    @Test void unchanged_projection_is_not_sent_and_does_not_spend_a_token() {
        Harness harness = new Harness(1);
        try (var capture = MpLog.capture()) {
            harness.scheduler.markDirty();
            harness.scheduler.flush(0, true);
            harness.scheduler.markDirty();
            harness.scheduler.flush(0, true);
            harness.patch("thinking");
            harness.scheduler.markDirty();
            harness.scheduler.flush(0, true);
            assertEquals(1, capture.lines().stream().filter(line -> line.contains("reason=" + MpReasons.UNCHANGED)).count());
        }
        assertEquals(2, harness.states.size());
        assertEquals(List.of(1, 2), harness.states.stream().map(PublicStudioState::rev).toList());
    }

    @Test void state_past_the_burst_is_kept_and_sent_when_the_bucket_refills() {
        Harness harness = new Harness(1);
        harness.sendChanged(0, "thinking");
        harness.sendChanged(0, "editing");
        try (var capture = MpLog.capture()) {
            harness.patch("reading");
            harness.scheduler.markDirty();
            harness.scheduler.flush(0, true);
            harness.scheduler.flush(0, true);
            assertEquals(2, harness.states.size());
            assertEquals(1, capture.lines().stream().filter(line -> line.contains("reason=" + MpReasons.RATE_LIMITED)).count());
            assertFalse(capture.lines().toString().contains("reading"));
        }
        harness.scheduler.flush(1_000_000_000L, true);
        assertEquals(3, harness.states.size());
        assertEquals(3, harness.states.get(2).rev());
    }

    @Test void events_past_the_rate_are_dropped() {
        Harness harness = new Harness(1);
        PublicEvent first = say("one");
        PublicEvent second = say("two");
        PublicEvent third = say("SECRET_DROPPED_SAY");
        harness.scheduler.offer(first, 0, true);
        harness.scheduler.offer(second, 0, true);
        try (var capture = MpLog.capture()) {
            harness.scheduler.offer(third, 0, true);
            harness.scheduler.offer(third, 0, true);
            assertEquals(List.of(first, second), harness.events);
            assertEquals(1, capture.lines().stream().filter(line -> line.contains("reason=" + MpReasons.RATE_LIMITED)).count());
            assertFalse(capture.lines().toString().contains("SECRET_DROPPED_SAY"));
        }
        harness.scheduler.flush(1_000_000_000L, true);
        assertEquals(2, harness.events.size());
    }

    @Test void not_multiplayer_sends_nothing() {
        Harness harness = new Harness(4);
        harness.scheduler.markDirty();
        try (var capture = MpLog.capture()) {
            harness.scheduler.flush(0, false);
            harness.scheduler.flush(0, false);
            harness.scheduler.offer(say("SECRET_OFFLINE_SAY"), 0, false);
            assertTrue(harness.states.isEmpty());
            assertTrue(harness.events.isEmpty());
            assertEquals(1, capture.lines().stream().filter(line -> line.contains("reason=" + MpReasons.NOT_MULTIPLAYER)).count());
            assertFalse(capture.lines().toString().contains("SECRET_OFFLINE_SAY"));
            assertTrue(capture.lines().get(0).contains("player=" + player));
            assertTrue(capture.lines().get(0).contains("studio=" + studio));
        }
    }

    @Test void reconnect_sends_the_same_projection_again_at_rev_1() {
        Harness harness = new Harness(4);
        harness.scheduler.markDirty();
        harness.scheduler.flush(0, true);
        harness.scheduler.reconnect();
        harness.scheduler.flush(1, true);
        assertEquals(2, harness.states.size());
        assertEquals(1, harness.states.get(0).rev());
        assertEquals(1, harness.states.get(1).rev());
        PublicStudioState first = harness.states.get(0);
        PublicStudioState second = harness.states.get(1);
        assertEquals(new PublicStudioState(0, first.foremanOnline(), first.agents(), first.counts(), first.goal(), first.ci(), first.policy(), first.tasks()),
            new PublicStudioState(0, second.foremanOnline(), second.agents(), second.counts(), second.goal(), second.ci(), second.policy(), second.tasks()));
    }

    @Test void sent_telemetry_has_counts_and_no_foreman_text() {
        Harness harness = new Harness(4);
        String activity = harness.state.agents().values().iterator().next().activity();
        assertTrue(activity.contains("`notes"));
        try (var capture = MpLog.capture()) {
            harness.scheduler.markDirty();
            harness.scheduler.flush(0, true);
            String line = capture.lines().get(0);
            assertTrue(line.startsWith("event=" + MpEvents.PUBLIC_STATE_SENT));
            assertTrue(line.contains("player=" + player));
            assertTrue(line.contains("studio=" + studio));
            assertTrue(line.contains("plot=4"));
            assertTrue(line.contains("rev=1"));
            assertTrue(line.contains("agents=" + harness.states.get(0).agents().size()));
            assertTrue(line.contains("bytes="));
            assertTrue(line.contains("policy=0"));
            assertFalse(capture.lines().toString().contains("`notes"));
        }
    }

    @Test void private_callbacks_do_not_publish() {
        Harness harness = new Harness(4);
        var listener = PublishFeature.listener(harness.scheduler, () -> true, () -> true, () -> PublicPolicy.DEFAULT, () -> harness.state);
        listener.onLog("marlow", List.of(new Protocol.LogEntry(1, Protocol.LogKind.TEXT, "SECRET_LOG_LINE")));
        listener.onChange(1);
        listener.onFeed(new Protocol.FeedItem(1, Protocol.FeedKind.MESSAGE, "SECRET_FEED_LINE", "marlow", null));
        listener.onMemory(null, new Protocol.MemoryEntry("m", "shared", "SECRET_MEMORY", "SECRET_MEMORY_BODY", 1, null));
        listener.onNotify(new Protocol.Notify(Protocol.NotifyLevel.INFO, "SECRET_NOTIFY", null, 1));
        listener.onStatus(harness.state.status());
        harness.scheduler.flush(0, true);
        assertTrue(harness.states.isEmpty());
        assertTrue(harness.events.isEmpty());
    }

    @Test void snapshot_marks_dirty_and_a_say_uses_the_policy() {
        Harness harness = new Harness(4);
        var listener = PublishFeature.listener(harness.scheduler, () -> true, () -> true, () -> PublicPolicy.DEFAULT, () -> harness.state);
        listener.onSnapshot(harness.state);
        harness.scheduler.flush(0, true);
        assertEquals(1, harness.states.size());
        assertTrue(harness.events.isEmpty());
        listener.onSay(new Protocol.AgentSay("marlow", "SECRET_SAY_BODY", "user", 1));
        assertEquals(1, harness.events.size());
        assertNull(((PublicEvent.Say) harness.events.get(0)).text());
        assertEquals("SECRET_SAY_BODY".length(), ((PublicEvent.Say) harness.events.get(0)).length());
    }

    @Test void quiet_tracking_drops_says_without_a_log() {
        Harness harness = new Harness(4);
        var listener = PublishFeature.listener(harness.scheduler, () -> false, () -> true, () -> new PublicPolicy(false, true, false, false), () -> harness.state);
        try (var capture = MpLog.capture()) {
            listener.onSay(new Protocol.AgentSay("marlow", "SECRET_QUIET_SAY", "user", 1));
            listener.onSnapshot(harness.state);
            harness.scheduler.flush(0, true);
            assertTrue(harness.events.isEmpty());
            assertTrue(harness.states.isEmpty());
            assertTrue(capture.lines().isEmpty());
        }
    }

    private static PublicEvent say(String text) {
        return new PublicEvent.Say("marlow", "user", text, text.length());
    }

    private final class Harness {
        final ForemanState state = ForemanStates.showcase();
        final List<PublicStudioState> states = new ArrayList<>();
        final List<PublicEvent> events = new ArrayList<>();
        final PublishScheduler scheduler;

        Harness(int rate) {
            AtomicReference<PublicPolicy> policy = new AtomicReference<>(PublicPolicy.DEFAULT);
            scheduler = new PublishScheduler(() -> state, policy::get, () -> rate, () -> player, () -> studio, () -> 4, new PublishScheduler.Out() {
                @Override public void state(PublicStudioState state) { states.add(state); }
                @Override public void event(PublicEvent event) { events.add(event); }
            });
        }

        void patch(String agentState) {
            JsonObject set = new JsonObject();
            set.addProperty("state", agentState);
            state.patch("agent", "marlow", set);
        }

        void sendChanged(long now, String agentState) {
            patch(agentState);
            scheduler.markDirty();
            scheduler.flush(now, true);
        }
    }
}
