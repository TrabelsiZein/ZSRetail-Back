package com.digithink.zsretail.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.LoyaltyMember;

public interface LoyaltyMemberRepository extends _BaseRepository<LoyaltyMember, Long> {

	Optional<LoyaltyMember> findByCardNumber(String cardNumber);

	List<LoyaltyMember> findByCustomer(Customer customer);

	@Query("SELECT m FROM LoyaltyMember m WHERE " +
		   "LOWER(m.cardNumber) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
		   "LOWER(m.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
		   "LOWER(m.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
		   "m.phone LIKE CONCAT('%', :query, '%')")
	List<LoyaltyMember> searchMembers(@Param("query") String query);

	@Query("SELECT m FROM LoyaltyMember m WHERE " +
		   "(:search IS NULL OR :search = '' OR " +
		   "LOWER(m.cardNumber) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
		   "LOWER(m.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
		   "LOWER(m.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
		   "m.phone LIKE CONCAT('%', :search, '%'))")
	Page<LoyaltyMember> findAllBySearchTerm(@Param("search") String search, Pageable pageable);

	@Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(card_number, 5, LEN(card_number) - 4) AS INT)), 0) FROM loyalty_member WHERE card_number LIKE 'LYL-%'", nativeQuery = true)
	Integer findMaxCardSequence();
}
