package com.digithink.zsretail.store.rfid.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.rfid.model.RfidTag;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;

/** RFID tags step: the store's RFID tags; exists only with rfid.enabled=true. */
@ConditionalOnInstallation(Installation.RFID)
public interface RfidTagRepository extends _BaseRepository<RfidTag, Long> {

	/** The tag of this EPC (normalised: trimmed, upper case). */
	Optional<RfidTag> findByEpc(String epc);

	/** The tags of these EPCs (normalised). */
	List<RfidTag> findByEpcIn(Collection<String> epcs);

	/** How many tags this item has, whatever their status. */
	long countByItem_Id(Long itemId);

	/**
	 * The Tags page: newest first, each filter applied when given (null: every value), as ItemBarcodeRepository does.
	 * epcPattern: a ready LIKE pattern ('%E200%'), built in Java: no concat of a parameter in the query (SQL Server binds
	 * a null String as varbinary, and '%' + varbinary fails: Msg 402, found on the Tags page, 2026-10-11).
	 */
	@Query("select t from RfidTag t where (:itemId is null or t.item.id = :itemId)"
			+ " and (:status is null or t.status = :status)"
			+ " and (:epcPattern is null or t.epc like :epcPattern) order by t.id desc")
	Page<RfidTag> search(@Param("itemId") Long itemId, @Param("status") RfidTagStatus status,
			@Param("epcPattern") String epcPattern, Pageable page);
}
