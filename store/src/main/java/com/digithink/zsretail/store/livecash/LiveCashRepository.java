package com.digithink.zsretail.store.livecash;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;

/**
 * 3.2.0, "Caisse en direct": the reads of the page that no other reader has, each one query (grouped, or one row per
 * POS machine). Only the open sessions and the latest session of each POS machine are read; nothing is locked.
 */
public interface LiveCashRepository extends Repository<CashierSession, Long> {

	/**
	 * The open sessions, oldest first: {id, number, opened at, opening cash, cashier id, full name, username}. Columns, not
	 * entities: a session's cashier and its role are read eagerly, one more query each.
	 */
	@Query("SELECT s.id, s.sessionNumber, s.openedAt, s.openingCash, c.id, c.fullName, c.username"
			+ " FROM CashierSession s LEFT JOIN s.cashier c WHERE s.status = :opened ORDER BY s.openedAt, s.id")
	List<Object[]> openSessions(@Param("opened") SessionStatus opened);

	/**
	 * The POS machines shown, by name: {id, name, active}: the active ones, and an inactive one that still holds an open
	 * session. Columns, not entities: a machine's reader and users are read eagerly, one more query each.
	 */
	@Query("SELECT m.id, m.name, m.active FROM PosMachine m WHERE m.active = true OR EXISTS (SELECT l.sessionId"
			+ " FROM SessionPosMachine l WHERE l.posMachine = m AND l.session.status = :opened) ORDER BY m.name, m.id")
	List<Object[]> machines(@Param("opened") SessionStatus opened);

	/** {session id, POS machine id} of these sessions (a session without POS machine is absent). */
	@Query("SELECT l.sessionId, l.posMachine.id FROM SessionPosMachine l WHERE l.sessionId IN :ids")
	List<Object[]> machineOf(@Param("ids") Collection<Long> ids);

	/**
	 * The latest session of each POS machine (its highest id: ids follow the openings), whatever its date: {machine id,
	 * session id, number, status, closed at, cashier's full name, cashier's username}. One row per machine.
	 */
	@Query("SELECT l.posMachine.id, s.id, s.sessionNumber, s.status, s.closedAt, c.fullName, c.username"
			+ " FROM SessionPosMachine l JOIN l.session s LEFT JOIN s.cashier c"
			+ " WHERE l.sessionId = (SELECT MAX(l2.sessionId) FROM SessionPosMachine l2 WHERE l2.posMachine = l.posMachine)")
	List<Object[]> latestSessionOfEachMachine();

	/** {session id, time of its last ticket that counts} (neither PENDING nor CANCELLED). */
	@Query("SELECT s.cashierSession.id, MAX(s.salesDate) FROM SalesHeader s WHERE s.cashierSession.id IN :ids"
			+ " AND s.status NOT IN :notCounted GROUP BY s.cashierSession.id")
	List<Object[]> lastTicketTimes(@Param("ids") Collection<Long> ids,
			@Param("notCounted") Collection<TransactionStatus> notCounted);

	/**
	 * The Journal's day (StoreDaysService, SalesHeaderRepository.findFinishedTimesAndTotals): {tickets, their total} of
	 * the finished tickets whose salesDate is in [from, to), whatever their session.
	 */
	@Query("SELECT COUNT(s), SUM(s.totalAmount) FROM SalesHeader s WHERE s.status NOT IN :excluded"
			+ " AND s.salesDate >= :from AND s.salesDate < :to")
	List<Object[]> dayTotals(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
			@Param("excluded") Collection<TransactionStatus> excluded);
}
