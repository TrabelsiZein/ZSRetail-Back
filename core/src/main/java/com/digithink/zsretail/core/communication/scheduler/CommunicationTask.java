package com.digithink.zsretail.core.communication.scheduler;

import java.time.Instant;

/**
 * 3.3.0: one task of the communication cycle (step 2: the store's two finders). Every bean implementing it is run by
 * {@link CommunicationScheduler} on its single thread, in its {@code @Order}, once per cycle. To add one (later steps):
 * a bean with {@code @ConditionalOnInstallation(Installation.COMMUNICATION)} (and the condition of where it runs), an
 * order and a code.
 */
public interface CommunicationTask {

	/** Stable code, for the log, e.g. TICKET_EVENTS. */
	String getCode();

	/**
	 * One run: stop starting new work after {@code deadline} (the cycle's bound). Returns what it did in a few words,
	 * or null when there was nothing to do (nothing logged). A throw is logged and the next task and cycle go on.
	 */
	String run(Instant deadline);

	/**
	 * Step 8b: after its run, work this task can do at once is left (messages due on a channel not paused, a campaign
	 * still being made): the next cycle starts after CommunicationScheduler.FAST_DELAY instead of
	 * communication.cycle-seconds.
	 */
	default boolean moreToDo() {
		return false;
	}
}
