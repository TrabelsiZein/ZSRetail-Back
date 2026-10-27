package com.digithink.zsretail.headoffice.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import javax.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.headoffice.enumeration.HoStoreOrderStatus;
import com.digithink.zsretail.headoffice.model.HoStoreOrder;

/** 3.1.1: ho_store_order (docs/modules/store-orders.md). */
@Repository
public interface HoStoreOrderRepository extends _BaseRepository<HoStoreOrder, Long> {

	Optional<HoStoreOrder> findByStoreIdAndNumber(Long storeId, String number);

	/** The order, its row locked until the end of the transaction (the job, "retry now" and the admin actions). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from HoStoreOrder o where o.id = :id")
	Optional<HoStoreOrder> findForUpdate(@Param("id") Long id);

	/** The ids of the orders of these statuses, oldest first (the job's run). */
	@Query("select o.id from HoStoreOrder o where o.status in :statuses order by o.id")
	List<Long> findIdsByStatusIn(@Param("statuses") Collection<HoStoreOrderStatus> statuses, Pageable page);

	/** The orders of a store among these numbers, of these statuses (the copies down). */
	@Query("select o from HoStoreOrder o where o.storeId = :storeId and o.number in :numbers and o.status in :statuses")
	List<HoStoreOrder> findForStore(@Param("storeId") Long storeId, @Param("numbers") Collection<String> numbers,
			@Param("statuses") Collection<HoStoreOrderStatus> statuses);

	/** [number, store id] of the orders of these statuses (the startup backfill of the copies down). */
	@Query("select o.number, o.storeId from HoStoreOrder o where o.status in :statuses")
	List<Object[]> findTargets(@Param("statuses") Collection<HoStoreOrderStatus> statuses);

	/**
	 * A page of orders, in the page's sort. anyStore / anyStatus 1: every store / status, else the given one; from / to:
	 * received_at at or after / before, null = not filtered.
	 */
	@Query(value = "select o from HoStoreOrder o where (:anyStore = 1 or o.storeId = :storeId)"
			+ " and (:anyStatus = 1 or o.status = :status)"
			+ " and (:from is null or o.receivedAt >= :from) and (:to is null or o.receivedAt < :to)",
			countQuery = "select count(o) from HoStoreOrder o where (:anyStore = 1 or o.storeId = :storeId)"
					+ " and (:anyStatus = 1 or o.status = :status)"
					+ " and (:from is null or o.receivedAt >= :from) and (:to is null or o.receivedAt < :to)")
	Page<HoStoreOrder> findPage(@Param("anyStore") long anyStore, @Param("storeId") Long storeId,
			@Param("anyStatus") long anyStatus, @Param("status") HoStoreOrderStatus status,
			@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable page);
}
