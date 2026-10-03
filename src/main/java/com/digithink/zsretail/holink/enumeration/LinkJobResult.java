package com.digithink.zsretail.holink.enumeration;

/** Outcome of one run of a head office link job, or of one exchange (task 2.6), like the ERP jobs. */
public enum LinkJobResult {

	/** Everything went through (or there was nothing to do). */
	SUCCESS,

	/** The head office answered, but some records were rejected or could not be built. */
	WARNING,

	/** Nothing went through: head office unreachable, refused, unexpected answer, or a local failure. */
	ERROR
}
