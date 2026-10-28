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

	/** The orders of this ERP number in this status, their rows locked until the end of the transaction (the deliveries). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from HoStoreOrder o where o.navOrderNo = :navOrderNo and o.status = :status order by o.id")
	List<HoStoreOrder> findForDelivery(@Param("navOrderNo") String navOrderNo, @Param("status") HoStoreOrderStatus status);

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
	 * The filters of {@link #findPage}: store, status, reception, and the text: the order number, the ERP order number, or
	 * a line's item code, ERP item number or name (lower case, the pattern's wildcards escaped with a backslash).
	 */
	String PAGE_FILTERS = " where (:anyStore = 1L or o.storeId = :storeId) and (:anyStatus = 1L or o.status = :status)"
			+ " and (:from is null or o.receivedAt >= :from) and (:to is null or o.receivedAt < :to)"
			+ " and (:anyText = 1L or lower(o.number) like :text escape '\\'"
			+ " or lower(o.navOrderNo) like :text escape '\\'"
			+ " or exists (select l.id from HoStoreOrderLine l where l.order = o"
			+ " and (lower(l.itemCode) like :text escape '\\' or lower(l.navItemNo) like :text escape '\\'"
			+ " or lower(l.description) like :text escape '\\')))";

	/**
	 * A page of orders, in the page's sort. anyStore / anyStatus / anyText 1: every store / status / text, else the given
	 * one; from / to: received_at at or after / before, null = not filtered; text: a LIKE pattern (ItemBarcodeService
	 * containsPattern).
	 */
	@Query(value = "select o from HoStoreOrder o" + PAGE_FILTERS,
			countQuery = "select count(o) from HoStoreOrder o" + PAGE_FILTERS)
	Page<HoStoreOrder> findPage(@Param("anyStore") long anyStore, @Param("storeId") Long storeId,
			@Param("anyStatus") long anyStatus, @Param("status") HoStoreOrderStatus status,
			@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("anyText") long anyText,
			@Param("text") String text, Pageable page);
}
