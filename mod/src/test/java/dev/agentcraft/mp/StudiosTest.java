package dev.agentcraft.mp;

import static org.junit.jupiter.api.Assertions.*;
import dev.agentcraft.client.mp.*;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.net.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StudiosTest {
    @AfterEach void reset() { MpMode.disconnected(); Anchors.publish(Anchors.Layout.EMPTY); }
    @Test void slots_boundaries_layout_fallback_overlay_and_listeners() {
        Studios.reset();
        var layout=Anchors.builder("local").bounds(-46,60,-36,46,100,54).build(); Anchors.publish(layout);
        StudioId remote=StudioId.of(UUID.randomUUID()), later=StudioId.of(UUID.randomUUID());
        List<StudioView> notices=new ArrayList<>(); Studios.addListener((id,view)-> { if(id.equals(remote)) notices.add(view); });
        Studios.put(new StudioView(remote,false,"Bob",true,layout,CodecTest.state(),999));
        Studios.setPlot(remote,1,128);
        assertEquals(-10000,Studios.entityIdBase(StudioId.LOCAL)); assertEquals(-11000,Studios.entityIdBase(remote));
        assertEquals(remote,Studios.at(new BlockPos(128,66,0)).orElseThrow().id());
        assertTrue(Studios.at(new BlockPos(0,66,0)).orElseThrow().own());
        Studios.setOverlay(remote); assertFalse(Studios.at(new BlockPos(0,66,0)).orElseThrow().own());
        Studios.updateState(remote,CodecTest.state()); assertEquals(2,notices.size());
        Studios.remove(remote); assertTrue(Studios.at(new BlockPos(0,66,0)).orElseThrow().own());
        assertEquals(-12000,Studios.entityIdBase(later)); assertEquals(-11000,Studios.entityIdBase(remote));
        var far=Anchors.builder("far").bounds(1000,60,1000,1010,100,1010).build();
        Studios.put(new StudioView(later,false,"Ann",false,far,null,2));
        assertEquals(later,Studios.at(new BlockPos(1005,66,1005)).orElseThrow().id());
        assertTrue(Studios.at(new BlockPos(500,66,500)).isEmpty());
    }
    @Test void only_matching_remote_hello_changes_mode_and_singleplayer_never_sends() {
        UUID player=UUID.randomUUID(); StudioId id=StudioId.of(player);
        var yes=new MpServerConfig(true,128,true,true,true,true,true,12,4,10);
        assertTrue(MpPayloads.helloFor(player,yes,false,true).isEmpty());
        assertTrue(MpPayloads.helloFor(player,MpServerConfig.DEFAULT,true,true).isEmpty());
        assertTrue(MpPayloads.helloFor(player,yes,true,false).isEmpty());
        var hello=MpPayloads.helloFor(player,yes,true,true).orElseThrow();
        assertEquals(-1,hello.plotIndex()); assertEquals(id,hello.you());
        try(var capture=MpLog.capture()) {
            MpMode.joined(true,"integrated"); assertFalse(MpMode.receiveHello(hello,true,player));
            assertEquals(MpMode.SINGLEPLAYER,MpMode.current()); assertEquals(StudioId.LOCAL,Anchors.self());
            MpMode.joined(false,MpMode.hashServer("example.invalid:25600"));
            assertFalse(MpMode.receiveHello(new HelloS2C(2,id,-1,new ServerInfo(128,12)),false,player));
            assertFalse(MpMode.receiveHello(hello,false,UUID.randomUUID()));
            for(int i=0;i<100;i++) MpMode.tick(); assertEquals(MpMode.REMOTE_VANILLA,MpMode.current());
            assertTrue(MpMode.receiveHello(hello,false,player)); assertEquals(MpMode.MULTIPLAYER,MpMode.current());
            assertEquals(id,Anchors.self()); assertEquals(0,Studios.own().slot());
            assertFalse(MpPayloads.acceptHello(player,new HelloC2S(2,"secret"),yes,true));
            assertFalse(MpPayloads.acceptHello(player,new HelloC2S(1,"secret"),yes,false));
            assertTrue(MpPayloads.acceptHello(player,new HelloC2S(1,"secret"),yes,true)); assertTrue(MpPayloads.isModEquipped(player));
            MpPayloads.logSent(hello);
            assertTrue(capture.lines().stream().anyMatch(l->l.startsWith("event=hello_sent ")));
            assertTrue(capture.lines().stream().anyMatch(l->l.startsWith("event=hello_received ")));
            assertTrue(capture.lines().stream().anyMatch(l->l.startsWith("event=mode_changed from=REMOTE_VANILLA to=MULTIPLAYER server=")));
            assertFalse(capture.lines().toString().contains("example.invalid")); assertFalse(capture.lines().toString().contains("secret"));
        }
        MpMode.disconnected(); assertEquals(MpMode.SINGLEPLAYER,MpMode.current()); assertEquals(StudioId.LOCAL,Studios.own().id());
    }
}
