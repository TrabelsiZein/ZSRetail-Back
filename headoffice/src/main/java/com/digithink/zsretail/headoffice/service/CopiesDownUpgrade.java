package com.digithink.zsretail.headoffice.service;

/**
 * Stock point copies, step 5: a one-time change of the copies down rows of an existing database, run at each start
 * before the backfill ({@link CopiesDownFeed#initialise}). It does its work only while there is some, in its own
 * transaction, so a second start changes nothing.
 */
public interface CopiesDownUpgrade {

	/** Does the work when there is some; returns a line for the log, null when there was nothing to do. */
	String run();
}
