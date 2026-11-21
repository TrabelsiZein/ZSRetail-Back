package com.digithink.zsretail.core.communication.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 3.3.0, communication module, step 2: the cycle of the module's scheduler. Its frequency (communication.cycle-seconds,
 * absent or empty: 60); every task in order with the cycle's deadline; a task that fails never stops the next task or
 * the next cycle. That the module off has no scheduler and no thread: InstallationBeansProofTest.
 */
class CommunicationSchedulerTest {

	private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

	private final List<String> runs = new ArrayList<>();

	private CommunicationTask task(String code, boolean fails) {
		return new CommunicationTask() {
			@Override
			public String getCode() {
				return code;
			}

			@Override
			public String run(Instant deadline) {
				runs.add(code + " until " + deadline);
				if (fails) {
					throw new IllegalStateException("The query has timed out.");
				}
				return null;
			}
		};
	}

	@Test
	@DisplayName("communication.cycle-seconds: absent or empty 60, a whole number of at least 1; step 8e: anything else 60"
			+ " with a WARN, never a refused start")
	void cycleSeconds() {
		assertEquals(60, CommunicationScheduler.cycleSeconds(null));
		assertEquals(60, CommunicationScheduler.cycleSeconds(" "));
		assertEquals(15, CommunicationScheduler.cycleSeconds(" 15 "));
		for (String bad : new String[] { "0", "-5", "1.5", "one" }) {
			assertEquals(60, CommunicationScheduler.cycleSeconds(bad), bad);
		}
	}

	@Test
	@DisplayName("A cycle runs every task in order with a deadline 20 s ahead; a failing task never stops the next one or the next cycle")
	void failingTask() {
		CommunicationScheduler scheduler = new CommunicationScheduler(
				Arrays.asList(task("TICKET_EVENTS", true), task("MEMBER_EVENTS", false)), null, 60, () -> NOW);
		scheduler.cycle();
		scheduler.cycle();
		assertEquals(Arrays.asList("TICKET_EVENTS until 2026-11-20T10:00:20Z", "MEMBER_EVENTS until 2026-11-20T10:00:20Z",
				"TICKET_EVENTS until 2026-11-20T10:00:20Z", "MEMBER_EVENTS until 2026-11-20T10:00:20Z"), runs);
	}

	@Test
	@DisplayName("Step 8b: a cycle says when a task has work left it can do at once; the next cycle then comes after 5 s"
			+ " (never more than cycle-seconds)")
	void moreToDo() {
		CommunicationTask busy = new CommunicationTask() {
			@Override
			public String getCode() {
				return "MESSAGES_SENT";
			}

			@Override
			public String run(Instant deadline) {
				return null;
			}

			@Override
			public boolean moreToDo() {
				return true;
			}
		};
		assertEquals(false, new CommunicationScheduler(Arrays.asList(task("TICKET_EVENTS", false)), null, 60, () -> NOW)
				.cycle());
		assertEquals(true, new CommunicationScheduler(Arrays.asList(task("TICKET_EVENTS", true), busy), null, 60, () -> NOW)
				.cycle(), "a failing task before never hides the work left");
		assertEquals(java.time.Duration.ofSeconds(5), CommunicationScheduler.busyDelay(60));
		assertEquals(java.time.Duration.ofSeconds(3), CommunicationScheduler.busyDelay(3));
	}
}
