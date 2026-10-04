package dev.agentcraft.gametest.mp.plot;

import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.PlotGrid;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.net.HelloS2C;
import dev.agentcraft.mp.net.MpPayloads;
import dev.agentcraft.mp.server.plot.PlotCommands;
import dev.agentcraft.mp.server.plot.PlotFeature;
import dev.agentcraft.mp.server.plot.PlotRegistry;
import dev.agentcraft.mp.server.plot.PlotStore;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelData;

public final class PlotLifecycleGameTest {
    @GameTest
    public void allocatesLowestFreeIndexAndSkipsASecondJoin(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            try (var capture = MpLog.capture()) {
                helper.assertTrue(PlotFeature.allocate(server, first), "first player is allocated");
                helper.assertTrue(PlotFeature.allocate(server, second), "second player is allocated");
                helper.assertTrue(!PlotFeature.allocate(server, first), "the same player is not allocated again");
                long allocated = capture.lines().stream().filter(line -> line.startsWith("event=plot_allocated ")).count();
                helper.assertValueEqual(allocated, 2L, "plot_allocated lines");
            }
            PlotRegistry registry = (PlotRegistry) Plots.directory();
            var plot0 = registry.plotOf(StudioId.of(first)).orElseThrow();
            var plot1 = registry.plotOf(StudioId.of(second)).orElseThrow();
            helper.assertValueEqual(plot0.index(), 0, "first plot index");
            helper.assertValueEqual(plot0.origin(), BlockPos.ZERO, "first plot origin");
            helper.assertValueEqual(plot1.index(), 1, "second plot index");
            helper.assertValueEqual(plot1.origin(), PlotGrid.originOf(1, 128), "second plot origin");
            helper.assertTrue(registry.plotAt(new BlockPos(0, 64, 0)).isPresent(), "the site resolves to a plot");
            helper.assertTrue(registry.plotAt(new BlockPos(64, 64, 0)).isEmpty(), "the road between sites is not a plot");
            HelloS2C hello = MpPayloads.helloFor(first, MpServerConfig.current(), true, true).orElseThrow();
            helper.assertValueEqual(hello.plotIndex(), 0, "hello plot index");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void autoAllocateOffDoesNotCreateAPlot(GameTestHelper helper) {
        var config = new MpServerConfig(true, 128, false, false, true, true, true, 12, 4, 10);
        var session = PlotGameSupport.open(helper, config);
        try {
            UUID player = UUID.randomUUID();
            try (var capture = MpLog.capture()) {
                helper.assertTrue(!PlotFeature.allocate(helper.getLevel().getServer(), player), "autoAllocate is off");
                helper.assertTrue(capture.lines().stream().noneMatch(line -> line.startsWith("event=plot_")), "no plot telemetry");
            }
            helper.assertTrue(Plots.directory().plotOf(StudioId.of(player)).isEmpty(), "no plot was stored");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void disabledGateLogsNothing(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer player = PlotGameSupport.mock(helper);
            helper.assertTrue(PlotFeature.allocate(server, player.getUUID()), "the player has a plot while enabled");
            MpServerConfig.install(MpServerConfig.DEFAULT);
            try (var capture = MpLog.capture()) {
                helper.assertTrue(!PlotFeature.allocate(server, UUID.randomUUID()), "a disabled server does not allocate");
                helper.assertValueEqual(PlotCommands.info(PlotGameSupport.player(server, player, false)), 0, "info while disabled");
                helper.assertTrue(capture.lines().stream().noneMatch(line -> line.startsWith("event=plot_")), "a disabled server logs no plot events");
            }
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void saveThenLoadKeepsOwnerOriginAndSpawn(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            UUID owner = UUID.randomUUID();
            helper.assertTrue(PlotFeature.allocate(server, owner), "allocate persists a row");
            Anchor spawn = new Anchor(AnchorNames.SPAWN, 1.5, 70, 3.5, 90, 0);
            Anchors.Layout layout = new Anchors.Layout("studio", 4, null, java.util.Map.of(AnchorNames.SPAWN, spawn));
            helper.assertTrue(PlotStore.saveAnchors(PlotFeature.worldRoot(server), 0, layout), "anchors are saved");

            PlotRegistry loaded = new PlotRegistry();
            var rows = PlotStore.load(PlotFeature.worldRoot(server));
            helper.assertTrue(!rows.failed(), "plots.json loads");
            loaded.replaceAll(rows.plots());
            var plot = loaded.plotOf(StudioId.of(owner)).orElseThrow();
            helper.assertValueEqual(plot.index(), 0, "loaded index");
            helper.assertValueEqual(plot.origin(), BlockPos.ZERO, "loaded origin");
            Anchor back = PlotStore.loadAnchors(PlotFeature.worldRoot(server), 0).orElseThrow().get(AnchorNames.SPAWN);
            helper.assertValueEqual(back.x(), spawn.x(), "loaded spawn x");
            helper.assertValueEqual(back.y(), spawn.y(), "loaded spawn y");
            helper.assertValueEqual(back.z(), spawn.z(), "loaded spawn z");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void queuedBuildDoesNotRunInsideAllocate(GameTestHelper helper) {
        var config = new MpServerConfig(true, 128, true, true, true, true, true, 12, 4, 10);
        var session = PlotGameSupport.open(helper, config);
        try {
            UUID owner = UUID.randomUUID();
            helper.assertTrue(PlotFeature.allocate(helper.getLevel().getServer(), owner), "allocate queues a build");
            helper.assertTrue(PlotFeature.buildPending(0), "the build is still queued");
            helper.assertTrue(Anchors.forStudio(StudioId.of(owner)).isEmpty(), "allocate did not build");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest(maxTicks = 200)
    public void ownerRebuildSetsRespawnAndLeavesWorldSpawn(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer player = PlotGameSupport.mock(helper);
            helper.assertTrue(PlotFeature.allocate(server, player.getUUID()), "the owner has plot 0");
            long localRevision = Anchors.forStudio(StudioId.LOCAL).revision();
            LevelData.RespawnData worldSpawn = server.getWorldData().overworldData().getRespawnData();
            int result = PlotCommands.rebuild(PlotGameSupport.player(server, player, false), 0, false, false);
            helper.assertValueEqual(result, 69, "owner rebuild anchor count");
            Anchors.Layout layout = Anchors.forStudio(StudioId.of(player.getUUID()));
            helper.assertValueEqual(layout.anchors().size(), 69, "the owner's studio, not only LOCAL");
            helper.assertValueEqual(Anchors.forStudio(StudioId.LOCAL).revision(), localRevision, "LOCAL revision is unchanged");
            helper.assertValueEqual(server.getWorldData().overworldData().getRespawnData(), worldSpawn, "world spawn is unchanged");
            Anchor spawn = layout.get(AnchorNames.SPAWN);
            if (spawn == null) helper.fail("rebuild did not publish spawn");
            var respawn = player.getRespawnConfig();
            if (respawn == null) helper.fail("the owner has no respawn");
            helper.assertTrue(respawn.forced(), "plot respawn is forced");
            helper.assertValueEqual(respawn.respawnData().pos(), spawn.blockPos(), "respawn block");
            dev.agentcraft.mp.server.plot.PlotSpawn.teleportHome(player, layout);
            helper.assertValueEqual(player.getX(), spawn.x(), "teleport x");
            helper.assertValueEqual(player.getY(), spawn.y(), "teleport y");
            helper.assertValueEqual(player.getZ(), spawn.z(), "teleport z");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }
}
