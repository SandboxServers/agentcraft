package dev.agentcraft.gametest.mp.plot;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.server.plot.PlotCommands;
import dev.agentcraft.mp.server.plot.PlotFeature;
import dev.agentcraft.mp.server.plot.PlotRegistry;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

public final class PlotCommandGameTest {
    @GameTest
    public void commandNodesFollowTheActor(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer player = PlotGameSupport.mock(helper);
            CommandSourceStack nonOp = PlotGameSupport.player(server, player, false);
            CommandSourceStack op = PlotGameSupport.player(server, player, true);
            var dispatcher = server.getCommands().getDispatcher();
            helper.assertTrue(dispatcher.findNode(List.of("agentcraft")).canUse(nonOp), "the root is open");
            helper.assertTrue(dispatcher.findNode(List.of("agentcraft", "plot", "info")).canUse(nonOp), "info is open");
            helper.assertTrue(dispatcher.findNode(List.of("agentcraft", "plot", "home")).canUse(nonOp), "home is open");
            helper.assertTrue(!dispatcher.findNode(List.of("agentcraft", "hq")).canUse(nonOp), "hq stays an operator command");
            helper.assertTrue(!dispatcher.findNode(List.of("agentcraft", "anchors")).canUse(nonOp), "anchors stays an operator command");
            helper.assertTrue(!dispatcher.findNode(List.of("agentcraft", "plot", "list")).canUse(nonOp), "list is an operator command");
            helper.assertTrue(!dispatcher.findNode(List.of("agentcraft", "plot", "assign")).canUse(nonOp), "assign is an operator command");
            helper.assertTrue(!dispatcher.findNode(List.of("agentcraft", "plot", "free")).canUse(nonOp), "free is an operator command");
            helper.assertTrue(!dispatcher.findNode(List.of("agentcraft", "plot", "rebuild", "index")).canUse(nonOp), "rebuild by index is an operator command");
            helper.assertTrue(dispatcher.findNode(List.of("agentcraft", "plot", "list")).canUse(op), "an operator can list");
            helper.assertTrue(run(dispatcher, "agentcraft plot list", nonOp) == 0, "a non-op dispatcher cannot list");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void infoAndHomeUseTheSourcePlayer(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer a = PlotGameSupport.mock(helper);
            ServerPlayer b = PlotGameSupport.mock(helper);
            helper.assertTrue(PlotFeature.allocate(server, a.getUUID()), "A is allocated");
            helper.assertTrue(PlotFeature.allocate(server, b.getUUID()), "B is allocated");
            CommandSourceStack sourceA = PlotGameSupport.player(server, a, false);
            CommandSourceStack sourceB = PlotGameSupport.player(server, b, false);
            try (var capture = MpLog.capture()) {
                helper.assertValueEqual(PlotCommands.info(sourceA), 1, "A's info is A's plot");
                helper.assertValueEqual(PlotCommands.info(sourceB), 2, "B's info is B's plot");
                String line = capture.lines().get(0);
                helper.assertTrue(line.contains("command=info") && line.contains("ok=true"), "info telemetry");
                helper.assertTrue(!line.contains("test-mock-player"), "info does not log the player name");
            }
            publishSpawn(StudioId.of(a.getUUID()), 10.5, 80, 10.5);
            publishSpawn(StudioId.of(b.getUUID()), 30.5, 80, 30.5);
            a.absSnapTo(0, 64, 0, 0, 0);
            b.absSnapTo(0, 64, 0, 0, 0);
            helper.assertValueEqual(PlotCommands.home(sourceA), 1, "A can go home");
            helper.assertValueEqual(a.getX(), 10.5, "A moved home");
            helper.assertValueEqual(b.getX(), 0.0, "B stayed put");
            helper.assertValueEqual(PlotCommands.home(server.createCommandSourceStack().withSuppressedOutput()), 0, "the console has no plot");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest(maxTicks = 200)
    public void nonOpMutationsAreRefused(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer owner = PlotGameSupport.mock(helper);
            helper.assertTrue(PlotFeature.allocate(server, owner.getUUID()), "the owner has a plot");
            StudioId studio = StudioId.of(owner.getUUID());
            publishSpawn(studio, 4.5, 70, 4.5);
            long revision = Anchors.forStudio(studio).revision();
            CommandSourceStack nonOp = PlotGameSupport.player(server, owner, false);
            UUID other = UUID.randomUUID();
            server.services().nameToIdCache().add(new NameAndId(other, "Other"));
            PlotRegistry registry = (PlotRegistry) Plots.directory();
            try (var capture = MpLog.capture()) {
                helper.assertValueEqual(PlotCommands.list(nonOp), 0, "a non-op cannot list");
                helper.assertValueEqual(PlotCommands.assign(nonOp, "Other", 1), 0, "a non-op cannot assign");
                helper.assertValueEqual(PlotCommands.free(nonOp, 0), 0, "a non-op cannot free");
                helper.assertValueEqual(PlotCommands.rebuild(nonOp, 0, true, false), 0, "a non-op cannot rebuild by index");
                helper.assertTrue(registry.byIndex(0) != null, "the plot is still there");
                helper.assertTrue(registry.plotOf(StudioId.of(other)).isEmpty(), "assign did not create a plot");
                helper.assertValueEqual(Anchors.forStudio(studio).revision(), revision, "rebuild by index did not build");
                helper.assertTrue(capture.lines().stream().anyMatch(line ->
                    line.contains("command=assign") && line.contains("ok=false") && !line.contains("Other")), "assign refusal telemetry");
            }
            long local = Anchors.forStudio(StudioId.LOCAL).revision();
            helper.assertTrue(run(server.getCommands().getDispatcher(), "agentcraft hq", nonOp) == 0, "a non-op cannot run hq");
            helper.assertValueEqual(Anchors.forStudio(studio).revision(), revision, "hq did not build the owner's plot");
            helper.assertValueEqual(Anchors.forStudio(StudioId.LOCAL).revision(), local, "hq did not build LOCAL");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void assignUsesTheCachedUuidNotAnOfflineProfile(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer op = PlotGameSupport.mock(helper);
            CommandSourceStack source = PlotGameSupport.player(server, op, true);
            UUID real = UUID.randomUUID();
            server.services().nameToIdCache().add(new NameAndId(real, "CachedOwner"));
            helper.assertValueEqual(PlotCommands.assign(source, "cachedowner", 3), 1, "a cached name assigns");
            PlotRegistry registry = (PlotRegistry) Plots.directory();
            helper.assertValueEqual(registry.byIndex(3).owner(), StudioId.of(real), "the stored uuid is the cached one");
            UUID offline = NameAndId.createOffline("cachedowner").id();
            helper.assertTrue(!registry.byIndex(3).owner().owner().equals(offline), "the offline uuid was not invented");
            int before = registry.all().size();
            helper.assertValueEqual(PlotCommands.assign(source, "NotAPlayer", 4), 0, "an unknown name is refused");
            helper.assertValueEqual(PlotCommands.assign(source, "../../x", 4), 0, "a path-like name is refused");
            helper.assertValueEqual(registry.all().size(), before, "unknown names create no plot");
            helper.assertTrue(registry.plotOf(StudioId.of(NameAndId.createOffline("NotAPlayer").id())).isEmpty(), "no offline plot");
            helper.assertTrue(registry.plotOf(StudioId.of(NameAndId.createOffline("../../x").id())).isEmpty(), "no path plot");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void assignRefusesATakenIndexAndAnOwnerWhoAlreadyHasAPlot(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            UUID seated = UUID.randomUUID();
            helper.assertTrue(PlotFeature.allocate(server, seated), "a player already owns plot 0");
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            UUID cached = UUID.randomUUID();
            server.services().nameToIdCache().add(new NameAndId(cached, "Newcomer"));
            helper.assertValueEqual(PlotCommands.assign(source, "Newcomer", 0), 0, "plot 0 is taken");
            server.services().nameToIdCache().add(new NameAndId(seated, "Seated"));
            helper.assertValueEqual(PlotCommands.assign(source, "Seated", 1), 0, "that player already has a plot");
            PlotRegistry registry = (PlotRegistry) Plots.directory();
            helper.assertTrue(registry.byIndex(1) == null, "index 1 was not filled");
            helper.assertValueEqual(registry.plotOf(StudioId.of(seated)).orElseThrow().index(), 0, "the owner kept plot 0");
            helper.assertTrue(registry.plotOf(StudioId.of(cached)).isEmpty(), "the newcomer got nothing");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void freeDropsTheRowAndTheDirectory(GameTestHelper helper) throws Exception {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            UUID owner = UUID.randomUUID();
            helper.assertTrue(PlotFeature.allocate(server, owner), "a plot exists to free");
            var root = PlotFeature.worldRoot(server);
            var dir = dev.agentcraft.mp.server.plot.PlotStore.plotDirectory(root, 0).orElseThrow();
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("anchors.json"), "{}");
            var keep = root.resolve("agentcraft").resolve("plots").resolve("keep.txt");
            java.nio.file.Files.writeString(keep, "stay");
            CommandSourceStack console = server.createCommandSourceStack().withSuppressedOutput();
            helper.assertValueEqual(PlotCommands.free(console, 9), 0, "an unknown index is refused");
            helper.assertTrue(((PlotRegistry) Plots.directory()).byIndex(0) != null, "the real plot survived");
            try (var capture = MpLog.capture()) {
                helper.assertValueEqual(PlotCommands.free(console, 0), 1, "an operator can free");
                helper.assertTrue(Plots.directory().plotOf(StudioId.of(owner)).isEmpty(), "the row is gone");
                helper.assertTrue(!java.nio.file.Files.exists(dir), "the plot directory is gone");
                helper.assertTrue(java.nio.file.Files.exists(keep), "a sibling file stays");
                helper.assertTrue(java.nio.file.Files.exists(dev.agentcraft.mp.server.plot.PlotStore.plotsFile(root)), "plots.json stays");
                helper.assertTrue(PlotFeature.allocate(server, owner), "the freed owner can be allocated again");
                helper.assertTrue(capture.lines().stream().anyMatch(line -> line.startsWith("event=plot_allocated ")), "the new row is logged");
            }
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest(maxTicks = 200)
    public void hqBuildsTheCallersPlot(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer player = PlotGameSupport.mock(helper);
            helper.assertTrue(PlotFeature.allocate(server, player.getUUID()), "the caller owns plot 0");
            long localRevision = Anchors.forStudio(StudioId.LOCAL).revision();
            var worldSpawn = server.getWorldData().overworldData().getRespawnData();
            CommandSourceStack source = server.createCommandSourceStack().withEntity(player).withSuppressedOutput();
            int result;
            try {
                result = server.getCommands().getDispatcher().execute("agentcraft hq", source);
            } catch (CommandSyntaxException e) {
                helper.fail("the owner could not run hq: " + e.getMessage());
                return;
            }
            helper.assertValueEqual(result, 69, "/agentcraft hq result");
            helper.assertValueEqual(Anchors.forStudio(StudioId.of(player.getUUID())).anchors().size(), 69, "hq published the caller's studio");
            helper.assertValueEqual(Anchors.forStudio(StudioId.LOCAL).revision(), localRevision, "hq did not publish LOCAL");
            helper.assertValueEqual(server.getWorldData().overworldData().getRespawnData(), worldSpawn, "hq did not move the world spawn");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    private static void publishSpawn(StudioId studio, double x, double y, double z) {
        Anchors.publish(studio, new Anchors.Layout("studio", 5, null, java.util.Map.of(AnchorNames.SPAWN, new Anchor(AnchorNames.SPAWN, x, y, z, 0, 0))));
    }

    private static int run(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher, String command, CommandSourceStack source) {
        try {
            return dispatcher.execute(command, source);
        } catch (CommandSyntaxException e) {
            return 0;
        }
    }
}
