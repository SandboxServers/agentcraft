package dev.agentcraft.mp.server.plot;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.command.AgentCraftCommands;
import dev.agentcraft.hq.HqBuilders;
import dev.agentcraft.hq.HqFeature;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.PlotDirectory;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.server.StudioRange;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Loads the plot registry on a dedicated server with multiplayer enabled, allocates on play init
 * (before the join hello), and builds one queued plot per later tick.
 */
public final class PlotFeature {
    private record Pending(int index, int readyTick) {}

    private static final ArrayDeque<Pending> BUILDS = new ArrayDeque<>();
    private static final ArrayDeque<UUID> SPAWNS = new ArrayDeque<>();

    private PlotFeature() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(PlotFeature::onStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(PlotFeature::onStopped);
        // INIT runs from the play-listener constructor, before JOIN. The uuid is the connection's player.
        ServerPlayConnectionEvents.INIT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null) allocate(server, player.getUUID());
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!multiplayer(server)) return;
            ServerPlayer player = handler.getPlayer();
            if (player != null) SPAWNS.add(player.getUUID());
        });
        ServerTickEvents.END_SERVER_TICK.register(PlotFeature::onTick);
        AgentCraftCommands.sub(PlotCommands::register);
    }

    /** Dedicated and enabled, read at call time. Singleplayer never enters this. */
    public static boolean multiplayer(MinecraftServer server) {
        return multiplayer(server.isDedicatedServer(), MpServerConfig.current().enabled());
    }

    public static boolean multiplayer(boolean dedicated, boolean enabled) {
        return dedicated && enabled;
    }

    public static Path worldRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT);
    }

    /**
     * Makes the registry row for {@code playerId} when {@code autoAllocate} is on.
     * The build is queued, never run here.
     */
    public static boolean allocate(MinecraftServer server, UUID playerId) {
        if (!multiplayer(server) || playerId == null) return false;
        if (!MpServerConfig.current().autoAllocate()) return false;
        PlotRegistry registry = registry();
        if (registry == null) return false;
        var result = registry.allocateStored(worldRoot(server), StudioId.of(playerId), MpServerConfig.current().plotStride());
        if (!result.created() || result.plot() == null) return false;
        StudioRange.refresh(server);
        if (MpServerConfig.current().autoBuild() && Anchors.forStudio(result.plot().owner()).isEmpty()) {
            enqueueBuild(server, result.plot().index());
        }
        return true;
    }

    public static void enqueueBuild(MinecraftServer server, int index) {
        if (!multiplayer(server) || !MpServerConfig.current().autoBuild()) return;
        for (Pending pending : BUILDS) {
            if (pending.index == index) return;
        }
        // Two ticks out so this tick can finish the join hello before the build runs.
        BUILDS.add(new Pending(index, server.getTickCount() + 2));
    }

    public static boolean buildPending(int index) {
        for (Pending pending : BUILDS) {
            if (pending.index == index) return true;
        }
        return false;
    }

    public static void resetPending() {
        BUILDS.clear();
        SPAWNS.clear();
    }

    /** Publishes {@code layout} for {@code studio} and stores that plot's anchors. Does not move the world spawn. */
    public static Anchors.Layout publishBuilt(MinecraftServer server, StudioId studio, Anchors.Layout layout) {
        Anchors.Layout previous = Anchors.forStudio(studio);
        Anchors.Layout published = new Anchors.Layout(layout.name(), previous.revision() + 1, layout.bounds(), layout.anchors());
        Anchors.publish(studio, published);
        PlotRegistry registry = registry();
        if (registry != null) {
            Plot plot = registry.plotOf(studio).orElse(null);
            if (plot != null && !PlotStore.saveAnchors(worldRoot(server), plot.index(), published)) {
                AgentCraft.LOGGER.warn("Could not save anchors for plot {}", plot.index());
            }
        }
        return published;
    }

    private static void onStarted(MinecraftServer server) {
        if (!multiplayer(server)) return;
        PlotStore.Loaded loaded = PlotStore.load(worldRoot(server));
        PlotRegistry registry = new PlotRegistry();
        if (loaded.failed()) {
            registry.freeze();
            AgentCraft.LOGGER.warn("Could not read the plot registry; plots will not be saved over it");
        } else {
            registry.replaceAll(loaded.plots());
            for (Plot plot : registry.all()) {
                PlotStore.loadAnchors(worldRoot(server), plot.index()).ifPresent(layout -> Anchors.publish(plot.owner(), layout));
            }
        }
        Plots.install(registry);
    }

    private static void onStopped(MinecraftServer server) {
        resetPending();
        if (Plots.directory() instanceof PlotRegistry registry) {
            for (Plot plot : registry.all()) Anchors.remove(plot.owner());
        }
        Plots.install(Plots.SINGLEPLAYER);
    }

    private static void onTick(MinecraftServer server) {
        if (!multiplayer(server)) {
            resetPending();
            return;
        }
        if (!BUILDS.isEmpty() && server.getTickCount() >= BUILDS.peek().readyTick) {
            buildOne(server, BUILDS.remove().index());
            return;
        }
        if (SPAWNS.isEmpty()) return;
        placeSpawn(server, SPAWNS.remove());
    }

    private static void buildOne(MinecraftServer server, int index) {
        PlotRegistry registry = registry();
        if (registry == null) return;
        Plot plot = registry.byIndex(index);
        if (plot == null || !Anchors.forStudio(plot.owner()).isEmpty()) return;
        var builder = HqBuilders.get(HqBuilders.defaultId());
        if (builder == null) return;
        try {
            Anchors.Layout layout = HqFeature.buildPlot(server.overworld(), builder, false, plot.origin(), plot.owner());
            ServerPlayer owner = server.getPlayerList().getPlayer(plot.owner().owner());
            if (owner != null) PlotSpawn.applyRespawn(owner, layout);
        } catch (RuntimeException e) {
            AgentCraft.LOGGER.error("Plot {} failed to build", index, e);
        }
    }

    private static void placeSpawn(MinecraftServer server, UUID playerId) {
        PlotRegistry registry = registry();
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (registry == null || player == null) return;
        Plot plot = registry.plotOf(StudioId.of(playerId)).orElse(null);
        if (plot == null) return;
        Anchors.Layout layout = Anchors.forStudio(plot.owner());
        if (layout.isEmpty()) {
            if (buildPending(plot.index())) SPAWNS.addFirst(playerId);
            return;
        }
        PlotSpawn.applyRespawn(player, layout);
        PlotSpawn.teleportHome(player, layout);
    }

    private static PlotRegistry registry() {
        PlotDirectory directory = Plots.directory();
        return directory instanceof PlotRegistry registry ? registry : null;
    }
}
