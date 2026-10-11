package com.digithink.zsretail.headoffice.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.model.HoSession;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoSessionRepository extends _BaseRepository<HoSession, Long> {

	Optional<HoSession> findByStoreIdAndSessionNumber(Long storeId, String sessionNumber);

	/**
	 * Task 2.5: the sessions list, by opening date; parameters as in HoTicketRepository.search (never null), the
	 * number filter on the session number.
	 */
	@Query("select s from HoSession s where (:storeId = 0L or s.store.id = :storeId)"
			+ " and s.openedAt >= :dateFrom and s.openedAt <= :dateTo and lower(s.sessionNumber) like :number"
			+ " and (:status = '' or s.status = :status)")
	Page<HoSession> search(@Param("storeId") long storeId, @Param("dateFrom") LocalDateTime dateFrom,
			@Param("dateTo") LocalDateTime dateTo, @Param("number") String number, @Param("status") String status,
			Pageable page);

	long countByStatus(String status);
}
