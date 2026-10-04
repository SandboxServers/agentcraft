package dev.agentcraft.mp.server.layout;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LayoutSyncFeatureTest {
    private PlotDirectory previousDirectory;

    @AfterEach
    void reset() {
        LayoutSyncFeature.setDirtyTrackingActive(false);
        for (StudioId id : List.copyOf(Anchors.all().keySet())) {
            if (!id.equals(StudioId.LOCAL)) Anchors.remove(id);
        }
        if (previousDirectory != null) {
            Plots.install(previousDirectory);
            previousDirectory = null;
        }
    }

    @Test
    void enter_and_leave_rules_skip_local_and_empty_layouts() {
        Plot local = new Plot(0, StudioId.LOCAL, BlockPos.ZERO);
        StudioId remote = StudioId.of(UUID.randomUUID());
        Plot remotePlot = new Plot(2, remote, new BlockPos(128, 0, 0));
        assertFalse(LayoutSyncFeature.sendLayoutOnEnter(local));
        assertFalse(LayoutSyncFeature.sendRemoveOnLeave(local));
        assertFalse(LayoutSyncFeature.sendLayoutOnEnter(remotePlot));
        var layout = Anchors.builder("studio").bounds(-46, 60, -36, 46, 100, 54).put("desk_a", 0, 64, 0, 0, 0).build();
        Anchors.publish(remote, layout);
        assertTrue(LayoutSyncFeature.sendLayoutOnEnter(remotePlot));
        assertTrue(LayoutSyncFeature.sendRemoveOnLeave(remotePlot));
        Anchors.remove(remote);
        assertFalse(LayoutSyncFeature.sendLayoutOnEnter(remotePlot));
    }

    @Test
    void layout_sent_capture_uses_viewer_studio_plot_rev_and_anchor_count() {
        StudioId studio = StudioId.of(UUID.randomUUID());
        UUID viewer = UUID.randomUUID();
        var layout = Anchors.builder("hq").bounds(-1, 60, -1, 1, 70, 1).put("a", 0, 64, 0, 0, 0).put("b", 1, 64, 1, 0, 0).build();
        Plot plot = new Plot(3, studio, new BlockPos(256, 0, 0));
        try (var capture = MpLog.capture()) {
            LayoutSyncFeature.logLayoutSent(viewer, plot, layout);
            assertEquals(1, capture.lines().size());
            String line = capture.lines().getFirst();
            assertTrue(line.contains("event=" + MpEvents.LAYOUT_SENT));
            assertTrue(line.contains("to=" + viewer));
            assertTrue(line.contains("studio=" + studio.owner()));
            assertTrue(line.contains("plot=3"));
            assertTrue(line.contains("rev=" + layout.revision()));
            assertTrue(line.contains("anchors=2"));
        }
    }

    @Test
    void republish_action_follows_anchor_and_plot_registries() {
        StudioId remote = StudioId.of(UUID.randomUUID());
        StudioId local = StudioId.LOCAL;
        var layout = Anchors.builder("studio").bounds(0, 60, 0, 1, 70, 1).put("a", 0, 64, 0, 0, 0).build();
        Plot remotePlot = new Plot(1, remote, new BlockPos(128, 0, 0));
        PlotDirectory fake = new PlotDirectory() {
            public Optional<Plot> plotOf(StudioId id) {
                if (id.equals(remote)) return Optional.of(remotePlot);
                return Optional.empty();
            }

            public Optional<Plot> plotAt(BlockPos pos) {
                return Optional.empty();
            }

            public Collection<Plot> all() {
                return List.of(remotePlot);
            }
        };
        previousDirectory = Plots.directory();
        Plots.install(fake);
        assertEquals(LayoutSyncFeature.RepublishAction.NOTHING, LayoutSyncFeature.republishAction(local, fake));
        Anchors.publish(remote, layout);
        assertEquals(LayoutSyncFeature.RepublishAction.SEND_LAYOUT, LayoutSyncFeature.republishAction(remote, fake));
        StudioId noPlot = StudioId.of(UUID.randomUUID());
        Anchors.publish(noPlot, layout);
        assertEquals(LayoutSyncFeature.RepublishAction.NOTHING, LayoutSyncFeature.republishAction(noPlot, fake));
        Anchors.remove(remote);
        assertEquals(LayoutSyncFeature.RepublishAction.SEND_REMOVAL, LayoutSyncFeature.republishAction(remote, fake));
    }

    @Test
    void dirty_set_records_only_while_active_and_dedupes_studios() {
        StudioId remote = StudioId.of(UUID.randomUUID());
        LayoutSyncFeature.setDirtyTrackingActive(false);
        LayoutSyncFeature.onAnchorsStudioChanged(remote);
        assertEquals(0, LayoutSyncFeature.dirtyCount());
        LayoutSyncFeature.onAnchorsStudioChanged(StudioId.LOCAL);
        assertEquals(0, LayoutSyncFeature.dirtyCount());
        LayoutSyncFeature.setDirtyTrackingActive(true);
        LayoutSyncFeature.onAnchorsStudioChanged(remote);
        LayoutSyncFeature.onAnchorsStudioChanged(remote);
        assertEquals(1, LayoutSyncFeature.dirtyCount());
        assertEquals(Set.of(remote), LayoutSyncFeature.takeDirtyBatch());
        assertEquals(0, LayoutSyncFeature.dirtyCount());
        assertTrue(LayoutSyncFeature.takeDirtyBatch().isEmpty());
    }
}
