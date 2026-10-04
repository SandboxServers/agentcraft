package dev.agentcraft.client.dev;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** MP-12: the command text a remote server may receive, and the plain-decimal numbers of its /tp. */
class DevRemoteCommandTest {
	@Test void one_leading_slash_is_stripped_and_plain_command_text_is_unchanged() {
		assertEquals("time set 0", DevCommands.remoteCommandText("/time set 0"));
		assertEquals("time set 0", DevCommands.remoteCommandText("time set 0"));
		assertEquals("a".repeat(256), DevCommands.remoteCommandText("a".repeat(256)));
		// the limit counts the text that is sent, not the slash in front of it
		assertEquals("a".repeat(256), DevCommands.remoteCommandText("/" + "a".repeat(256)));
	}

	@Test void a_remote_command_is_refused_when_it_is_blank_too_long_or_not_chat_text() {
		// The server disconnects a client that sends any of these, so they never leave the client.
		refused("");
		refused("   ");
		refused("/"); // the slash alone leaves nothing to run
		refused("a".repeat(257));
		refused("say a\nb"); // §, a control character or 127 all fail isAllowedChatCharacter
		refused("say §c");
	}

	@Test void the_remote_tp_arguments_are_plain_decimals_with_no_exponent_notation() {
		String big = DevCommands.tpArg(1e7);
		String small = DevCommands.tpArg(1e-4);
		assertFalse(big.contains("E"), big);
		assertFalse(small.contains("E"), small);
		assertEquals("64.38", DevCommands.tpArg(64.38));
	}

	/** Refuse {@code cmd} with a {@link DevBridge.DevException} that names the field. */
	private static String refused(String cmd) {
		DevBridge.DevException e = assertThrows(DevBridge.DevException.class, () -> DevCommands.remoteCommandText(cmd));
		assertTrue(e.getMessage().contains("'cmd'"), e.getMessage());
		return e.getMessage();
	}
}
