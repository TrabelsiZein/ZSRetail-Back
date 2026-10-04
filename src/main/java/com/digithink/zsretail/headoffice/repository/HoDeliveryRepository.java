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

import com.digithink.zsretail.headoffice.enumeration.DeliveryStatus;
import com.digithink.zsretail.headoffice.model.HoDelivery;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoDeliveryRepository extends _BaseRepository<HoDelivery, Long> {

	/**
	 * The BL, its row locked until the caller's transaction ends (SQL Server: UPDLOCK): two validations, or an edit and a
	 * validation, of the same BL run one after the other, and the second one sees the status the first one left.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from HoDelivery d where d.id = :id")
	Optional<HoDelivery> findForUpdate(@Param("id") Long id);

	/** The BL of this store with this number, locked like {@link #findForUpdate} (a store's confirmation). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from HoDelivery d where d.storeId = :storeId and d.number = :number")
	Optional<HoDelivery> findForUpdateByStoreAndNumber(@Param("storeId") Long storeId, @Param("number") String number);

	/** The numbered BLs of one store among these numbers (the copies down). */
	@Query("select d from HoDelivery d where d.storeId = :storeId and d.number in :numbers and d.status <> :draft")
	List<HoDelivery> findSent(@Param("storeId") Long storeId, @Param("numbers") Collection<String> numbers,
			@Param("draft") DeliveryStatus draft);

	/** Step 7B: the BLs of this store in this status and not invoiced, oldest first (the "to invoice" list). */
	@Query("select d from HoDelivery d where d.storeId = :storeId and d.status = :status and d.invoiceId is null"
			+ " order by d.id")
	List<HoDelivery> findToInvoice(@Param("storeId") Long storeId, @Param("status") DeliveryStatus status);

	/** [number, storeId] of every numbered BL (the startup backfill of the copies down). */
	@Query("select d.number, d.storeId from HoDelivery d where d.number is not null and d.status <> :draft")
	List<Object[]> findSentTargets(@Param("draft") DeliveryStatus draft);

	/**
	 * The BLs page, newest first. storeId 0 = every store; anyStatus 1 = every status (status then ignored); search
	 * (lower case, with % around) on the number and the note, null for none; document date within the bounds; difference
	 * 1 = only the BLs with a line received in another quantity than sent.
	 */
	@Query(value = "select d from HoDelivery d where (:storeId = 0L or d.storeId = :storeId)"
			+ " and (:anyStatus = 1L or d.status = :status)"
			+ " and (:search is null or lower(d.number) like :search or lower(d.note) like :search)"
			+ " and d.documentDate >= :dateFrom and d.documentDate <= :dateTo"
			+ " and (:difference = 0L or exists (select l.id from HoDeliveryLine l where l.delivery = d"
			+ " and l.quantityReceived is not null and l.quantityReceived <> l.quantitySent))"
			+ " order by d.id desc",
			countQuery = "select count(d) from HoDelivery d where (:storeId = 0L or d.storeId = :storeId)"
					+ " and (:anyStatus = 1L or d.status = :status)"
					+ " and (:search is null or lower(d.number) like :search or lower(d.note) like :search)"
					+ " and d.documentDate >= :dateFrom and d.documentDate <= :dateTo"
					+ " and (:difference = 0L or exists (select l.id from HoDeliveryLine l where l.delivery = d"
					+ " and l.quantityReceived is not null and l.quantityReceived <> l.quantitySent))")
	Page<HoDelivery> findPage(@Param("storeId") Long storeId, @Param("anyStatus") Long anyStatus,
			@Param("status") DeliveryStatus status, @Param("search") String search,
			@Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo,
			@Param("difference") Long difference, Pageable page);
}
