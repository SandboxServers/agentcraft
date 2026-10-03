package dev.agentcraft.mp.net;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PublicStateC2S(PublicStudioState state) implements CustomPacketPayload {
    public static final Type<PublicStateC2S> TYPE = new Type<>(AgentCraft.id("public_state_c2s"));
    public static final StreamCodec<RegistryFriendlyByteBuf,PublicStateC2S> CODEC = MpCodecs.codec(
        (b,v)-> { MpCodecs.state(b,v.state()); }, b->new PublicStateC2S(MpCodecs.state(b)));
    public Type<PublicStateC2S> type() { return TYPE; }
}
