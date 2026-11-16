package com.digithink.zsretail.store.cashdifference;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;

/**
 * 3.2.0, the differences, step C: the block "À surveiller" of the page "Écarts de caisse", for a group of sessions, one
 * grouped query per kind (CashDifferencesService reads them by chunks of ids, as SessionFiguresService). The tickets,
 * the returns and the cash movements come from SessionFiguresService (the Sessions list's figures).
 */
public interface CashWatchRepository extends Repository<CashierSession, Long> {

	/** [session id, count, sum of totalAmount] of the cancelled tickets (status CANCELLED, the Journal's CANCELLED). */
	@Query("SELECT s.cashierSession.id, COUNT(s), SUM(s.totalAmount) FROM SalesHeader s"
			+ " WHERE s.cashierSession.id IN :ids AND s.status = :cancelled GROUP BY s.cashierSession.id")
	List<Object[]> cancelledTickets(@Param("ids") Collection<Long> ids, @Param("cancelled") TransactionStatus cancelled);

	/**
	 * [sale id, session id, discountAmount, discountSource, lineTotalIncludingVat, discountPercentage,
	 * unitPriceIncludingVat, quantity, item code] of the lines with a discount of the finished tickets (statuses
	 * excluded as the tickets X / Z: PENDING, CANCELLED); a free-item line (100 %, total 0) has its discount in its price.
	 * The value of each is SessionReportService.ticketXzLineDiscount.
	 */
	@Query("SELECT sh.id, sh.cashierSession.id, l.discountAmount, l.discountSource, l.lineTotalIncludingVat,"
			+ " l.discountPercentage, l.unitPriceIncludingVat, l.quantity, it.itemCode FROM SalesLine l"
			+ " JOIN l.salesHeader sh LEFT JOIN l.item it WHERE sh.cashierSession.id IN :ids"
			+ " AND sh.status NOT IN :excludedStatuses AND (l.discountAmount <> 0 OR l.discountPercentage = 100)")
	List<Object[]> discountedLines(@Param("ids") Collection<Long> ids,
			@Param("excludedStatuses") Collection<TransactionStatus> excludedStatuses);

	/** [sale id, session id, discountAmount, discountSource] of the finished tickets with a ticket discount. */
	@Query("SELECT s.id, s.cashierSession.id, s.discountAmount, s.discountSource FROM SalesHeader s"
			+ " WHERE s.cashierSession.id IN :ids AND s.status NOT IN :excludedStatuses AND s.discountAmount <> 0")
	List<Object[]> discountedTickets(@Param("ids") Collection<Long> ids,
			@Param("excludedStatuses") Collection<TransactionStatus> excludedStatuses);

	/**
	 * [session id, count] of the refused till authorisations (BadgeScanLog success false: a badge scan refused; a role
	 * without the right is never logged, the till asks for a badge), by the session the scanning user had open.
	 */
	@Query("SELECT b.sessionId, COUNT(b) FROM BadgeScanLog b WHERE b.sessionId IN :ids AND b.success = false"
			+ " GROUP BY b.sessionId")
	List<Object[]> refusedAuthorisations(@Param("ids") Collection<Long> ids);
}
