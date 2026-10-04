package com.digithink.zsretail.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.enumeration.RecordOrigin;

public interface LoyaltyMemberRepository extends _BaseRepository<LoyaltyMember, Long> {

	Optional<LoyaltyMember> findByCardNumber(String cardNumber);

	/** Step 4: the members whose card number is one of these (head office copies down). */
	List<LoyaltyMember> findByCardNumberIn(Collection<String> cardNumbers);

	/**
	 * Step 4: card numbers matching a LIKE pattern whose escape character is '!' (the network numbering
	 * LYL-&lt;code&gt;-000001 takes the highest sequence of its prefix).
	 */
	@Query("select m.cardNumber from LoyaltyMember m where m.cardNumber like :pattern escape '!'")
	List<String> findCardNumbersLike(@Param("pattern") String pattern);

	/**
	 * Step 4: active members not from this origin (null counts as LOCAL): on a store whose loyalty is owned by the head
	 * office, the members made before the switch, set inactive at the pull.
	 */
	@Query("select m from LoyaltyMember m where m.active = true and (m.origin is null or m.origin <> :origin)")
	List<LoyaltyMember> findActiveNotFrom(@Param("origin") RecordOrigin origin);

	List<LoyaltyMember> findByCustomer(Customer customer);

	/** A list, not an Optional: members saved before the phone became unique may share a number. */
	List<LoyaltyMember> findByPhone(String phone);

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

	/**
	 * Highest number of today's LYL-000001 cards (loyalty LOCAL). Only cards whose part after LYL- is all digits count
	 * (L2 of step 4: a store back to LOCAL after holding network cards LYL-HO-000001, LYL-STORE-B-000001 failed with
	 * "Conversion failed"); the CASE keeps SQL Server from casting any other card. Same result for a store that only
	 * ever had LYL-000001 cards.
	 */
	@Query(value = "SELECT COALESCE(MAX(CASE WHEN LEN(card_number) > 4 AND SUBSTRING(card_number, 5, LEN(card_number) - 4)"
			+ " NOT LIKE '%[^0-9]%' THEN CAST(SUBSTRING(card_number, 5, LEN(card_number) - 4) AS INT) END), 0)"
			+ " FROM loyalty_member WHERE card_number LIKE 'LYL-%'", nativeQuery = true)
	Integer findMaxCardSequence();
}
