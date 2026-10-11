package com.digithink.zsretail.core.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.enumeration.SynchronizationStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;

@Repository
public interface ReturnHeaderRepository extends _BaseRepository<ReturnHeader, Long> {

	Optional<ReturnHeader> findByReturnNumber(String returnNumber);

	Optional<ReturnHeader> findByOriginalSalesHeader(SalesHeader salesHeader);

	List<ReturnHeader> findAllByOriginalSalesHeader(SalesHeader salesHeader);

	List<ReturnHeader> findByCashierSession(CashierSession cashierSession);

	// Query methods for synchronization
	List<ReturnHeader> findByStatusAndSynchronizationStatusNot(TransactionStatus status,
			SynchronizationStatus synchronizationStatus);
}
