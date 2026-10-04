package dev.agentcraft.client.hq;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.agentcraft.block.LampStatus;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.ForemanStates;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.state.LampStatusWire;
import dev.agentcraft.mp.state.PublicJson;
import dev.agentcraft.mp.state.WorldIntent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;

/**
 * Golden tests for the pure {@link HqWorldDriver#compute(ForemanState, Anchors.Layout)}: the new
 * {@link WorldIntent} must equal the pre-MP-07 driver's visible state (see
 * {@link HqWorldDriverReference}) with the {@code ci:<repoId>} keys removed, for both fixtures.
 */
class WorldIntentComputeTest {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	@Test
	void computeMatchesReference_showcase() {
		ForemanState st = ForemanStates.showcase();
		assertMatchesReference(st, "sim-demo-fixture-busy");
	}

	@Test
	void computeMatchesReference_showcaseLate() {
		ForemanState st = ForemanStates.showcaseLate();
		assertMatchesReference(st, "sim-demo-fixture-late");
	}

	@Test
	void showcaseRepoIdIsAbsentFromEncodedIntent() {
		assertRepoIdsAbsent(ForemanStates.showcase(), "sim-demo-fixture-busy");
	}

	@Test
	void showcaseLateRepoIdIsAbsentFromEncodedIntent() {
		assertRepoIdsAbsent(ForemanStates.showcaseLate(), "sim-demo-fixture-late");
	}

	@Test
	void everyLampKeyIsALegalBinding() {
		assertAllKeysLegal(ForemanStates.showcase());
		assertAllKeysLegal(ForemanStates.showcaseLate());
	}

	@Test
	void activeAgentWith17CharIdIsLeftOutOfLitMonitors() {
		ForemanState st = ForemanStates.showcase();
		String longId = "0123456789abcdefg"; // 17 chars: the wire cannot carry it
		JsonObject agent = new JsonObject();
		agent.addProperty("id", longId);
		agent.addProperty("name", "Long");
		agent.addProperty("state", "idle");
		agent.addProperty("active", true);
		JsonObject msg = new JsonObject();
		msg.addProperty("v", 1);
		msg.addProperty("type", "agent.upsert");
		msg.add("agent", agent);
		assertTrue(st.inject("agent.upsert", msg), "the long-id agent should be accepted into the state");

		WorldIntent intent = assertDoesNotThrow(() -> HqWorldDriver.compute(st, Anchors.Layout.EMPTY));
		assertFalse(intent.litMonitors().contains(longId), "a 17-char id must not reach litMonitors");
		assertFalse(intent.lamps().containsKey("agent:" + longId), "a 17-char id must not reach lamps");
	}

	@Test
	void roundTripsThroughPublicJson() {
		WorldIntent i = HqWorldDriver.compute(ForemanStates.showcase(), Anchors.Layout.EMPTY);
		WorldIntent back = PublicJson.intentFromJson(PublicJson.toJson(i));
		assertEquals(i.lamps(), back.lamps());
		assertEquals(i.podiumOpen(), back.podiumOpen());
		assertEquals(i.mergeActive(), back.mergeActive());
		assertEquals(i.litMonitors(), back.litMonitors());
		assertEquals(i.rev(), back.rev());
	}

	// ------------------------------------------------------------------ helpers

	private static void assertMatchesReference(ForemanState st, String repoId) {
		WorldIntent intent = HqWorldDriver.compute(st, Anchors.Layout.EMPTY);
		HqWorldDriverReference.Reference ref = HqWorldDriverReference.compute(st);

		// Expected lamps: the reference's, without the ci:<repoId> keys.
		Map<String, LampStatusWire> expectedLamps = new LinkedHashMap<>();
		ref.lamps().forEach((k, v) -> {
			if (!isRepoKey(k)) {
				expectedLamps.put(k, LampStatusWire.valueOf(v.name()));
			}
		});
		assertEquals(expectedLamps, intent.lamps(), "lamps differ from the reference (minus ci:<repoId>)");

		assertEquals(ref.podiumOpen(), intent.podiumOpen(), "podiumOpen differs from the reference");
		assertEquals(ref.mergeActive(), intent.mergeActive(), "mergeActive differs from the reference");

		Set<String> expectedLit = new java.util.HashSet<>();
		ref.monitorLit().forEach((id, lit) -> {
			if (lit) {
				expectedLit.add(id);
			}
		});
		assertEquals(expectedLit, intent.litMonitors(), "litMonitors differ from the reference");

		// The fixture's own repo id never appears, and every ci: key is a slot.
		assertFalse(intent.lamps().containsKey("ci:" + repoId), "ci:<repoId> must be gone");
		assertTrue(intent.lamps().keySet().stream().noneMatch(WorldIntentComputeTest::isRepoKey),
			"no ci:<repoId> key may remain");
	}

	private static void assertRepoIdsAbsent(ForemanState st, String repoId) {
		WorldIntent intent = HqWorldDriver.compute(st, Anchors.Layout.EMPTY);
		String encoded = GSON.toJson(PublicJson.toJson(intent));
		assertFalse(encoded.contains(repoId), "encoded intent must not contain the repo id " + repoId);
		assertFalse(encoded.contains("ci:" + repoId), "encoded intent must not contain a ci:<repoId> key");
	}

	private static void assertAllKeysLegal(ForemanState st) {
		WorldIntent intent = HqWorldDriver.compute(st, Anchors.Layout.EMPTY);
		for (String key : intent.lamps().keySet()) {
			assertTrue(WorldIntent.isBinding(key), "lamp key '" + key + "' is not a legal binding");
		}
	}

	private static boolean isRepoKey(String key) {
		return key.startsWith("ci:") && !key.matches("ci:#[1-8]");
	}
}