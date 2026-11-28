package com.digithink.zsretail.core.convention.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.convention.model.ConventionCard;
import com.digithink.zsretail.core.repository._BaseRepository;

/**
 * 3.6.0, conventions step 1: the cards. The Cards tab's filters are Specifications (ConventionCards). Part B4: the
 * numbers the till already reads elsewhere (decision 6: an item code, an item barcode, a loyalty card number), as LIKE
 * patterns: "PREFIX-%" for a generation (one query per table whatever the count), the number itself for one card (a
 * number holds A-Z, 0-9 and "-" only: no LIKE character), upper-cased on both sides whatever the collation. Those
 * columns are varchar: the driver's nvarchar parameter reads them by a scan anyway (upper() costs nothing more), cheap
 * at a store's size and run once per generation, never at the till. The cards' own numbers are stored upper-cased:
 * their query seeks uk_convention_card_number.
 */
@Repository
public interface ConventionCardRepository extends _BaseRepository<ConventionCard, Long> {

	/** The number as stored (the service trims and upper-cases it first); unique in the whole installation. */
	Optional<ConventionCard> findByCardNumber(String cardNumber);

	/** Part B3: the convention has a card, of any status (its card settings are fixed, it cannot be deleted). */
	boolean existsByConventionId(Long conventionId);

	/**
	 * Part B3: [convention id, status, count] for these conventions, one GROUP BY over ix_convention_card_convention
	 * (convention_id, status): the list's active and blocked counts of a page in one query.
	 */
	@Query("select c.convention.id, c.status, count(c) from ConventionCard c where c.convention.id in :ids"
			+ " group by c.convention.id, c.status")
	List<Object[]> countByStatus(@Param("ids") Collection<Long> ids);

	/** Part B4: these cards of this convention (the others are not found). */
	List<ConventionCard> findByConventionIdAndIdIn(Long conventionId, Collection<Long> ids);

	Optional<ConventionCard> findByIdAndConventionId(Long id, Long conventionId);

	// ─── Part B4: the numbers already used, by LIKE pattern ──────────

	/** The cards of every convention. */
	@Query("select c.cardNumber from ConventionCard c where c.cardNumber like :pattern")
	List<String> cardNumbersLike(@Param("pattern") String pattern);

	@Query("select i.itemCode from Item i where upper(i.itemCode) like :pattern")
	List<String> itemCodesLike(@Param("pattern") String pattern);

	/** The item's own barcode column (read by the till's scan besides item_barcode). */
	@Query("select i.barcode from Item i where upper(i.barcode) like :pattern")
	List<String> itemOwnBarcodesLike(@Param("pattern") String pattern);

	@Query("select b.barcode from ItemBarcode b where upper(b.barcode) like :pattern")
	List<String> itemBarcodesLike(@Param("pattern") String pattern);

	/** The loyalty members (on a store whose loyalty is the head office's, the copies are written here too). */
	@Query("select m.cardNumber from LoyaltyMember m where upper(m.cardNumber) like :pattern")
	List<String> loyaltyCardsLike(@Param("pattern") String pattern);
}
