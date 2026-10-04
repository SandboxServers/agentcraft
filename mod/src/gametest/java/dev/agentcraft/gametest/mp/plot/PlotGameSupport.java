package dev.agentcraft.gametest.mp.plot;

import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.PlotDirectory;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.server.plot.PlotFeature;
import dev.agentcraft.mp.server.plot.PlotRegistry;
import dev.agentcraft.mp.server.plot.PlotStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelData;

/**
 * Installs a plot registry for one server-thread test and puts the shared world back.
 * The game-test server has already started, so this does the load a dedicated server does at start.
 */
final class PlotGameSupport {
    static final MpServerConfig ENABLED = new MpServerConfig(true, 128, true, false, true, true, true, 12, 4, 10);

    record Session(MpServerConfig config, PlotDirectory directory, LevelData.RespawnData spawn, byte[] plotsFile, boolean plotsDir) {}

    private PlotGameSupport() {}

    static Session open(GameTestHelper helper, MpServerConfig config) {
        MinecraftServer server = helper.getLevel().getServer();
        MpServerConfig previousConfig = MpServerConfig.install(config);
        PlotDirectory previousDirectory = Plots.directory();
        LevelData.RespawnData spawn = server.getWorldData().overworldData().getRespawnData();
        Path root = PlotFeature.worldRoot(server);
        Path file = PlotStore.plotsFile(root);
        Path plots = root.resolve("agentcraft").resolve("plots");
        byte[] bytes = null;
        try {
            if (Files.exists(file)) bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            MpServerConfig.install(previousConfig);
            throw new UncheckedIOException(e);
        }
        Plots.install(new PlotRegistry());
        return new Session(previousConfig, previousDirectory, spawn, bytes, Files.isDirectory(plots));
    }

    static void close(GameTestHelper helper, Session session) {
        MinecraftServer server = helper.getLevel().getServer();
        PlotFeature.resetPending();
        if (Plots.directory() instanceof PlotRegistry registry) {
            for (Plot plot : registry.all()) Anchors.remove(plot.owner());
        }
        Path root = PlotFeature.worldRoot(server);
        Path file = PlotStore.plotsFile(root);
        Path plots = root.resolve("agentcraft").resolve("plots");
        try {
            if (!session.plotsDir && Files.isDirectory(plots)) {
                try (var walk = Files.walk(plots)) {
                    for (Path path : walk.sorted((a, b) -> b.compareTo(a)).toList()) Files.deleteIfExists(path);
                }
            }
            if (session.plotsFile == null) Files.deleteIfExists(file);
            else {
                Files.createDirectories(file.getParent());
                Files.write(file, session.plotsFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Plots.install(session.directory);
        MpServerConfig.install(session.config);
        LevelData.RespawnData now = server.getWorldData().overworldData().getRespawnData();
        if (!now.equals(session.spawn)) server.getWorldData().overworldData().setSpawn(session.spawn);
    }

    static ServerPlayer mock(GameTestHelper helper) {
        return (ServerPlayer) helper.makeMockServerPlayer(GameType.CREATIVE);
    }

    static net.minecraft.commands.CommandSourceStack player(MinecraftServer server, ServerPlayer player, boolean op) {
        return server.createCommandSourceStack().withEntity(player).withSuppressedOutput()
            .withPermission(op ? PermissionSet.ALL_PERMISSIONS : PermissionSet.NO_PERMISSIONS);
    }
}
