package com.digithink.zsretail.core.repository;

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
}

