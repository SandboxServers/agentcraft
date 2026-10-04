package dev.agentcraft.client.mp;

import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.*;
import dev.agentcraft.mp.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.loader.api.FabricLoader;

public enum MpMode {
    SINGLEPLAYER, MULTIPLAYER, REMOTE_VANILLA;
    private static MpMode current=SINGLEPLAYER;
    private static final List<Consumer<MpMode>> LISTENERS=new ArrayList<>();
    private static int helloTicks;
    private static String serverHash="none";
    public static MpMode current() { return current; }
    public static void addListener(Consumer<MpMode> listener) { LISTENERS.add(listener); }
    public static String hashServer(String address) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8))).substring(0,16); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void change(MpMode mode) {
        if(mode==current) return;
        MpMode before=current; current=mode;
        if(Anchors.self().equals(StudioId.LOCAL))
            MpLog.event(MpEvents.MODE_CHANGED,"from",before,"to",mode,"server",serverHash);
        else
            MpLog.event(MpEvents.MODE_CHANGED,"from",before,"to",mode,"server",serverHash,"player",Anchors.self().owner(),"studio",Anchors.self().owner());
        LISTENERS.forEach(l->l.accept(mode));
    }
    public static void joined(boolean integrated,String hash) {
        Studios.reset(); serverHash=hash; helloTicks=integrated?0:100; change(SINGLEPLAYER);
    }
    public static void tick() { if(helloTicks>0 && --helloTicks==0 && current!=MULTIPLAYER) change(REMOTE_VANILLA); }
    public static void disconnected() {
        helloTicks=0; change(SINGLEPLAYER); Studios.reset();
        for(StudioId id:List.copyOf(Anchors.all().keySet())) if(!id.equals(StudioId.LOCAL)) Anchors.remove(id);
        serverHash="none";
    }
    /** Pure entry point for protocol/identity/lifecycle tests; the receiver alone sends the reply. */
    public static boolean receiveHello(HelloS2C hello,boolean integrated,UUID player) {
        if(integrated || hello.protocol()!=MpProtocol.VERSION || !hello.you().owner().equals(player)) return false;
        helloTicks=0; Studios.setOwn(hello.you()); Studios.setPlot(hello.you(),hello.plotIndex(),hello.serverInfo().plotStride());
        change(MULTIPLAYER);
        MpLog.event(MpEvents.HELLO_RECEIVED,"player",player,"studio",hello.you().owner(),"plot",hello.plotIndex(),"protocol",hello.protocol(),"mode",current);
        return true;
    }
    public static void init() {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(dev.agentcraft.AgentCraft.id("hud/mp_mode"),(g,delta)-> {
            if(current()!=REMOTE_VANILLA) return;
            var mc=net.minecraft.client.Minecraft.getInstance();
            if(mc.player==null) return;
            String text="Server without AgentCraft · local features";
            dev.agentcraft.client.ui.Panels.pill(g,mc.font,text,6,g.guiHeight()-36,dev.agentcraft.client.ui.UiStyle.color("paper.text"));
        });
        ClientPlayConnectionEvents.JOIN.register((handler,sender,mc)-> joined(mc.getSingleplayerServer()!=null,hashServer(mc.getCurrentServer()==null?"integrated":mc.getCurrentServer().ip)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler,mc)->disconnected());
        ClientTickEvents.END_CLIENT_TICK.register(mc->tick());
        ClientPlayNetworking.registerGlobalReceiver(HelloS2C.TYPE,(hello,context)->context.client().execute(()-> {
            if(context.client().player!=null && receiveHello(hello,context.client().getSingleplayerServer()!=null,context.client().player.getUUID()) && ClientPlayNetworking.canSend(HelloC2S.TYPE)) {
                String version=FabricLoader.getInstance().getModContainer("agentcraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
                ClientPlayNetworking.send(new HelloC2S(MpProtocol.VERSION,version));
                MpLog.event(MpEvents.HELLO_SENT,"player",context.player().getUUID(),"studio",hello.you().owner(),"plot",hello.plotIndex(),"protocol",MpProtocol.VERSION);
            }
        }));
    }
}
