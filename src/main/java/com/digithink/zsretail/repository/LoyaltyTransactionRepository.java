package com.digithink.zsretail.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.LoyaltyProgram;
import com.digithink.zsretail.model.LoyaltyTransaction;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.enumeration.LoyaltyTransactionType;

public interface LoyaltyTransactionRepository extends _BaseRepository<LoyaltyTransaction, Long> {

	Page<LoyaltyTransaction> findByLoyaltyMemberOrderByCreatedAtDesc(LoyaltyMember member, Pageable pageable);

	List<LoyaltyTransaction> findByLoyaltyMemberOrderByCreatedAtDesc(LoyaltyMember member);

	Optional<LoyaltyTransaction> findTopByLoyaltyMemberAndSalesHeaderAndTypeOrderByCreatedAtDesc(
			LoyaltyMember member, SalesHeader salesHeader, LoyaltyTransactionType type);

	List<LoyaltyTransaction> findByLoyaltyMemberAndType(LoyaltyMember member, LoyaltyTransactionType type);

	long countByLoyaltyProgram(LoyaltyProgram program);

	/**
	 * Cross-member paginated transaction query with optional filters.
	 * All parameters are optional (null = no filter applied).
	 */
	@Query("SELECT t FROM LoyaltyTransaction t JOIN t.loyaltyMember m WHERE " +
		   "(:type IS NULL OR t.type = :type) AND " +
		   "(:dateFrom IS NULL OR t.createdAt >= :dateFrom) AND " +
		   "(:dateTo IS NULL OR t.createdAt <= :dateTo) AND " +
		   "(:memberId IS NULL OR m.id = :memberId) AND " +
		   "(:search IS NULL OR :search = '' OR " +
		   " LOWER(m.cardNumber) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
		   " LOWER(m.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
		   " LOWER(m.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
		   " m.phone LIKE CONCAT('%', :search, '%')) " +
		   "ORDER BY t.createdAt DESC")
	Page<LoyaltyTransaction> findAllFiltered(
			@Param("type") LoyaltyTransactionType type,
			@Param("dateFrom") LocalDateTime dateFrom,
			@Param("dateTo") LocalDateTime dateTo,
			@Param("memberId") Long memberId,
			@Param("search") String search,
			Pageable pageable);
}
