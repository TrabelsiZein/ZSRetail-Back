package com.digithink.zsretail.store.cashmovement.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.store.cashmovement.model.CashMovement;

/** 3.2.0, POS machines step 5: the cash movements; no update or delete is used (a movement is never changed). */
public interface CashMovementRepository extends JpaRepository<CashMovement, Long> {

	/** A session's movements, oldest first (its tickets, its expected cash, its details). */
	List<CashMovement> findByCashierSessionOrderByMovedAtAsc(CashierSession session);

	/** How many a session has: the rank of the next one's number. */
	long countByCashierSession(CashierSession session);

	/** The movements of a day, by their own time (the Journal's day). */
	List<CashMovement> findByMovedAtGreaterThanEqualAndMovedAtLessThanOrderByMovedAtAsc(LocalDateTime from,
			LocalDateTime to);
}
