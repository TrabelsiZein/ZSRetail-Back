package com.digithink.zsretail.holink.scheduler;

import java.time.Duration;

import com.digithink.zsretail.holink.dto.LinkJobRun;

/**
 * A job of the head office link (task 2.6). Every bean implementing it is run by {@link LinkJobScheduler} on the single
 * ho-link-1 thread: first run {@link #getFirstDelay()} after the start, then its frequency after the end of the
 * previous run. The frequency is editable from the Head office link page and saved in hol_job; the value below is the
 * default while nothing is saved.
 * <p>
 * To add a job (later steps): one bean implementing this interface, with the condition that decides whether it exists
 * (the user never adds or removes a job), an {@code @Order} for its place in the list, and a new code. It writes its own
 * exchange log rows through {@code LinkExchangeLog}.
 */
public interface LinkJob {

	/** Stable code, the key of its hol_job row and of its exchange log rows, e.g. HEARTBEAT. */
	String getCode();

	/** Frequency while none is saved from the page: the job's properties value, in seconds. */
	long getDefaultIntervalSeconds();

	/** Delay of the first run after the start. */
	Duration getFirstDelay();

	/** One run on the ho-link thread. Should not throw; a throw is recorded as an ERROR run. */
	LinkJobRun run();
}
