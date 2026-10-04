package com.digithink.zsretail.headoffice.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import javax.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.model.HoSupplyInvoice;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoSupplyInvoiceRepository extends _BaseRepository<HoSupplyInvoice, Long> {

	/** The invoice, its row locked until the caller's transaction ends (paid or unpaid). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from HoSupplyInvoice i where i.id = :id")
	Optional<HoSupplyInvoice> findForUpdate(@Param("id") Long id);

	/** The last invoice issued (latest date, then latest id): a new one may not be dated before it. */
	Optional<HoSupplyInvoice> findFirstByOrderByInvoiceDateDescIdDesc();

	/** The invoices of one store among these numbers (the copies down). */
	@Query("select i from HoSupplyInvoice i where i.storeId = :storeId and i.invoiceNumber in :numbers")
	List<HoSupplyInvoice> findOfStore(@Param("storeId") Long storeId, @Param("numbers") Collection<String> numbers);

	/** [invoiceNumber, storeId] of every invoice (the startup backfill of the copies down). */
	@Query("select i.invoiceNumber, i.storeId from HoSupplyInvoice i")
	List<Object[]> findTargets();

	/**
	 * The invoices page, newest first. storeId 0 = every store; anyPaid 1 = paid or not (paid then ignored); invoice date
	 * within the bounds.
	 */
	@Query(value = "select i from HoSupplyInvoice i where (:storeId = 0L or i.storeId = :storeId)"
			+ " and (:anyPaid = 1L or i.paid = :paid) and i.invoiceDate >= :dateFrom and i.invoiceDate <= :dateTo"
			+ " order by i.id desc",
			countQuery = "select count(i) from HoSupplyInvoice i where (:storeId = 0L or i.storeId = :storeId)"
					+ " and (:anyPaid = 1L or i.paid = :paid) and i.invoiceDate >= :dateFrom and i.invoiceDate <= :dateTo")
	Page<HoSupplyInvoice> findPage(@Param("storeId") Long storeId, @Param("anyPaid") Long anyPaid,
			@Param("paid") Boolean paid, @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo,
			Pageable page);

	/** [storeId, paid, count, sum of totalAmount] per store and paid flag (what each store owes). */
	@Query("select i.storeId, i.paid, count(i), sum(i.totalAmount) from HoSupplyInvoice i group by i.storeId, i.paid")
	List<Object[]> balances();
}
