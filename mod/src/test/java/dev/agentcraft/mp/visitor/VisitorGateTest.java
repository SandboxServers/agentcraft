package dev.agentcraft.mp.visitor;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.mp.visitor.VisitorGate;
import dev.agentcraft.client.mp.visitor.VisitorGate.Route;
import dev.agentcraft.client.mp.visitor.VisitorGate.Station;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.AgentStateWire;
import dev.agentcraft.mp.state.Counts;
import dev.agentcraft.mp.state.GoalStatusWire;
import dev.agentcraft.mp.state.GoalSummary;
import dev.agentcraft.mp.state.PublicAgent;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import dev.agentcraft.mp.state.PublicTask;
import dev.agentcraft.mp.state.StationWire;
import dev.agentcraft.mp.state.TaskStatusWire;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MP-11: the pure visitor routing table, the public-only copy and the telemetry capture. */
class VisitorGateTest {
	private static StudioView own() {
		return new StudioView(StudioId.LOCAL, true, "", true, Anchors.Layout.EMPTY, null, 0);
	}

	private static StudioView remote(PublicStudioState state) {
		return new StudioView(StudioId.of(UUID.randomUUID()), false, "Bob", true, Anchors.Layout.EMPTY, state, 1);
	}

	/** Every opt-in text at once, so a leaked field is visible in the assertion. */
	private static PublicStudioState leakyState() {
		PublicPolicy policy = new PublicPolicy(true, true, true, true);
		List<PublicAgent> agents = List.of(new PublicAgent("kit", "SECRET-NAME", "kit",
			AgentStateWire.WAITING_USER, StationWire.USER, true, false, true, "editing SECRET/path.ts"));
		List<PublicTask> tasks = List.of(new PublicTask("t1", "SECRET-TASK", TaskStatusWire.DOING, "kit"));
		return new PublicStudioState(7, true, agents, new Counts(1, 2, 3, 4, 5, 6, 2),
			new GoalSummary(GoalStatusWire.ACTIVE, 0.4f, "SECRET-GOAL"), List.of(), policy, tasks);
	}

	@Test void routing_sends_only_remote_studios_to_the_visitor_panel() {
		assertEquals(Route.HANDLER, VisitorGate.route(Optional.empty()));
		assertEquals(Route.HANDLER, VisitorGate.route(Optional.of(own())));
		assertEquals(Route.VISITOR, VisitorGate.route(Optional.of(remote(null))));
		assertEquals(Route.VISITOR, VisitorGate.route(Optional.of(remote(leakyState()))));
	}

	@Test void station_lines_use_counts_and_opt_in_titles_only() {
		PublicStudioState state = leakyState();
		for (Station kind : List.of(Station.PODIUM, Station.MERGE, Station.ARCHIVE, Station.CATALOG, Station.LECTERN, Station.CONSOLE)) {
			String text = String.join("\n", VisitorGate.stationLines(state, kind));
			assertFalse(text.contains("SECRET-TASK"), "task title leaked into " + kind);
			assertFalse(text.contains("SECRET-GOAL"), "goal text leaked into " + kind);
			assertFalse(text.contains("SECRET/path.ts"), "activity leaked into " + kind);
			assertFalse(text.contains("SECRET-NAME"), "agent name leaked into " + kind);
		}
		assertEquals(List.of("6 decisions waiting"), VisitorGate.stationLines(state, Station.PODIUM));
		assertEquals(List.of("A merge is waiting."), VisitorGate.stationLines(state, Station.MERGE));
		assertEquals(List.of("Memory is private to its owner."), VisitorGate.stationLines(state, Station.ARCHIVE));

		// the wall shows titles only when the owner opted in; with policy off the record carries no tasks
		String optedIn = String.join("\n", VisitorGate.stationLines(state, Station.TASK_WALL));
		assertTrue(optedIn.contains("SECRET-TASK"));
		PublicStudioState withheld = new PublicStudioState(8, true, List.of(), new Counts(1, 2, 3, 4, 5, 6, 2),
			new GoalSummary(GoalStatusWire.NONE, 0, null), List.of(), PublicPolicy.DEFAULT, null);
		String countsOnly = String.join("\n", VisitorGate.stationLines(withheld, Station.TASK_WALL));
		assertFalse(countsOnly.contains("SECRET-TASK"));
		assertTrue(countsOnly.contains("Todo 1"));
		assertFalse(String.join("\n", VisitorGate.stationLines(null, Station.PODIUM)).contains("SECRET"));
	}

	@Test void agent_lines_say_the_owner_and_never_the_viewer() {
		PublicAgent waiting = new PublicAgent("kit", "Kit", "kit", AgentStateWire.WAITING_USER, StationWire.USER,
			true, false, true, null);
		String text = String.join("\n", VisitorGate.agentLines(waiting, "Bob"));
		assertTrue(text.contains("Waiting for Bob"));
		assertFalse(text.toLowerCase(Locale.ROOT).contains("you"));
		assertFalse(String.join("\n", VisitorGate.agentLines(waiting, null)).contains("Waiting for null"));

		PublicAgent active = new PublicAgent("kit", "Kit", "kit", AgentStateWire.EDITING, StationWire.DESK,
			true, false, false, "editing SECRET/path.ts");
		String activeText = String.join("\n", VisitorGate.agentLines(active, "Bob"));
		assertTrue(activeText.contains("Editing · desk"));
		assertFalse(activeText.contains("SECRET/path.ts"));
	}

	@Test void visitor_readonly_capture_has_correlators_and_closed_tokens() {
		UUID viewer = UUID.fromString("11111111-1111-1111-1111-111111111111");
		StudioView studio = remote(leakyState());
		try (MpLog.Capture capture = MpLog.capture()) {
			assertFalse(VisitorGate.logOpen(Route.HANDLER, Station.PODIUM, remote(leakyState()), viewer));
			VisitorGate.logOpen(Station.PODIUM, studio, viewer);
			VisitorGate.logOpen(Route.VISITOR, Station.AGENT, studio, viewer);
			assertEquals(List.of(
				"event=visitor_readonly player=" + viewer + " studio=" + studio.id().owner()
					+ " plot=-1 rev=7 station=podium action=open",
				"event=visitor_readonly player=" + viewer + " studio=" + studio.id().owner()
					+ " plot=-1 rev=7 station=agent action=open"), capture.lines());
			assertFalse(capture.lines().toString().contains("SECRET"));
		}
		assertEquals("debug", MpEvents.CATALOG.get(MpEvents.VISITOR_READONLY));
	}
}
