package com.digithink.zsretail.store.holink.repository;

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
import com.digithink.zsretail.store.holink.enumeration.StoreOrderStatus;
import com.digithink.zsretail.store.holink.model.StoreOrder;

/** 3.1.1: hol_order (docs/modules/store-orders.md). */
@Repository
public interface StoreOrderRepository extends _BaseRepository<StoreOrder, Long> {

	/** The orders of a status, oldest first: the draft is the first DRAFT (there is one at most). */
	List<StoreOrder> findByStatusOrderByIdAsc(StoreOrderStatus status);

	/** The order, its row locked until the end of the transaction: the draft's changes and its send wait for each other. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from StoreOrder o where o.id = :id")
	Optional<StoreOrder> findForUpdate(@Param("id") Long id);

	Optional<StoreOrder> findByNumber(String number);

	/**
	 * 3.1.1: the orders of these statuses holding a line of this ERP item number, newest first (the variants window's
	 * last order: the first one).
	 */
	@Query("select o from StoreOrder o where o.status in :statuses and exists (select l.id from StoreOrderLine l"
			+ " where l.order = o and l.navItemNo = :navItemNo) order by o.id desc")
	List<StoreOrder> findWithNavItem(@Param("navItemNo") String navItemNo,
			@Param("statuses") Collection<StoreOrderStatus> statuses, Pageable page);

	/** The highest number of a store and year (prefix with % after it, e.g. S01-2026-%); null for the first. */
	@Query("select max(o.number) from StoreOrder o where o.number like :prefix")
	String findLastNumber(@Param("prefix") String prefix);

	/**
	 * The filters of {@link #findPage}: status, creation, and the text: the order number, the head office's order number,
	 * or a line's item code, ERP item number or name (lower case, the pattern's wildcards escaped with a backslash).
	 */
	String PAGE_FILTERS = " where (:anyStatus = 1L or o.status = :status)"
			+ " and (:from is null or o.createdAt >= :from) and (:to is null or o.createdAt < :to)"
			+ " and (:anyText = 1L or lower(o.number) like :text escape '\\'"
			+ " or lower(o.navOrderNo) like :text escape '\\'"
			+ " or exists (select l.id from StoreOrderLine l where l.order = o"
			+ " and (lower(l.itemCode) like :text escape '\\' or lower(l.navItemNo) like :text escape '\\'"
			+ " or lower(l.description) like :text escape '\\')))";

	/**
	 * A page of orders, newest first. anyStatus / anyText 1: every status / text, else the given one; from / to:
	 * created_at at or after / before, null = not filtered; text: a LIKE pattern (ItemBarcodeService containsPattern).
	 */
	@Query(value = "select o from StoreOrder o" + PAGE_FILTERS + " order by o.id desc",
			countQuery = "select count(o) from StoreOrder o" + PAGE_FILTERS)
	Page<StoreOrder> findPage(@Param("anyStatus") long anyStatus, @Param("status") StoreOrderStatus status,
			@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("anyText") long anyText,
			@Param("text") String text, Pageable page);
}
