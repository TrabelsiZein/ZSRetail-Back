package com.digithink.zsretail.store.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.store.cashmovement.model.CashMovement;
import com.digithink.zsretail.store.model.ScannedTicketPayment;

/**
 * 3.2.0, the differences, step B: the figures of many sessions at once (SessionFiguresService), the queries of the
 * Sessions list and of the page "Écarts de caisse": one query per kind for a group of sessions, never one per session.
 * A store repository (the scanned tickets, the cash movements and the POS machine links exist only on a store).
 */
public interface SessionFiguresRepository extends Repository<CashierSession, Long> {

	/**
	 * [session id, totalAmount] of the sales of these sessions, the statuses excluded (PENDING, CANCELLED; a sale with no
	 * status counts, as the details' Java filter), by id.
	 */
	@Query("SELECT s.cashierSession.id, s.totalAmount FROM SalesHeader s WHERE s.cashierSession.id IN :ids"
			+ " AND (s.status IS NULL OR s.status NOT IN :excludedStatuses) ORDER BY s.id")
	List<Object[]> saleAmounts(@Param("ids") Collection<Long> ids,
			@Param("excludedStatuses") Collection<TransactionStatus> excludedStatuses);

	/** [session id, returnType, totalReturnAmount] of the returns of these sessions, by id. */
	@Query("SELECT r.cashierSession.id, r.returnType, r.totalReturnAmount FROM ReturnHeader r"
			+ " WHERE r.cashierSession.id IN :ids ORDER BY r.id")
	List<Object[]> returnAmounts(@Param("ids") Collection<Long> ids);

	/** The scanned tickets of these sessions' sales (the statuses excluded), payment, method and sale fetched, by id. */
	@Query("SELECT t FROM ScannedTicketPayment t JOIN FETCH t.payment p JOIN FETCH p.paymentMethod"
			+ " JOIN FETCH p.salesHeader sh WHERE sh.cashierSession.id IN :ids AND sh.status NOT IN :excludedStatuses"
			+ " ORDER BY t.id")
	List<ScannedTicketPayment> scannedTickets(@Param("ids") Collection<Long> ids,
			@Param("excludedStatuses") Collection<TransactionStatus> excludedStatuses);

	/** The cash movements of these sessions, oldest first. */
	@Query("SELECT m FROM CashMovement m WHERE m.cashierSession.id IN :ids ORDER BY m.movedAt, m.id")
	List<CashMovement> cashMovements(@Param("ids") Collection<Long> ids);

	/**
	 * The sessions opened in [from, to] (both included, as the Sessions list's dates), of a cashier and of a POS machine
	 * when given (null: any), newest first.
	 */
	@Query("SELECT s FROM CashierSession s LEFT JOIN FETCH s.cashier WHERE s.openedAt >= :from AND s.openedAt <= :to"
			+ " AND (:cashierId IS NULL OR s.cashier.id = :cashierId)"
			+ " AND (:posMachineId IS NULL OR s.id IN (SELECT l.sessionId FROM SessionPosMachine l"
			+ " WHERE l.posMachine.id = :posMachineId))"
			+ " ORDER BY s.openedAt DESC, s.id DESC")
	List<CashierSession> openedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
			@Param("cashierId") Long cashierId, @Param("posMachineId") Long posMachineId);
}
