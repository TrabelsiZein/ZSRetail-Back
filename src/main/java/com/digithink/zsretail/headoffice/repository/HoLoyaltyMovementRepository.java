package com.digithink.zsretail.headoffice.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.model.HoLoyaltyMovement;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoLoyaltyMovementRepository extends _BaseRepository<HoLoyaltyMovement, Long> {

	Optional<HoLoyaltyMovement> findByStoreIdAndStoreKey(Long storeId, String storeKey);

	String OVERSPEND_FILTER = " from HoLoyaltyMovement m, LoyaltyMember l where l.id = m.memberId"
			+ " and m.overspendPoints > 0 and (:storeId = 0L or m.storeId = :storeId)"
			+ " and m.createdAt >= :dateFrom and m.createdAt <= :dateTo"
			+ " and (lower(m.cardNumber) like :search or lower(l.cardNumber) like :search"
			+ " or lower(concat(concat(l.firstName, ' '), l.lastName)) like :search"
			+ " or lower(coalesce(m.salesNumber, '')) like :search or lower(coalesce(m.returnNumber, '')) like :search)";

	/**
	 * Step 5, overspend report: [movement, member] of the movements whose removal could not be complete, newest first
	 * (head office reception time). storeId 0 = every store; search a lower-case LIKE pattern ("%" for all) on the
	 * card sent, the member's card and name, the sale and return numbers.
	 */
	@Query(value = "select m, l" + OVERSPEND_FILTER + " order by m.createdAt desc, m.id desc",
			countQuery = "select count(m)" + OVERSPEND_FILTER)
	Page<Object[]> findOverspends(@Param("storeId") long storeId, @Param("dateFrom") LocalDateTime dateFrom,
			@Param("dateTo") LocalDateTime dateTo, @Param("search") String search, Pageable page);

	/** Step 5: [count, sum of the missing points] of the overspends received in the period (home page). */
	@Query("select count(m), coalesce(sum(m.overspendPoints), 0) from HoLoyaltyMovement m"
			+ " where m.overspendPoints > 0 and m.createdAt >= :dateFrom and m.createdAt <= :dateTo")
	List<Object[]> overspendTotals(@Param("dateFrom") LocalDateTime dateFrom, @Param("dateTo") LocalDateTime dateTo);
}
