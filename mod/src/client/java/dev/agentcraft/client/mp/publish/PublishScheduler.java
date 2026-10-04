package dev.agentcraft.client.mp.publish;

import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicJson;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Coalesces public state to the client send rate and drops events past that rate.
 * Call it on the client thread only. "Unchanged" compares content with {@code rev} still 0.
 */
public final class PublishScheduler {
    public interface Out {
        void state(PublicStudioState state);
        void event(PublicEvent event);
    }

    private final Supplier<ForemanState> states;
    private final Supplier<PublicPolicy> policy;
    private final IntSupplier ratePerSecond;
    private final Supplier<UUID> player;
    private final Supplier<UUID> studio;
    private final IntSupplier plot;
    private final Out out;
    private boolean dirty;
    private int rev;
    private @Nullable PublicStudioState last;
    private final SendWindow stateWindow = new SendWindow();
    private final SendWindow eventWindow = new SendWindow();
    private final Deque<PublicEvent> heldEvents = new ArrayDeque<>();
    private int rate = -1;
    private boolean loggedUnchanged;
    private boolean loggedStateRate;
    private boolean loggedEventRate;
    private boolean loggedNotMultiplayer;

    public PublishScheduler(Supplier<ForemanState> states, Supplier<PublicPolicy> policy, IntSupplier ratePerSecond,
            Supplier<UUID> player, Supplier<UUID> studio, IntSupplier plot, Out out) {
        this.states = Objects.requireNonNull(states);
        this.policy = Objects.requireNonNull(policy);
        this.ratePerSecond = Objects.requireNonNull(ratePerSecond);
        this.player = Objects.requireNonNull(player);
        this.studio = Objects.requireNonNull(studio);
        this.plot = Objects.requireNonNull(plot);
        this.out = Objects.requireNonNull(out);
    }

    public void markDirty() { dirty = true; }

    /** The next connection starts its send counter at 1, and the current projection is sent again. */
    public void reconnect() {
        rev = 0;
        last = null;
        dirty = true;
        stateWindow.clear();
        eventWindow.clear();
        heldEvents.clear();
        rate = -1;
        loggedUnchanged = false;
        loggedStateRate = false;
        loggedEventRate = false;
        loggedNotMultiplayer = false;
    }

    public void offer(PublicEvent event, long nowNanos, boolean multiplayer) {
        if (event == null) return;
        if (!multiplayer) {
            skipOnceNotMultiplayer();
            return;
        }
        int perSecond = ratePerSecond.getAsInt();
        prepareRate(perSecond);
        if (perSecond < 1) {
            skipOnceNotMultiplayer();
            return;
        }
        loggedNotMultiplayer = false;
        if (dirty) flush(nowNanos, true);
        if (dirty) {
            hold(event, perSecond);
            return;
        }
        drainHeld(nowNanos);
        if (!heldEvents.isEmpty()) {
            hold(event, perSecond);
            return;
        }
        sendEvent(event, perSecond, nowNanos);
    }

    public void flush(long nowNanos, boolean multiplayer) {
        if (!multiplayer) {
            if (dirty || !heldEvents.isEmpty()) skipOnceNotMultiplayer();
            return;
        }
        int perSecond = ratePerSecond.getAsInt();
        prepareRate(perSecond);
        if (perSecond < 1) {
            if (dirty || !heldEvents.isEmpty()) skipOnceNotMultiplayer();
            return;
        }
        loggedNotMultiplayer = false;
        if (dirty) {
            ForemanState state = states.get();
            if (state != null) {
                PublicStudioState projected = Redactor.redact(state, policy.get());
                if (projected.equals(last)) {
                    dirty = false;
                    if (!loggedUnchanged) {
                        skipped(MpReasons.UNCHANGED);
                        loggedUnchanged = true;
                    }
                } else {
                    loggedUnchanged = false;
                    if (!stateWindow.tryTake(perSecond, nowNanos)) {
                        if (!loggedStateRate) {
                            skipped(MpReasons.RATE_LIMITED);
                            loggedStateRate = true;
                        }
                    } else {
                        sendState(projected);
                    }
                }
            }
        }
        if (!dirty) drainHeld(nowNanos);
    }

    private void sendState(PublicStudioState projected) {
        if (rev == Integer.MAX_VALUE) rev = 0;
        PublicStudioState stamped = new PublicStudioState(rev + 1, projected.foremanOnline(), projected.agents(),
            projected.counts(), projected.goal(), projected.ci(), projected.policy(), projected.tasks());
        out.state(stamped);
        rev = stamped.rev();
        last = projected;
        dirty = false;
        loggedStateRate = false;
        MpLog.event(MpEvents.PUBLIC_STATE_SENT,
            "player", player.get(), "studio", studio.get(), "plot", plot.getAsInt(), "rev", stamped.rev(),
            "agents", stamped.agents().size(),
            "bytes", PublicJson.toJson(stamped).toString().getBytes(StandardCharsets.UTF_8).length,
            "policy", PolicyStore.bits(stamped.policy()));
    }

    private void sendEvent(PublicEvent event, int perSecond, long nowNanos) {
        if (!eventWindow.tryTake(perSecond, nowNanos)) {
            logEventRate();
            return;
        }
        loggedEventRate = false;
        out.event(underCurrentPolicy(event));
    }

    /**
     * A held say can be older than the policy. Its text leaves the machine only while {@code sayText}
     * is on at the moment it is sent; otherwise the text is cleared and the length kept.
     */
    private PublicEvent underCurrentPolicy(PublicEvent event) {
        if (event instanceof PublicEvent.Say say && say.text() != null && !policy.get().sayText()) {
            return new PublicEvent.Say(say.agentId(), say.to(), null, say.length());
        }
        return event;
    }

    private void hold(PublicEvent event, int perSecond) {
        if (heldEvents.size() < perSecond) {
            heldEvents.addLast(event);
            return;
        }
        logEventRate();
    }

    private void drainHeld(long nowNanos) {
        int perSecond = ratePerSecond.getAsInt();
        if (perSecond < 1) return;
        while (!heldEvents.isEmpty()) sendEvent(heldEvents.removeFirst(), perSecond, nowNanos);
    }

    private void logEventRate() {
        if (loggedEventRate) return;
        skipped(MpReasons.RATE_LIMITED);
        loggedEventRate = true;
    }

    private void skipOnceNotMultiplayer() {
        if (loggedNotMultiplayer) return;
        skipped(MpReasons.NOT_MULTIPLAYER);
        loggedNotMultiplayer = true;
    }

    private void skipped(String reason) {
        MpLog.event(MpEvents.PUBLIC_STATE_SKIPPED,
            "player", player.get(), "studio", studio.get(), "plot", plot.getAsInt(), "rev", rev, "reason", reason);
    }

    private void prepareRate(int perSecond) {
        perSecond = Math.max(0, perSecond);
        if (rate != perSecond) {
            rate = perSecond;
            stateWindow.clear();
            eventWindow.clear();
            while (heldEvents.size() > perSecond) {
                heldEvents.removeLast();
                logEventRate();
            }
        }
    }

    public boolean isDirty() { return dirty; }
}
