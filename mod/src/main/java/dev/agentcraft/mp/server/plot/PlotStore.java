package dev.agentcraft.mp.server.plot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.StudioId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * World-folder persistence for the plot registry. {@code agentcraft/plots.json} is the source of
 * truth (uuids, indexes, origins). Each plot's layout is {@code agentcraft/plots/<index>/anchors.json}.
 * An index becomes part of a path only after it is a non-negative int.
 */
public final class PlotStore {
    static final int MAX_BYTES = 1_048_576;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private PlotStore() {}

    public record Loaded(List<Plot> plots, boolean failed, int skipped) {
        public Loaded {
            plots = List.copyOf(plots);
        }
    }

    public static Path plotsFile(Path worldRoot) {
        return worldRoot.resolve("agentcraft").resolve("plots.json");
    }

    /** The plot directory, or empty when {@code index} must not be used as a path. */
    public static Optional<Path> plotDirectory(Path worldRoot, int index) {
        if (index < 0) return Optional.empty();
        Path base = worldRoot.resolve("agentcraft").resolve("plots").toAbsolutePath().normalize();
        Path dir = base.resolve(Integer.toString(index)).normalize();
        if (!dir.startsWith(base)) return Optional.empty();
        return Optional.of(dir);
    }

    public static boolean save(Path worldRoot, Collection<Plot> plots) {
        Path file = plotsFile(worldRoot);
        Path tmp = file.resolveSibling("plots.json.tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, GSON.toJson(toJson(plots)), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            deleteQuietly(tmp);
            return false;
        }
    }

    public static Loaded load(Path worldRoot) {
        Path file = plotsFile(worldRoot);
        if (!Files.exists(file)) return new Loaded(List.of(), false, 0);
        try {
            if (Files.size(file) > MAX_BYTES) return new Loaded(List.of(), true, 0);
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return new Loaded(List.of(), true, 0);
            JsonElement plots = root.getAsJsonObject().get("plots");
            if (plots == null || !plots.isJsonArray()) return new Loaded(List.of(), true, 0);
            List<Plot> loaded = new ArrayList<>();
            Set<Integer> indexes = new HashSet<>();
            Set<UUID> owners = new HashSet<>();
            int skipped = 0;
            for (JsonElement element : plots.getAsJsonArray()) {
                Plot plot = plotFrom(element);
                // Both sets are consulted before either is touched: a rejected row must not reserve
                // its index or owner and reject the valid rows that follow it.
                if (plot == null || indexes.contains(plot.index()) || owners.contains(plot.owner().owner())) {
                    skipped++;
                    continue;
                }
                indexes.add(plot.index());
                owners.add(plot.owner().owner());
                loaded.add(plot);
            }
            if (skipped > 0) AgentCraft.LOGGER.warn("Skipped {} invalid plot records", skipped);
            return new Loaded(loaded, false, skipped);
        } catch (IOException | RuntimeException e) {
            return new Loaded(List.of(), true, 0);
        }
    }

    public static boolean saveAnchors(Path worldRoot, int index, Anchors.Layout layout) {
        Optional<Path> dir = plotDirectory(worldRoot, index);
        if (dir.isEmpty()) return false;
        Path file = dir.get().resolve("anchors.json");
        Path tmp = dir.get().resolve("anchors.json.tmp");
        try {
            Files.createDirectories(dir.get());
            Files.writeString(tmp, GSON.toJson(Anchors.toJson(layout)), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            deleteQuietly(tmp);
            return false;
        }
    }

    public static Optional<Anchors.Layout> loadAnchors(Path worldRoot, int index) {
        Optional<Path> dir = plotDirectory(worldRoot, index);
        if (dir.isEmpty()) return Optional.empty();
        Path file = dir.get().resolve("anchors.json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            if (Files.size(file) > MAX_BYTES) return Optional.empty();
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return Optional.empty();
            return Optional.of(Anchors.fromJson(root.getAsJsonObject()));
        } catch (IOException | RuntimeException e) {
            AgentCraft.LOGGER.warn("Could not read anchors for plot {}", index);
            return Optional.empty();
        }
    }

    /** Deletes {@code agentcraft/plots/<index>} only. The registry file and the blocks stay. */
    public static boolean deletePlotDirectory(Path worldRoot, int index) {
        Optional<Path> dir = plotDirectory(worldRoot, index);
        if (dir.isEmpty()) return false;
        if (!Files.exists(dir.get())) return true;
        try (var walk = Files.walk(dir.get())) {
            for (Path path : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException | UncheckedIOException e) {
            // Files.walk is lazy: an unreadable child directory surfaces here as an UncheckedIOException,
            // which must count as a deletion failure so the caller rolls back instead of throwing past it.
            return false;
        }
    }

    private static JsonObject toJson(Collection<Plot> plots) {
        JsonArray array = new JsonArray();
        for (Plot plot : plots) {
            JsonObject o = new JsonObject();
            o.addProperty("index", plot.index());
            o.addProperty("owner", plot.owner().owner().toString());
            o.addProperty("x", plot.origin().getX());
            o.addProperty("y", plot.origin().getY());
            o.addProperty("z", plot.origin().getZ());
            array.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("plots", array);
        return root;
    }

    private static Plot plotFrom(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject o = element.getAsJsonObject();
        OptionalInt index = integer(o.get("index"));
        OptionalInt x = integer(o.get("x"));
        OptionalInt y = integer(o.get("y"));
        OptionalInt z = integer(o.get("z"));
        if (index.isEmpty() || x.isEmpty() || y.isEmpty() || z.isEmpty()) return null;
        if (index.getAsInt() < 0 || y.getAsInt() != 0) return null;
        // Compare the signed values directly: Math.abs(Integer.MIN_VALUE) is negative, so it would
        // pass an absolute-value bound check and then pass the % 16 test.
        if (x.getAsInt() < -30_000_000 || x.getAsInt() > 30_000_000
            || z.getAsInt() < -30_000_000 || z.getAsInt() > 30_000_000) return null;
        if (x.getAsInt() % 16 != 0 || z.getAsInt() % 16 != 0) return null;
        JsonElement owner = o.get("owner");
        if (owner == null || !owner.isJsonPrimitive() || !owner.getAsJsonPrimitive().isString()) return null;
        UUID uuid;
        try {
            uuid = UUID.fromString(owner.getAsString());
        } catch (IllegalArgumentException e) {
            return null;
        }
        StudioId studio = StudioId.of(uuid);
        if (studio.equals(StudioId.LOCAL)) return null;
        return new Plot(index.getAsInt(), studio, new BlockPos(x.getAsInt(), y.getAsInt(), z.getAsInt()));
    }

    private static OptionalInt integer(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return OptionalInt.empty();
        try {
            BigDecimal value = element.getAsBigDecimal();
            return OptionalInt.of(value.intValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
