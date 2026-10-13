package com.digithink.zsretail.core.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.enumeration.SynchronizationStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;

public interface SalesHeaderRepository extends _BaseRepository<SalesHeader, Long> {

	Optional<SalesHeader> findBySalesNumber(String salesNumber);

	List<SalesHeader> findByStatus(TransactionStatus status);

	List<SalesHeader> findByCustomer(Customer customer);

	List<SalesHeader> findByCashierSession(CashierSession cashierSession);

	List<SalesHeader> findByCashierSessionAndStatus(CashierSession cashierSession, TransactionStatus status);

	/** Regular pending tickets only — excludes table-linked tickets (tableNumber IS NULL). */
	List<SalesHeader> findByCashierSessionAndStatusAndTableNumberIsNull(CashierSession cashierSession, TransactionStatus status);

	/** Table-linked pending tickets only — excludes regular pending tickets (tableNumber IS NOT NULL). */
	List<SalesHeader> findByCashierSessionAndStatusAndTableNumberIsNotNull(CashierSession cashierSession, TransactionStatus status);

	long countBySalesDateGreaterThanEqual(LocalDateTime date);
	
	List<SalesHeader> findBySalesDateBetween(LocalDateTime startDate, LocalDateTime endDate);
	
	List<SalesHeader> findBySalesDateBetweenAndStatus(LocalDateTime startDate, LocalDateTime endDate, TransactionStatus status);
	
	List<SalesHeader> findByStatusAndSynchronizationStatusNot(TransactionStatus status, SynchronizationStatus synchronizationStatus);

	/**
	 * 3.1.0, day journal: the finished tickets (status not in excluded) whose salesDate is in [from, to), as the sales
	 * reports select them, in time order.
	 */
	@Query("SELECT sh FROM SalesHeader sh WHERE sh.status NOT IN :excluded"
			+ " AND sh.salesDate >= :from AND sh.salesDate < :to ORDER BY sh.salesDate")
	List<SalesHeader> findFinishedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
			@Param("excluded") List<TransactionStatus> excluded);

	/** 3.1.0, store days: {salesDate, totalAmount} of the finished tickets whose salesDate is in [from, to). */
	@Query("SELECT sh.salesDate, sh.totalAmount FROM SalesHeader sh WHERE sh.status NOT IN :excluded"
			+ " AND sh.salesDate >= :from AND sh.salesDate < :to")
	List<Object[]> findFinishedTimesAndTotals(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
			@Param("excluded") List<TransactionStatus> excluded);

	/** 3.1.0, day journal: the tickets of a status (the cancelled ones) by their salesDate, in [from, to), in time order. */
	List<SalesHeader> findByStatusAndSalesDateGreaterThanEqualAndSalesDateLessThanOrderBySalesDateAsc(
			TransactionStatus status, LocalDateTime from, LocalDateTime to);

	/** 3.1.0, store days: the salesDate of the tickets of a status (the cancelled ones) in [from, to). */
	@Query("SELECT sh.salesDate FROM SalesHeader sh WHERE sh.status = :status AND sh.salesDate >= :from AND sh.salesDate < :to")
	List<LocalDateTime> findSalesDatesByStatusBetween(@Param("status") TransactionStatus status,
			@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
