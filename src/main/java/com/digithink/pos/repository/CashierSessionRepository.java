package com.digithink.pos.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import javax.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.pos.model.CashierSession;
import com.digithink.pos.model.UserAccount;
import com.digithink.pos.model.enumeration.SessionStatus;
import com.digithink.pos.model.enumeration.SynchronizationStatus;

public interface CashierSessionRepository extends _BaseRepository<CashierSession, Long> {

	Optional<CashierSession> findBySessionNumber(String sessionNumber);

	/**
	 * Load a session with a pessimistic write lock. Used to serialize all
	 * PaymentHeader/PaymentLine mutations for the session (post-sale async creation
	 * vs. admin payment-method change vs. concurrent edits).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM CashierSession s WHERE s.id = :id")
	Optional<CashierSession> findByIdForUpdate(@Param("id") Long id);

	List<CashierSession> findByCashier(UserAccount cashier);

	List<CashierSession> findByStatus(SessionStatus status);

	Optional<CashierSession> findByCashierAndStatus(UserAccount cashier, SessionStatus status);

	List<CashierSession> findByVerifiedBy(UserAccount user);
	
	long countByOpenedAtGreaterThanEqual(LocalDateTime date);

	// Query methods for synchronization
	// Find sessions that are CLOSED or TERMINATED and not totally synched
	List<CashierSession> findByStatusInAndSynchronizationStatusNot(
			java.util.List<SessionStatus> statuses, SynchronizationStatus synchronizationStatus);
}

