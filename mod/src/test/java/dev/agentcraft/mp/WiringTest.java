package dev.agentcraft.mp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WiringTest {
    @Test void all_feature_seams_are_wired_once() throws Exception {
        for(var group:List.of(
                List.of("main/java/dev/agentcraft/AgentCraft.java","world/ServerWorldFeature","plot/PlotFeature","layout/LayoutSyncFeature","relay/RelayFeature","intent/WorldIntentFeature","protect/PlotProtectionFeature"),
                List.of("client/java/dev/agentcraft/client/ClientFeatures.java","layout/LayoutSyncClient","publish/PublishFeature","remote/RemoteStudiosFeature","visitor/VisitorFeature"))) {
            String wiring=Files.readString(Path.of("src",group.getFirst()));
            for(String stub:group.subList(1,group.size())) {
                String name=stub.substring(stub.lastIndexOf('/')+1);
                assertEquals(1,wiring.split(name+"\\.init\\(\\)",-1).length-1,name);
                Path root=Path.of(group.getFirst().startsWith("main")?"src/main/java/dev/agentcraft/mp/server":"src/client/java/dev/agentcraft/client/mp");
                assertTrue(Files.readString(root.resolve(stub+".java")).contains("public static void init()"));
            }
        }
    }
}
