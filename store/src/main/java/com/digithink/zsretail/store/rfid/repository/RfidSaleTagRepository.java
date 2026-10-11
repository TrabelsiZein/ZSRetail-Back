package com.digithink.zsretail.store.rfid.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
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
}
