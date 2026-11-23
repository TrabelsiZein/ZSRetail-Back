package com.digithink.zsretail.core.eventlog.service;

import com.digithink.zsretail.core.eventlog.model.EventLogEntry;

/**
 * 3.4.0, event log step 2: fills what an event's caller did not say (its POS machine, its session, the badge's owner),
 * so that a gesture's event stays one line. An application gives its own (the store: StoreEventContext); a value the
 * caller gave is never replaced. Neither method may throw to the gesture: EventLog catches them.
 */
public interface EventLogContext {

	/**
	 * At the gesture, in its thread and its transaction: only what the request knows in memory (no query: a failing
	 * query would mark the gesture's transaction rollback-only), e.g. the badge grants the request used.
	 */
	default void atCall(EventLogEntry entry) {
	}

	/** In the event's own transaction, before it is numbered: what needs a query (the session, the machine, names). */
	default void complete(EventLogEntry entry) {
	}
}
