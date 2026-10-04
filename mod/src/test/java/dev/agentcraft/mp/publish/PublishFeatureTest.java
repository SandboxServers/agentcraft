package dev.agentcraft.mp.publish;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.mp.publish.PolicyStore;
import dev.agentcraft.client.mp.publish.PublishFeature;
import dev.agentcraft.client.mp.publish.PublishGate;
import dev.agentcraft.client.mp.publish.PublishScheduler;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.state.PublicPolicy;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishFeatureTest {
    @Test void singleplayer_does_not_send_even_when_the_channel_is_open() {
        assertEquals(PublishGate.Action.NONE, PublishGate.action(MpMode.SINGLEPLAYER, true));
        assertEquals(PublishGate.Action.NONE, PublishGate.action(MpMode.SINGLEPLAYER, false));
        assertEquals(PublishGate.Action.REFUSE, PublishGate.action(MpMode.REMOTE_VANILLA, true));
        assertEquals(PublishGate.Action.SEND, PublishGate.action(MpMode.MULTIPLAYER, true));
        assertEquals(PublishGate.Action.REFUSE, PublishGate.action(MpMode.MULTIPLAYER, false));
        assertEquals(PublishGate.QUIET, PublishGate.of(MpMode.SINGLEPLAYER));
    }

    @Test void policy_change_logs_the_caller_and_not_the_file(@TempDir Path dir) {
        Path file = dir.resolve("SENTINEL_CONFIG_PATH").resolve("agentcraft-public.json");
        PolicyStore store = PolicyStore.load(file);
        UUID player = UUID.nameUUIDFromBytes("policy-player".getBytes());
        UUID studio = UUID.nameUUIDFromBytes("policy-studio".getBytes());
        UUID forged = UUID.nameUUIDFromBytes("forged-player".getBytes());
        var sent = new java.util.ArrayList<dev.agentcraft.mp.state.PublicStudioState>();
        PublishScheduler scheduler = new PublishScheduler(dev.agentcraft.client.foreman.ForemanStates::showcase, store::current, () -> 4,
            () -> forged, () -> forged, () -> 9, new PublishScheduler.Out() {
                @Override public void state(dev.agentcraft.mp.state.PublicStudioState state) { sent.add(state); }
                @Override public void event(dev.agentcraft.mp.state.PublicEvent event) {}
            });
        try (var capture = MpLog.capture()) {
            String feedback = PublishFeature.applyPolicy(store, "activityText", true, player, studio, scheduler);
            assertEquals("activityText=true sayText=false taskTitles=false goalText=false", feedback);
            assertFalse(feedback.contains("SENTINEL_CONFIG_PATH"));
            assertEquals(1, capture.lines().size());
            String line = capture.lines().get(0);
            assertTrue(line.startsWith("event=" + MpEvents.POLICY_CHANGED));
            assertTrue(line.contains("player=" + player));
            assertTrue(line.contains("studio=" + studio));
            assertTrue(line.contains("before=0"));
            assertTrue(line.contains("after=1"));
            assertFalse(line.contains(forged.toString()));
            assertFalse(line.contains("SENTINEL_CONFIG_PATH"));
            assertEquals(feedback, PublishFeature.applyPolicy(store, "activityText", true, player, studio, scheduler));
            assertEquals(1, capture.lines().size());
            scheduler.flush(0, true);
            assertEquals(1, sent.size());
            assertTrue(sent.get(0).policy().activityText());
            assertFalse(capture.lines().toString().contains("SENTINEL_CONFIG_PATH"));
        }
    }

    @Test void command_has_no_player_argument_and_leaves_a_forged_id_unparsed(@TempDir Path dir) throws Exception {
        PolicyStore store = PolicyStore.load(dir.resolve("agentcraft-public.json"));
        PublishScheduler scheduler = new PublishScheduler(() -> null, () -> PublicPolicy.DEFAULT, () -> 4,
            () -> UUID.nameUUIDFromBytes("x".getBytes()), () -> UUID.nameUUIDFromBytes("y".getBytes()), () -> -1,
            new PublishScheduler.Out() {
                @Override public void state(dev.agentcraft.mp.state.PublicStudioState state) {}
                @Override public void event(dev.agentcraft.mp.state.PublicEvent event) {}
            });
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        PublishFeature.register(dispatcher, store, scheduler);
        CommandNode<FabricClientCommandSource> root = dispatcher.getRoot().getChild("agentcraft-public");
        assertNotNull(root);
        assertEquals(java.util.Set.of("activityText", "sayText", "taskTitles", "goalText"),
            root.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet()));
        assertNull(root.getChild("player"));
        for (String flag : java.util.List.of("activityText", "sayText", "taskTitles", "goalText")) {
            CommandNode<FabricClientCommandSource> node = root.getChild(flag);
            assertNotNull(node.getCommand());
            assertEquals(java.util.Set.of("value"), node.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet()));
            assertTrue(node.getChild("value").getChildren().isEmpty());
        }
        FabricClientCommandSource source = (FabricClientCommandSource) Proxy.newProxyInstance(
            FabricClientCommandSource.class.getClassLoader(), new Class<?>[] {FabricClientCommandSource.class},
            (proxy, method, args) -> { throw new AssertionError(method.getName()); });
        String forged = UUID.nameUUIDFromBytes("forged-player".getBytes()).toString();
        var parsed = dispatcher.parse("agentcraft-public sayText true " + forged, source);
        assertTrue(parsed.getReader().getRemaining().contains(forged));
        assertFalse(dispatcher.parse("agentcraft-public sayText true", source).getReader().canRead());
        assertFalse(dispatcher.parse("agentcraft-public", source).getReader().canRead());
    }

    @Test void publisher_source_attributes_the_player_and_does_not_write_the_studio() throws Exception {
        Path dir = Path.of(System.getProperty("agentcraft.src"), "client", "java", "dev", "agentcraft", "client", "mp", "publish");
        String feature = Files.readString(dir.resolve("PublishFeature.java"));
        String joined = Files.list(dir).filter(path -> path.toString().endsWith(".java")).map(path -> {
            try { return Files.readString(path); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }).collect(Collectors.joining("\n"));
        assertTrue(feature.contains("PublishGate.action"));
        assertTrue(feature.contains("getPlayer().getUUID()"));
        assertTrue(feature.contains("Anchors.self().owner()"));
        assertTrue(feature.contains("MpMode.current() != MpMode.MULTIPLAYER"));
        assertFalse(feature.contains("getArgument(\"player\""));
        assertFalse(feature.contains("getArgument(\"studio\""));
        assertFalse(joined.contains("updateState"));
        assertFalse(joined.contains("Studios.put"));
    }
}
