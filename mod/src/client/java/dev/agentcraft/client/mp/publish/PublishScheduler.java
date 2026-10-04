package dev.agentcraft.client.mp.publish;

import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.RateBucket;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicJson;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Coalesces public state to the server rate and drops events past that rate.
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
    private @Nullable RateBucket stateBucket;
    private @Nullable RateBucket eventBucket;
    private int rate;
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
        stateBucket = null;
        eventBucket = null;
        rate = 0;
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
        loggedNotMultiplayer = false;
        int perSecond = ratePerSecond.getAsInt();
        if (perSecond < 1) {
            skipOnceNotMultiplayer();
            return;
        }
        if (!eventBucket(perSecond).tryTake(nowNanos)) {
            if (!loggedEventRate) {
                skipped(MpReasons.RATE_LIMITED);
                loggedEventRate = true;
            }
            return;
        }
        loggedEventRate = false;
        out.event(event);
    }

    public void flush(long nowNanos, boolean multiplayer) {
        if (!dirty) return;
        if (!multiplayer) {
            skipOnceNotMultiplayer();
            return;
        }
        loggedNotMultiplayer = false;
        ForemanState state = states.get();
        if (state == null) return;
        PublicStudioState projected = Redactor.redact(state, policy.get());
        if (projected.equals(last)) {
            dirty = false;
            if (!loggedUnchanged) {
                skipped(MpReasons.UNCHANGED);
                loggedUnchanged = true;
            }
            return;
        }
        loggedUnchanged = false;
        int perSecond = ratePerSecond.getAsInt();
        if (perSecond < 1) {
            skipOnceNotMultiplayer();
            return;
        }
        if (!stateBucket(perSecond).tryTake(nowNanos)) {
            if (!loggedStateRate) {
                skipped(MpReasons.RATE_LIMITED);
                loggedStateRate = true;
            }
            return;
        }
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

    private void skipOnceNotMultiplayer() {
        if (loggedNotMultiplayer) return;
        skipped(MpReasons.NOT_MULTIPLAYER);
        loggedNotMultiplayer = true;
    }

    private void skipped(String reason) {
        MpLog.event(MpEvents.PUBLIC_STATE_SKIPPED,
            "player", player.get(), "studio", studio.get(), "plot", plot.getAsInt(), "rev", rev, "reason", reason);
    }

    private void buckets(int perSecond) {
        if (stateBucket == null || rate != perSecond) {
            rate = perSecond;
            stateBucket = new RateBucket(perSecond);
            eventBucket = new RateBucket(perSecond);
        }
    }

    private RateBucket stateBucket(int perSecond) {
        buckets(perSecond);
        return stateBucket;
    }

    private RateBucket eventBucket(int perSecond) {
        buckets(perSecond);
        return eventBucket;
    }
}
