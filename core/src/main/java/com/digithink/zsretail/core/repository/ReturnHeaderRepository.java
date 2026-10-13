package com.digithink.zsretail.core.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

	/** 3.1.0, day journal: the returns made in [from, to), in time order. */
	List<ReturnHeader> findByReturnDateGreaterThanEqualAndReturnDateLessThanOrderByReturnDateAsc(LocalDateTime from,
			LocalDateTime to);

	/** 3.1.0, store days: {returnDate, totalReturnAmount} of the returns made in [from, to). */
	@Query("SELECT r.returnDate, r.totalReturnAmount FROM ReturnHeader r WHERE r.returnDate >= :from AND r.returnDate < :to")
	List<Object[]> findReturnTimesAndAmounts(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
