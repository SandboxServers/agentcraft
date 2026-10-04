package dev.agentcraft.client.mp.publish;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.LinkStatus;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.net.PublicStateC2S;
import dev.agentcraft.mp.net.StudioEventC2S;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jspecify.annotations.Nullable;

/** Publishes the local studio. The acting player is always the client player, never a command argument. */
public final class PublishFeature {
    private static final List<String> FLAGS = List.of("activityText", "sayText", "taskTitles", "goalText");

    private PublishFeature() {}

    public static void init() {
        PolicyStore store = PolicyStore.load(FabricLoader.getInstance().getConfigDir().resolve("agentcraft-public.json"));
        PublishScheduler scheduler = new PublishScheduler(Foreman::state, store::current, PublishFeature::rate,
            PublishFeature::player, () -> Anchors.self().owner(), PublishFeature::plot, outbound());
        Foreman.addListener(listener(scheduler, PublishFeature::track, PublishFeature::open, store::current, Foreman::state));
        // Mode listeners already run on the client thread; execute() keeps a netty caller off the scheduler.
        MpMode.addListener(mode -> Minecraft.getInstance().execute(() -> {
            if (MpMode.current() == MpMode.MULTIPLAYER) scheduler.reconnect();
        }));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc, scheduler));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, build) -> register(dispatcher, store, scheduler));
    }

    public static ForemanListener listener(PublishScheduler scheduler, BooleanSupplier track, BooleanSupplier multiplayer,
            Supplier<PublicPolicy> policy, Supplier<ForemanState> states) {
        return new ForemanListener() {
            private void touch() { if (track.getAsBoolean()) scheduler.markDirty(); }

            @Override public void onSnapshot(ForemanState state) { touch(); }
            @Override public void onAgent(Protocol.@Nullable Agent previous, Protocol.Agent agent) { touch(); }
            @Override public void onDecision(Protocol.@Nullable Decision previous, Protocol.Decision decision) { touch(); }
            @Override public void onRepo(Protocol.@Nullable Repo previous, Protocol.Repo repo) { touch(); }
            @Override public void onGoal(Protocol.@Nullable Goal previous, Protocol.Goal goal) { touch(); }
            @Override public void onConnection(LinkStatus status) { touch(); }

            @Override public void onTask(Protocol.@Nullable Task previous, Protocol.Task task) {
                touch();
                if (!track.getAsBoolean()) return;
                ForemanState state = states.get();
                if (state == null) return;
                PublicEvent event = Redactor.taskDone(state, previous, task);
                if (event != null) scheduler.offer(event, System.nanoTime(), multiplayer.getAsBoolean());
            }

            @Override public void onSay(Protocol.AgentSay say) {
                if (!track.getAsBoolean()) return;
                ForemanState state = states.get();
                if (state == null) return;
                PublicEvent event = Redactor.say(state, say, policy.get());
                if (event != null) scheduler.offer(event, System.nanoTime(), multiplayer.getAsBoolean());
            }
        };
    }

    public static void tick(Minecraft mc, PublishScheduler scheduler) {
        if (mc.player == null || mc.getConnection() == null) return;
        switch (PublishGate.action(MpMode.current(), channels())) {
            case NONE -> { }
            case REFUSE -> scheduler.flush(System.nanoTime(), false);
            case SEND -> scheduler.flush(System.nanoTime(), true);
        }
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, PolicyStore store, PublishScheduler scheduler) {
        var root = ClientCommands.literal("agentcraft-public").executes(ctx -> {
            ctx.getSource().sendFeedback(Component.literal(describe(store.current())));
            return 1;
        });
        for (String flag : FLAGS) {
            root.then(ClientCommands.literal(flag)
                .executes(ctx -> apply(ctx.getSource(), store, scheduler, flag, null))
                .then(ClientCommands.argument("value", BoolArgumentType.bool())
                    .executes(ctx -> apply(ctx.getSource(), store, scheduler, flag, BoolArgumentType.getBool(ctx, "value")))));
        }
        dispatcher.register(root);
    }

    /** {@code value} null toggles. The player and studio are the caller's, never text from the command. */
    public static String applyPolicy(PolicyStore store, String flag, boolean value, UUID player, UUID studio, @Nullable PublishScheduler scheduler) {
        int before = store.bits();
        store.set(flag, value);
        int after = store.bits();
        if (before != after) {
            MpLog.event(MpEvents.POLICY_CHANGED, "player", player, "studio", studio, "before", before, "after", after);
            if (scheduler != null) scheduler.markDirty();
        }
        return describe(store.current());
    }

    public static String describe(PublicPolicy policy) {
        return "activityText=" + policy.activityText() + " sayText=" + policy.sayText()
            + " taskTitles=" + policy.taskTitles() + " goalText=" + policy.goalText();
    }

    private static int apply(FabricClientCommandSource source, PolicyStore store, PublishScheduler scheduler, String flag, @Nullable Boolean value) {
        boolean next = value != null ? value : !PolicyStore.flag(store.current(), flag);
        // The acting player is the command source's player. There is no player argument to forge.
        UUID player = source.getPlayer().getUUID();
        String text = applyPolicy(store, flag, next, player, Anchors.self().owner(), scheduler);
        source.sendFeedback(Component.literal(text));
        return 1;
    }

    private static boolean track() { return PublishGate.of(MpMode.current()) != PublishGate.QUIET; }

    private static boolean open() {
        if (PublishGate.of(MpMode.current()) != PublishGate.OPEN) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.getConnection() != null && channels();
    }

    private static boolean channels() {
        return ClientPlayNetworking.canSend(PublicStateC2S.TYPE) && ClientPlayNetworking.canSend(StudioEventC2S.TYPE);
    }

    private static int rate() { return MpMode.serverInfo().map(info -> info.publicStatePerSecond()).orElse(0); }

    private static UUID player() {
        var player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("no player");
        return player.getUUID();
    }

    private static int plot() { return Studios.plot(Anchors.self()).map(p -> p.index()).orElse(-1); }

    private static PublishScheduler.Out outbound() {
        return new PublishScheduler.Out() {
            @Override public void state(PublicStudioState state) { send(new PublicStateC2S(state)); }
            @Override public void event(PublicEvent event) { send(new StudioEventC2S(event)); }
        };
    }

    private static void send(CustomPacketPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        if (MpMode.current() != MpMode.MULTIPLAYER) return;
        if (!ClientPlayNetworking.canSend(payload.type())) return;
        ClientPlayNetworking.send(payload);
    }
}
