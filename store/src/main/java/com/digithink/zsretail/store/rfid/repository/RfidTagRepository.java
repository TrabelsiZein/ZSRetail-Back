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
	 * The Tags page: newest first, each filter applied when given (null: every value). epc: a part of the EPC, upper case.
	 */
	@Query("select t from RfidTag t where (:itemId is null or t.item.id = :itemId)"
			+ " and (:status is null or t.status = :status)"
			+ " and (:epc is null or t.epc like concat('%', :epc, '%')) order by t.id desc")
	Page<RfidTag> search(@Param("itemId") Long itemId, @Param("status") RfidTagStatus status, @Param("epc") String epc,
			Pageable page);
}
