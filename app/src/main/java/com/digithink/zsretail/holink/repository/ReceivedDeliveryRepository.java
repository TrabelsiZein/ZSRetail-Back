package com.digithink.zsretail.holink.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import javax.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.holink.enumeration.ReceivedDeliveryStatus;
import com.digithink.zsretail.holink.enumeration.ReceivedLineType;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.model.ReceivedDelivery;
import com.digithink.zsretail.repository._BaseRepository;

public interface ReceivedDeliveryRepository extends _BaseRepository<ReceivedDelivery, Long> {

	Optional<ReceivedDelivery> findByNumber(String number);

	/** The BL, its row locked until the caller's transaction ends: two confirmations run one after the other. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from ReceivedDelivery d where d.id = :id")
	Optional<ReceivedDelivery> findForUpdate(@Param("id") Long id);

	/** The page of the reception screen, newest first; anyStatus 1 = every status. */
	@Query(value = "select d from ReceivedDelivery d where (:anyStatus = 1L or d.status = :status) order by d.id desc",
			countQuery = "select count(d) from ReceivedDelivery d where (:anyStatus = 1L or d.status = :status)")
	Page<ReceivedDelivery> findPage(@Param("anyStatus") Long anyStatus, @Param("status") ReceivedDeliveryStatus status,
			Pageable page);

	/** Confirmations to send: fewest attempts first, then the oldest. */
	@Query("select d from ReceivedDelivery d where d.pushStatus in :statuses order by d.attempts, d.id")
	List<ReceivedDelivery> findPushQueue(@Param("statuses") Collection<SalesCopyStatus> statuses, Pageable page);

	/** [push status, count] of the confirmed BLs. */
	@Query("select d.pushStatus, count(d) from ReceivedDelivery d where d.pushStatus is not null group by d.pushStatus")
	List<Object[]> countByPushStatus();

	/** [status, count] of the BLs. */
	@Query("select d.status, count(d) from ReceivedDelivery d group by d.status")
	List<Object[]> countByStatus();

	/**
	 * Ids of the documents in this status with an item line whose item is missing here (an OTHER line of an ERP invoice
	 * has no item and never waits for one).
	 */
	@Query("select distinct l.delivery.id from ReceivedDeliveryLine l where l.itemId is null and l.delivery.status = :status"
			+ " and (l.lineType is null or l.lineType <> :other)")
	List<Long> findIdsWithMissingItem(@Param("status") ReceivedDeliveryStatus status,
			@Param("other") ReceivedLineType other);

	/** Ids of the confirmed BLs with a line whose stock in is in this state (false: still waiting). */
	@Query("select distinct l.delivery.id from ReceivedDeliveryLine l where l.stockApplied = :applied")
	List<Long> findIdsWithStock(@Param("applied") Boolean applied);

	/** How many confirmed lines still wait for their item before their stock goes in. */
	@Query("select count(l) from ReceivedDeliveryLine l where l.stockApplied = :applied")
	long countLinesWithStock(@Param("applied") Boolean applied);
}
