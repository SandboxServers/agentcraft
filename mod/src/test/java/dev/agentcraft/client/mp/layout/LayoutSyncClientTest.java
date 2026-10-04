package dev.agentcraft.client.mp.layout;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.*;
import dev.agentcraft.mp.net.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LayoutSyncClientTest {
    @AfterEach
    void reset() {
        MpMode.disconnected();
        Studios.reset();
        Anchors.publish(Anchors.Layout.EMPTY);
    }

    private static void enterMultiplayer(UUID player) {
        StudioId own = StudioId.of(player);
        var hello = new HelloS2C(MpProtocol.VERSION, own, 1, new ServerInfo(128, 12, 4, 10));
        MpMode.joined(false, MpMode.hashServer("layout-sync.test:25604"));
        assertTrue(MpMode.receiveHello(hello, false, player));
    }

    private static LayoutS2C samplePayload(StudioId remote) {
        var layout = Anchors.builder("remote").bounds(-46, 60, -36, 46, 100, 54).put("desk", 0, 64, 0, 0, 0).build();
        layout = new Anchors.Layout(layout.name(), 7, layout.bounds(), layout.anchors());
        return new LayoutS2C(remote, 2, layout);
    }

    @Test
    void apply_publishes_server_revision_and_registers_plot() {
        UUID player = UUID.randomUUID();
        StudioId remote = StudioId.of(UUID.randomUUID());
        enterMultiplayer(player);
        var payload = samplePayload(remote);
        LayoutSyncClient.apply(payload);
        assertEquals(payload.layout(), Anchors.forStudio(remote));
        assertEquals(PlotGrid.originOf(2, 128), Studios.plot(remote).orElseThrow().origin());
    }

    @Test
    void layout_applied_telemetry_lists_studio_plot_rev_and_anchors() {
        UUID player = UUID.randomUUID();
        StudioId remote = StudioId.of(UUID.randomUUID());
        enterMultiplayer(player);
        var payload = samplePayload(remote);
        try (var capture = MpLog.capture()) {
            LayoutSyncClient.apply(payload);
            assertEquals(1, capture.lines().stream().filter(l -> l.contains(MpEvents.LAYOUT_APPLIED)).count());
            String applied = capture.lines().stream().filter(l -> l.contains(MpEvents.LAYOUT_APPLIED)).findFirst().orElseThrow();
            assertTrue(applied.contains("anchors=1"));
            assertTrue(applied.contains("rev=7"));
            assertTrue(applied.contains("plot=2"));
            assertTrue(applied.contains("studio=" + remote.owner()));
        }
    }

    @Test
    void remove_clears_layout_plot_view_and_logs_removed() {
        UUID player = UUID.randomUUID();
        StudioId remote = StudioId.of(UUID.randomUUID());
        enterMultiplayer(player);
        LayoutSyncClient.apply(samplePayload(remote));
        try (var capture = MpLog.capture()) {
            LayoutSyncClient.remove(new LayoutRemoveS2C(remote));
            assertTrue(Anchors.forStudio(remote).isEmpty());
            assertTrue(Studios.plot(remote).isEmpty());
            assertTrue(Studios.view(remote).isEmpty());
            assertEquals(1, capture.lines().stream().filter(l -> l.contains(MpEvents.LAYOUT_REMOVED)).count());
        }
    }

    @Test
    void apply_and_remove_noop_outside_multiplayer() {
        UUID player = UUID.randomUUID();
        StudioId remote = StudioId.of(UUID.randomUUID());
        MpMode.joined(false, MpMode.hashServer("layout-sync.test:25604"));
        assertEquals(MpMode.SINGLEPLAYER, MpMode.current());
        var layout = Anchors.builder("ghost").bounds(0, 60, 0, 1, 70, 1).put("x", 0, 64, 0, 0, 0).build();
        Anchors.publish(remote, layout);
        try (var capture = MpLog.capture()) {
            LayoutSyncClient.apply(samplePayload(remote));
            assertEquals(layout, Anchors.forStudio(remote));
            assertTrue(Studios.plot(remote).isEmpty());
            assertTrue(capture.lines().stream().noneMatch(l -> l.contains(MpEvents.LAYOUT_APPLIED)));
        }
        LayoutSyncClient.remove(new LayoutRemoveS2C(remote));
        assertEquals(layout, Anchors.forStudio(remote));
    }
}
