package com.digithink.zsretail.core.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;

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

	/** 3.3.0, communication step 5: {@link #findAllBySearchTerm} with the members' consent to receive messages. */
	@Query("SELECT m FROM LoyaltyMember m WHERE m.messageConsent = :consent AND " +
		   "(:search IS NULL OR :search = '' OR " +
		   "LOWER(m.cardNumber) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
		   "LOWER(m.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
		   "LOWER(m.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
		   "m.phone LIKE CONCAT('%', :search, '%'))")
	Page<LoyaltyMember> findAllBySearchTermAndConsent(@Param("search") String search, @Param("consent") Boolean consent,
			Pageable pageable);

	/** 3.3.0, communication step 5: the members (active or not) who accept to receive messages. */
	long countByMessageConsentTrue();

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

	/**
	 * 3.3.0, communication step 7: the members a campaign may reach, after {@code afterId}, by id: active, with the
	 * consent to receive messages, not a copy of a head office's register (origin {@code notOrigin}: HEAD_OFFICE; a store
	 * that went back to its own loyalty keeps such cards, never messaged by it). [id, cardNumber, firstName, lastName,
	 * phone, email, loyaltyPoints, createdBy] (no entity: no customer, no function loaded). The campaign's count and its
	 * messages both read this one query (CampaignAudience), the address rule and the one-per-address rule applied the
	 * same way after it.
	 */
	@Query("select m.id, m.cardNumber, m.firstName, m.lastName, m.phone, m.email, m.loyaltyPoints, m.createdBy"
			+ " from LoyaltyMember m where m.active = true and m.messageConsent = true"
			+ " and (m.origin is null or m.origin <> :notOrigin) and m.id > :afterId order by m.id")
	List<Object[]> findCampaignCandidates(@Param("notOrigin") RecordOrigin notOrigin, @Param("afterId") long afterId,
			Pageable page);

	/** Step 7: the active members without the consent (the window's "left out: no consent"), same members as above. */
	@Query("select count(m) from LoyaltyMember m where m.active = true and (m.messageConsent = false"
			+ " or m.messageConsent is null) and (m.origin is null or m.origin <> :notOrigin)")
	long countActiveWithoutConsent(@Param("notOrigin") RecordOrigin notOrigin);
}
