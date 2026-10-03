package dev.agentcraft.mp.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class MpPayloads {
    private static boolean registered;
    private MpPayloads() {}
    public static synchronized void register() {
        if(registered) return;
        PayloadTypeRegistry.clientboundPlay().register(HelloS2C.TYPE,HelloS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HelloC2S.TYPE,HelloC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LayoutS2C.TYPE,LayoutS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LayoutRemoveS2C.TYPE,LayoutRemoveS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PublicStateC2S.TYPE,PublicStateC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StudioStateS2C.TYPE,StudioStateS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StudioEventC2S.TYPE,StudioEventC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StudioEventS2C.TYPE,StudioEventS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PresenceS2C.TYPE,PresenceS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WorldIntentC2S.TYPE,WorldIntentC2S.CODEC);
        registered=true;
    }
}
