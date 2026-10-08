package com.digithink.zsretail.headoffice.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import javax.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceMapping;
import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceStatus;
import com.digithink.zsretail.headoffice.model.HoErpInvoice;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoErpInvoiceRepository extends _BaseRepository<HoErpInvoice, Long> {

	boolean existsByBcNumber(String bcNumber);

	/** [year_prefix, highest bc_number] per year prefix: where each year's read goes on. */
	@Query("select i.yearPrefix, max(i.bcNumber) from HoErpInvoice i where i.yearPrefix is not null group by i.yearPrefix")
	List<Object[]> findHighestByYear();

	/** The invoices still without a store and not held, by number (the second step of a run). */
	@Query("select i.id from HoErpInvoice i where i.storeId is null and i.held = false order by i.bcNumber")
	List<Long> findIdsToAssign();

	/** Step (c): the invoices of this store among these numbers in these statuses, never a held one (the copies down). */
	@Query("select i from HoErpInvoice i where i.storeId = :storeId and i.bcNumber in :numbers and i.status in :statuses"
			+ " and i.held = false")
	List<HoErpInvoice> findForStore(@Param("storeId") Long storeId, @Param("numbers") Collection<String> numbers,
			@Param("statuses") Collection<ErpInvoiceStatus> statuses);

	/** Step (c): [bc_number, store_id] of every invoice given to a store and not held, by number (the backfill). */
	@Query("select i.bcNumber, i.storeId from HoErpInvoice i where i.storeId is not null and i.held = false"
			+ " order by i.bcNumber")
	List<Object[]> findAssignedTargets();

	/** The invoice, its row locked until the caller's transaction ends (an assignment, a confirmation). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from HoErpInvoice i where i.id = :id")
	Optional<HoErpInvoice> findForUpdate(@Param("id") Long id);

	/** The invoice of this store with this number, locked like {@link #findForUpdate} (a store's confirmation). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from HoErpInvoice i where i.storeId = :storeId and i.bcNumber = :number")
	Optional<HoErpInvoice> findForUpdateByStoreAndNumber(@Param("storeId") Long storeId, @Param("number") String number);

	/**
	 * The invoices page, newest number first. storeId 0 = every store; anyStatus 1 = every status; anyMapping 1 = every
	 * mapping, else that one (a held invoice has none); held 0 = all, 1 = held only, 2 = not held; withWarnings 1 = only
	 * those with warnings, 0 = all; withDifference 1 = only those received with a difference, 0 = all; search (lower
	 * case, with % around) on the number, the customer number and name, null for none.
	 */
	@Query(value = "select i from HoErpInvoice i where (:storeId = 0L or i.storeId = :storeId)"
			+ " and (:anyStatus = 1L or i.status = :status)"
			+ " and (:anyMapping = 1L or i.mappingStatus = :mapping)"
			+ " and (:held = 0L or (:held = 1L and i.held = true) or (:held = 2L and i.held = false))"
			+ " and (:withWarnings = 0L or (i.warnings is not null and i.warnings <> ''))"
			+ " and (:withDifference = 0L or i.difference = true)"
			+ " and (:search is null or lower(i.bcNumber) like :search or lower(i.customerNo) like :search"
			+ " or lower(i.customerName) like :search)"
			+ " order by i.bcNumber desc",
			countQuery = "select count(i) from HoErpInvoice i where (:storeId = 0L or i.storeId = :storeId)"
					+ " and (:anyStatus = 1L or i.status = :status)"
					+ " and (:anyMapping = 1L or i.mappingStatus = :mapping)"
					+ " and (:held = 0L or (:held = 1L and i.held = true) or (:held = 2L and i.held = false))"
					+ " and (:withWarnings = 0L or (i.warnings is not null and i.warnings <> ''))"
					+ " and (:withDifference = 0L or i.difference = true)"
					+ " and (:search is null or lower(i.bcNumber) like :search or lower(i.customerNo) like :search"
					+ " or lower(i.customerName) like :search)")
	Page<HoErpInvoice> findPage(@Param("storeId") Long storeId, @Param("anyStatus") Long anyStatus,
			@Param("status") ErpInvoiceStatus status, @Param("anyMapping") Long anyMapping,
			@Param("mapping") ErpInvoiceMapping mapping, @Param("held") Long held, @Param("withWarnings") Long withWarnings,
			@Param("withDifference") Long withDifference, @Param("search") String search, Pageable page);
}
