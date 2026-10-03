package dev.agentcraft.mp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoundationTest {
    @Test void spiral_round_trips_and_sites_do_not_overlap() {
        Set<BlockPos> seen=new HashSet<>(); List<Plot> plots=new ArrayList<>();
        for(int i=0;i<=200;i++) {
            BlockPos p=PlotGrid.originOf(i,128);
            assertTrue(seen.add(p)); assertEquals(0,p.getX()%16); assertEquals(0,p.getZ()%16); assertEquals(0,p.getY());
            assertEquals(i,PlotGrid.indexAt(p.getX(),p.getZ(),128).orElseThrow());
            Plot plot=new Plot(i,StudioId.LOCAL,p);
            for(Plot old:plots) assertFalse(old.box().intersects(plot.box()));
            plots.add(plot);
        }
        assertEquals(BlockPos.ZERO,PlotGrid.originOf(0,128));
        assertTrue(PlotGrid.indexAt(64,0,128).isEmpty());
        assertThrows(IllegalArgumentException.class,()->PlotGrid.originOf(-1,128));
        assertThrows(IllegalArgumentException.class,()->PlotGrid.originOf(1,127));
        for(int i:List.of(1000000,Integer.MAX_VALUE)) {
            BlockPos p=PlotGrid.originOf(i,128); assertEquals(i,PlotGrid.indexAt(p.getX(),p.getZ(),128).orElseThrow());
        }
    }
    @Test void local_directory_and_inclusive_site() {
        Plot p=Plots.directory().plotOf(StudioId.LOCAL).orElseThrow();
        assertTrue(p.contains(new BlockPos(46,100,54))); assertFalse(p.contains(new BlockPos(47,100,54)));
        assertEquals(List.of(p),Plots.directory().all());
    }
    @Test void config_defaults_and_invalid_values_warn(@TempDir Path dir) throws Exception {
        Path f=dir.resolve("server.json");
        try(var capture=MpLog.capture()) {
            assertEquals(MpServerConfig.DEFAULT,MpServerConfig.load(f));
            Files.writeString(f,"{\"enabled\":\"true\",\"plotStride\":127,\"autoBuild\":false,\"private\":\"secret text\"}");
            var c=MpServerConfig.load(f); assertFalse(c.enabled()); assertEquals(128,c.plotStride()); assertFalse(c.autoBuild());
            assertEquals(3,capture.lines().stream().filter(l->l.startsWith("event=config_invalid ")).count());
            assertTrue(capture.lines().stream().anyMatch(l->l.startsWith("event=config_loaded enabled=false file=")));
            assertEquals("warn",MpEvents.CATALOG.get(MpEvents.CONFIG_INVALID));
            assertFalse(capture.lines().toString().contains("secret"));
        }
    }
    @Test void catalog_covers_every_mp_event_in_the_sources() throws Exception {
        var pattern=Pattern.compile("MpLog\\.event\\(\\s*\"([^\"]+)\"");
        try(var paths=Files.walk(Path.of("src"))) {
            for(Path p:paths.filter(p->p.toString().endsWith(".java")).toList()) {
                var m=pattern.matcher(Files.readString(p));
                while(m.find()) assertTrue(MpEvents.CATALOG.containsKey(m.group(1)),p+":"+m.group(1));
            }
        }
    }
}
