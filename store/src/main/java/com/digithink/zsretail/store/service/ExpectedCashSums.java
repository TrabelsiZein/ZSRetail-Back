package com.digithink.zsretail.store.service;

import java.util.Collection;
import java.util.Map;

import com.digithink.zsretail.core.model.CashierSession;

/**
 * 3.2.0: the sums the expected cash of a drawer is made of, per session (CashierSessionService.expectedCash adds them
 * to the opening and the cash movements). In production SQL, one query per kind for any number of sessions
 * ({@link SqlExpectedCashSums}); a unit test gives the same sums over its in-memory tickets.
 */
public interface ExpectedCashSums {

	/** One session's sums (0 when nothing). */
	final class Sums {
		public double cashPaid;
		public double changeGiven;
		public double simpleReturns;
	}

	/** The sums of these sessions, by session id (every session in it). */
	Map<Long, Sums> of(Collection<CashierSession> sessions);
}
