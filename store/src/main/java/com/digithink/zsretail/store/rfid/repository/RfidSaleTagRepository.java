package com.digithink.zsretail.store.rfid.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.rfid.model.RfidSaleTag;

/** RFID POS step: the tags of the tickets' lines; exists only with rfid.enabled=true. */
@ConditionalOnInstallation(Installation.RFID)
public interface RfidSaleTagRepository extends _BaseRepository<RfidSaleTag, Long> {

	/** The tags of a ticket. */
	List<RfidSaleTag> findBySalesHeader_Id(Long salesHeaderId);

	/** The tags of a completed ticket's paid line not yet put back in stock by a return. */
	List<RfidSaleTag> findBySalesLine_IdAndReturnLineIsNull(Long salesLineId);

	/**
	 * Among these EPCs, those held by a ticket of this status (PENDING: on a pending ticket), but the ticket of the id
	 * given (the pending ticket being completed or reopened in the cart; null: none).
	 */
	@Query("select r.epc from RfidSaleTag r where r.epc in :epcs and r.salesHeader.status = :status"
			+ " and (:exceptTicketId is null or r.salesHeader.id <> :exceptTicketId)")
	List<String> heldBy(@Param("epcs") Collection<String> epcs, @Param("status") TransactionStatus status,
			@Param("exceptTicketId") Long exceptTicketId);

	/**
	 * The lines of an item on a ticket: on a paid ticket its paid line and its free line (a "buy N get M" promotion),
	 * whose pieces all carry tags (a free piece is scanned or read, decision of 2026-10-12).
	 */
	@Query("select l from SalesLine l where l.salesHeader.id = :ticketId and l.item.id = :itemId")
	List<SalesLine> linesOf(@Param("ticketId") Long ticketId, @Param("itemId") Long itemId);

	/** How much of a sales line its returns took back, this return included (0 when none). */
	@Query("select coalesce(sum(r.quantity), 0) from ReturnLine r where r.originalSalesLine.id = :salesLineId")
	BigDecimal returnedQuantity(@Param("salesLineId") Long salesLineId);
}
