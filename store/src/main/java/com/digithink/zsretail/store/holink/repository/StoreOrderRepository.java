package com.digithink.zsretail.store.holink.repository;

import java.time.LocalDateTime;
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

	/** The highest number of a store and year (prefix with % after it, e.g. S01-2026-%); null for the first. */
	@Query("select max(o.number) from StoreOrder o where o.number like :prefix")
	String findLastNumber(@Param("prefix") String prefix);

	/**
	 * A page of orders, newest first. anyStatus 1: every status, else the given one; from / to: created_at at or after /
	 * before, null = not filtered.
	 */
	@Query(value = "select o from StoreOrder o where (:anyStatus = 1L or o.status = :status)"
			+ " and (:from is null or o.createdAt >= :from) and (:to is null or o.createdAt < :to) order by o.id desc",
			countQuery = "select count(o) from StoreOrder o where (:anyStatus = 1L or o.status = :status)"
					+ " and (:from is null or o.createdAt >= :from) and (:to is null or o.createdAt < :to)")
	Page<StoreOrder> findPage(@Param("anyStatus") long anyStatus, @Param("status") StoreOrderStatus status,
			@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable page);
}
