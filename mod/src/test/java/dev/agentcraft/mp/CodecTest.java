package dev.agentcraft.mp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.net.*;
import dev.agentcraft.mp.state.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

public class CodecTest {
    public static PublicStudioState state() {
        return new PublicStudioState(1,true,List.of(new PublicAgent("kit","Kit","kit",AgentStateWire.EDITING,StationWire.DESK,true,false,false,null)),new Counts(1,2,3,4,5,6,7),new GoalSummary(GoalStatusWire.ACTIVE,.5f,null),List.of(new CiSlot(0,CiStatusWire.PASS)),PublicPolicy.DEFAULT,null);
    }
    private static RegistryFriendlyByteBuf buf() { return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY); }
    private static <T> T round(StreamCodec<RegistryFriendlyByteBuf,T> c,T value) {
        var b=buf(); try { c.encode(b,value); T decoded=c.decode(b); assertEquals(0,b.readableBytes()); return decoded; } finally { b.release(); }
    }
    @Test void every_payload_round_trips() {
        StudioId id=StudioId.of(UUID.randomUUID());
        var layout=Anchors.builder("studio").bounds(-46,60,-36,46,100,54).spot("desk_kit",1,66,2,90).build();
        assertRound(HelloS2C.CODEC,new HelloS2C(1,id,-1,new ServerInfo(128,12)));
        assertRound(HelloC2S.CODEC,new HelloC2S(1,"0.1.0"));
        assertRound(LayoutS2C.CODEC,new LayoutS2C(id,0,layout));
        assertRound(LayoutRemoveS2C.CODEC,new LayoutRemoveS2C(id));
        assertRound(PublicStateC2S.CODEC,new PublicStateC2S(state()));
        assertRound(StudioStateS2C.CODEC,new StudioStateS2C(id,state()));
        for(PublicEvent e:List.of(new PublicEvent.Say("kit","user",null,500),new PublicEvent.TaskDone("kit"))) {
            assertRound(StudioEventC2S.CODEC,new StudioEventC2S(e)); assertRound(StudioEventS2C.CODEC,new StudioEventS2C(id,e));
        }
        assertRound(PresenceS2C.CODEC,new PresenceS2C(id,"Bob",true));
        assertRound(WorldIntentC2S.CODEC,new WorldIntentC2S(new WorldIntent(1,Map.of("agent:kit",LampStatusWire.WORKING),true,false,Set.of("kit"))));
    }
    private static <T> void assertRound(StreamCodec<RegistryFriendlyByteBuf,T> c,T v) { assertEquals(v,round(c,v)); }
    private static void badState(Consumer<JsonObject> mutate) {
        JsonObject o=PublicJson.toJson(state()); mutate.accept(o);
        var b=buf(); try { b.writeUtf(o.toString()); assertThrows(RuntimeException.class,()->PublicStateC2S.CODEC.decode(b)); } finally { b.release(); }
    }
    @Test void every_public_state_collection_and_string_cap_is_checked_at_decode() {
        for(String key:List.of("agents","ci","tasks")) badState(o->{
            o.getAsJsonObject("policy").addProperty("taskTitles",true);
            JsonArray a=new JsonArray(); int cap=key.equals("agents")?16:key.equals("ci")?8:32;
            for(int i=0;i<=cap;i++) a.add(key.equals("agents")?o.getAsJsonArray("agents").get(0):key.equals("ci")?o.getAsJsonArray("ci").get(0):JsonParser.parseString("{\"id\":\"t\",\"title\":\"Task\",\"status\":\"todo\"}"));
            o.add(key,a);
        });
        for(String key:List.of("id","name","skin","activity")) badState(o->{
            o.getAsJsonObject("policy").addProperty("activityText",true);
            o.getAsJsonArray("agents").get(0).getAsJsonObject().addProperty(key,"x".repeat(key.equals("activity")?49:17));
        });
        badState(o->{ o.getAsJsonObject("policy").addProperty("goalText",true); o.getAsJsonObject("goal").addProperty("text","x".repeat(121)); });
        for(String key:List.of("id","title","assignee")) badState(o->{
            o.getAsJsonObject("policy").addProperty("taskTitles",true);
            var task=JsonParser.parseString("{\"id\":\"t\",\"title\":\"Task\",\"status\":\"todo\"}").getAsJsonObject();
            task.addProperty(key,"x".repeat(key.equals("title")?81:key.equals("id")?49:17)); JsonArray tasks=new JsonArray(); tasks.add(task); o.add("tasks",tasks);
        });
    }
    @Test void layout_and_hello_presence_event_and_intent_caps_are_checked_at_decode() {
        assertDecodeFails(HelloC2S.CODEC,new HelloC2S(1,"x".repeat(33)));
        assertDecodeFails(PresenceS2C.CODEC,new PresenceS2C(StudioId.LOCAL,"x".repeat(17),true));
        assertDecodeFails(LayoutS2C.CODEC,new LayoutS2C(StudioId.LOCAL,0,Anchors.builder("studio").spot("x".repeat(49),0,65,0,0).build()));
        var builder=Anchors.builder("studio"); for(int i=0;i<513;i++) builder.spot("a"+i,0,65,0,0);
        assertDecodeFails(LayoutS2C.CODEC,new LayoutS2C(StudioId.LOCAL,0,builder.build()));
        assertDecodeFails(LayoutS2C.CODEC,new LayoutS2C(StudioId.LOCAL,0,Anchors.builder("x".repeat(49)).build()));
        for(var e:List.of(new PublicEvent.Say("x".repeat(17),null,null,1),new PublicEvent.Say("kit","x".repeat(17),null,1),new PublicEvent.Say("kit",null,"x".repeat(121),1),new PublicEvent.TaskDone("x".repeat(17)))) assertDecodeFails(StudioEventC2S.CODEC,new StudioEventC2S(e));
        Map<String,LampStatusWire> lamps=new HashMap<>(); Set<String> lit=new HashSet<>();
        for(int i=0;i<65;i++) { lamps.put("b"+i,LampStatusWire.OFF); lit.add("a"+i); }
        assertDecodeFails(WorldIntentC2S.CODEC,new WorldIntentC2S(new WorldIntent(1,lamps,false,false,Set.of())));
        assertDecodeFails(WorldIntentC2S.CODEC,new WorldIntentC2S(new WorldIntent(1,Map.of(),false,false,lit)));
        assertDecodeFails(WorldIntentC2S.CODEC,new WorldIntentC2S(new WorldIntent(1,Map.of("x".repeat(49),LampStatusWire.OFF),false,false,Set.of())));
        assertDecodeFails(WorldIntentC2S.CODEC,new WorldIntentC2S(new WorldIntent(1,Map.of(),false,false,Set.of("x".repeat(17)))));
    }
    private static <T> void assertDecodeFails(StreamCodec<RegistryFriendlyByteBuf,T> c,T v) {
        var b=buf(); try { c.encode(b,v); assertThrows(RuntimeException.class,()->c.decode(b)); } finally { b.release(); }
    }
    @Test void strings_are_sanitized_and_opt_in_fields_fail_closed() {
        assertEquals("Kit",round(HelloC2S.CODEC,new HelloC2S(1,"§aK\nit\u0000")).modVersion());
        var o=PublicJson.toJson(state()); o.getAsJsonArray("agents").get(0).getAsJsonObject().addProperty("name","§cK\rit");
        assertEquals("Kit",PublicJson.fromJson(o).agents().getFirst().name());
        badState(j->j.getAsJsonArray("agents").get(0).getAsJsonObject().addProperty("activity","secret"));
        badState(j->j.getAsJsonObject("goal").addProperty("text","secret"));
        badState(j->j.add("tasks",new JsonArray()));
        assertEquals(new PublicEvent.Say("kit","user","Hi",2),PublicJson.eventFromJson(PublicJson.toJson(new PublicEvent.Say("kit","user","§aH\ni",2))));
        assertEquals(PublicPolicy.DEFAULT,state().policy());
    }
    @Test void malformed_values_never_reach_handlers() {
        badState(o->o.getAsJsonArray("agents").get(0).getAsJsonObject().addProperty("state","invalid"));
        badState(o->o.getAsJsonArray("agents").get(0).getAsJsonObject().addProperty("station","invalid"));
        badState(o->o.getAsJsonObject("goal").addProperty("status","invalid"));
        badState(o->o.getAsJsonArray("ci").get(0).getAsJsonObject().addProperty("ci","invalid"));
        badState(o->o.getAsJsonObject("goal").addProperty("progress",Float.POSITIVE_INFINITY));
        badState(o->o.getAsJsonObject("counts").addProperty("todo",-1));
        badState(o->o.addProperty("foremanOnline","true"));
        badState(o->o.addProperty("rev",1.5));
        badState(o->o.addProperty("privateData","secret"));
        assertDecodeFails(LayoutS2C.CODEC,new LayoutS2C(StudioId.LOCAL,0,Anchors.builder("studio").put("a",Double.NaN,0,0,0,0).build()));
        var b=buf(); try { b.writeVarInt(1); b.writeUtf("{broken"); b.readerIndex(1); assertThrows(RuntimeException.class,()->PublicStateC2S.CODEC.decode(b)); } finally { b.release(); }
    }
}
