package dev.agentcraft.mp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.agentcraft.client.mp.*;
import dev.agentcraft.client.mp.dev.MpDevFake;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.state.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class DevFakeTest {
    @Test void two_agent_overlay_updates_and_events_use_registry_listeners_then_clear() {
        Studios.reset(); Anchors.publish(Anchors.builder("studio").bounds(-46,60,-36,46,100,54).build());
        JsonObject req=new JsonObject(); JsonObject state=PublicJson.toJson(CodecTest.state());
        var second=state.getAsJsonArray("agents").get(0).deepCopy().getAsJsonObject(); second.addProperty("id","wren"); second.addProperty("name","Wren"); second.addProperty("skin","wren"); state.getAsJsonArray("agents").add(second);
        req.add("state",state); req.addProperty("owner","Bob");
        List<StudioView> changes=new ArrayList<>(); List<PublicEvent> events=new ArrayList<>();
        Studios.addListener((id,view)->{ if(id.equals(MpDevFake.FAKE)) changes.add(view); });
        Studios.addEventListener((id,event)->{ if(id.equals(MpDevFake.FAKE)) events.add(event); });
        try(var capture=MpLog.capture()) {
            var response=MpDevFake.apply(req); assertEquals("SINGLEPLAYER",response.get("mode").getAsString());
            assertEquals(2,response.getAsJsonArray("studios").size());
            assertEquals(MpDevFake.FAKE,Studios.at(new BlockPos(0,66,0)).orElseThrow().id());
            MpDevFake.apply(req); assertEquals(2,changes.size()); assertEquals(1,changes.getFirst().slot());
            var eventReq=new JsonObject(); eventReq.add("event",PublicJson.toJson(new PublicEvent.Say("kit","user",null,50))); MpDevFake.apply(eventReq);
            assertEquals(List.of(new PublicEvent.Say("kit","user",null,50)),events);
            req.addProperty("overlay",false); MpDevFake.apply(req); assertTrue(Studios.at(new BlockPos(0,66,0)).orElseThrow().own());
            MpDevFake.apply(JsonParser.parseString("{\"clear\":true}").getAsJsonObject());
            assertTrue(Studios.view(MpDevFake.FAKE).isEmpty()); assertNull(changes.getLast());
            assertTrue(capture.lines().stream().anyMatch(l->l.contains("action=set agents=2 rev=1")));
            assertTrue(capture.lines().stream().anyMatch(l->l.contains("action=clear agents=0")));
            assertFalse(capture.lines().toString().contains("Bob"));
            assertThrows(RuntimeException.class,()->MpDevFake.apply(eventReq));
            req.addProperty("overlay","true"); assertThrows(RuntimeException.class,()->MpDevFake.apply(req));
        } finally { Studios.reset(); Anchors.publish(Anchors.Layout.EMPTY); }
    }
}
