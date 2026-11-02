package com.digithink.zsretail.core.repository;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.ReturnLine;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface ReturnLineRepository extends _BaseRepository<ReturnLine, Long> {
	
	List<ReturnLine> findByReturnHeader(ReturnHeader returnHeader);

	/** 3.1.0, day journal: the lines of several returns in one query (never called with an empty list). */
	List<ReturnLine> findByReturnHeaderIn(List<ReturnHeader> returnHeaders);
	
	void deleteByReturnHeader(ReturnHeader returnHeader);

	/**
	 * 3.1.2, the Journal's gains (store days): one row per line of the returns whose returnDate is in [from, to) (as the
	 * day journal): {return id, returnDate, totalReturnAmount, item code, item type, line TTC, line HT, quantity, unit
	 * cost}. A projection, no entity.
	 */
	@Query("SELECT r.id, r.returnDate, r.totalReturnAmount, i.itemCode, i.type, l.lineTotalIncludingVat, l.lineTotal,"
			+ " l.quantity, l.unitCost FROM ReturnLine l JOIN l.returnHeader r JOIN l.item i"
			+ " WHERE r.returnDate >= :from AND r.returnDate < :to")
	List<Object[]> findCostLinesOfReturnsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
