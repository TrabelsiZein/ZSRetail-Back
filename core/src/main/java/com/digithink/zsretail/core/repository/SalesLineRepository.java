package com.digithink.zsretail.core.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;

public interface SalesLineRepository extends _BaseRepository<SalesLine, Long> {

	List<SalesLine> findBySalesHeader(SalesHeader salesHeader);

	/** 3.1.0, day journal: the lines of several tickets in one query (never called with an empty list). */
	List<SalesLine> findBySalesHeaderIn(List<SalesHeader> salesHeaders);

	List<SalesLine> findByItem(Item item);

	void deleteBySalesHeader(SalesHeader salesHeader);

	/** 3.1.0: the lines of a session's tickets in one query, the tickets of the given statuses left out (ticket X / Z). */
	@Query("SELECT l FROM SalesLine l JOIN l.salesHeader sh WHERE sh.cashierSession = :session AND sh.status NOT IN :excludedStatuses")
	List<SalesLine> findByCashierSession(@Param("session") CashierSession session, @Param("excludedStatuses") List<TransactionStatus> excludedStatuses);

	/**
	 * 3.1.2, the Journal's gains (store days): one row per line of the finished tickets whose salesDate is in [from, to),
	 * the filter of findFinishedBetween: {ticket id, salesDate, totalAmount, item code, item type, line TTC, line HT,
	 * VAT %, quantity, unit cost}. A projection, no entity (a month at a time).
	 */
	@Query("SELECT sh.id, sh.salesDate, sh.totalAmount, i.itemCode, i.type, l.lineTotalIncludingVat, l.lineTotal,"
			+ " l.vatPercent, l.quantity, l.unitCost FROM SalesLine l JOIN l.salesHeader sh JOIN l.item i"
			+ " WHERE sh.status NOT IN :excluded AND sh.salesDate >= :from AND sh.salesDate < :to")
	List<Object[]> findCostLinesOfFinishedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
			@Param("excluded") List<TransactionStatus> excluded);
}
