package dev.agentcraft.mp.net;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record HelloC2S(int protocol, String modVersion) implements CustomPacketPayload {
    public static final Type<HelloC2S> TYPE = new Type<>(AgentCraft.id("hello_c2s"));
    public static final StreamCodec<RegistryFriendlyByteBuf,HelloC2S> CODEC = MpCodecs.codec(
        (b,v)-> { b.writeVarInt(v.protocol()); b.writeUtf(v.modVersion()); }, b->new HelloC2S(MpCodecs.integer(b,0,Integer.MAX_VALUE), MpCodecs.text(b,32)));
    public Type<HelloC2S> type() { return TYPE; }
}
