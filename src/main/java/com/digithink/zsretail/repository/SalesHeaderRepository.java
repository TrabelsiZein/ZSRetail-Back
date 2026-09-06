package com.digithink.zsretail.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.enumeration.SynchronizationStatus;
import com.digithink.zsretail.model.enumeration.TransactionStatus;

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
}
