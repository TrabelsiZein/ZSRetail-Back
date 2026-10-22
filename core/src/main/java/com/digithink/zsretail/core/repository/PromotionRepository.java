package com.digithink.zsretail.core.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.Promotion;
import com.digithink.zsretail.core.model.enumeration.PromotionScope;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;

public interface PromotionRepository extends _BaseRepository<Promotion, Long> {

	Optional<Promotion> findByCode(String code);

	/** Head office plan, task 3.3: the promotions with these codes (copies down). */
	List<Promotion> findByCodeIn(Collection<String> codes);

	/**
	 * Head office plan, task 3.3: the active promotions made at this store (origin null or LOCAL), set inactive by the
	 * pull job when promotions are owned by the head office.
	 */
	@Query("SELECT p FROM Promotion p WHERE p.active = true AND (p.origin IS NULL OR p.origin <> :headOffice)")
	List<Promotion> findActiveNotFrom(@Param("headOffice") RecordOrigin headOffice);

	/** Total number of sales lines + sales headers that reference this promotion. */
	@Query(value =
		"SELECT (SELECT COUNT(*) FROM sales_line WHERE promotion_id = :id) " +
		"     + (SELECT COUNT(*) FROM sales_header WHERE promotion_id = :id)",
		nativeQuery = true)
	long countUsages(@Param("id") Long id);

	/** All active promotions for a given scope that are valid on the given date, ordered by priority DESC */
	@Query("SELECT p FROM Promotion p WHERE p.active = true AND p.scope = :scope "
		+ "AND (p.startDate IS NULL OR p.startDate <= :today) "
		+ "AND (p.endDate   IS NULL OR p.endDate   >= :today) "
		+ "ORDER BY p.priority DESC")
	List<Promotion> findActiveByScope(
		@Param("scope") PromotionScope scope,
		@Param("today") LocalDate today);

	/** Active promotions targeting a specific item, valid today */
	@Query("SELECT p FROM Promotion p WHERE p.active = true AND p.item.id = :itemId "
		+ "AND (p.startDate IS NULL OR p.startDate <= :today) "
		+ "AND (p.endDate   IS NULL OR p.endDate   >= :today) "
		+ "ORDER BY p.priority DESC")
	List<Promotion> findActiveByItemId(
		@Param("itemId") Long itemId,
		@Param("today") LocalDate today);

	/** Active promotions targeting a specific item family, valid today */
	@Query("SELECT p FROM Promotion p WHERE p.active = true AND p.itemFamily.id = :familyId "
		+ "AND (p.startDate IS NULL OR p.startDate <= :today) "
		+ "AND (p.endDate   IS NULL OR p.endDate   >= :today) "
		+ "ORDER BY p.priority DESC")
	List<Promotion> findActiveByItemFamilyId(
		@Param("familyId") Long familyId,
		@Param("today") LocalDate today);

	/** Active promotions targeting a specific item subfamily, valid today */
	@Query("SELECT p FROM Promotion p WHERE p.active = true AND p.itemSubFamily.id = :subFamilyId "
		+ "AND (p.startDate IS NULL OR p.startDate <= :today) "
		+ "AND (p.endDate   IS NULL OR p.endDate   >= :today) "
		+ "ORDER BY p.priority DESC")
	List<Promotion> findActiveByItemSubFamilyId(
		@Param("subFamilyId") Long subFamilyId,
		@Param("today") LocalDate today);

	/**
	 * 3.1.0 (ZEN): active SECTION promotions whose section has this code (the item's section code), valid today. The
	 * scope filter keeps a promotion of another scope out even if it ever kept a section.
	 */
	@Query("SELECT p FROM Promotion p WHERE p.active = true "
		+ "AND p.scope = com.digithink.zsretail.core.model.enumeration.PromotionScope.SECTION "
		+ "AND p.itemSection.code = :code "
		+ "AND (p.startDate IS NULL OR p.startDate <= :today) "
		+ "AND (p.endDate   IS NULL OR p.endDate   >= :today) "
		+ "ORDER BY p.priority DESC")
	List<Promotion> findActiveBySectionCode(
		@Param("code") String code,
		@Param("today") LocalDate today);

	/**
	 * 3.1.0 (ZEN): active CATEGORY promotions whose catégorie has this code (the item's catégorie code, else its
	 * family's), valid today. Scope filter as {@link #findActiveBySectionCode}.
	 */
	@Query("SELECT p FROM Promotion p WHERE p.active = true "
		+ "AND p.scope = com.digithink.zsretail.core.model.enumeration.PromotionScope.CATEGORY "
		+ "AND p.itemCategory.code = :code "
		+ "AND (p.startDate IS NULL OR p.startDate <= :today) "
		+ "AND (p.endDate   IS NULL OR p.endDate   >= :today) "
		+ "ORDER BY p.priority DESC")
	List<Promotion> findActiveByCategoryCode(
		@Param("code") String code,
		@Param("today") LocalDate today);

	/**
	 * Active ITEM_GROUP promotions whose item group contains the given item, valid today.
	 * The scope filter guarantees promotions of other scopes are never returned,
	 * even if stale promotion_group_item rows were ever left behind.
	 */
	@Query("SELECT p FROM Promotion p JOIN p.groupItems gi WHERE p.active = true "
		+ "AND p.scope = com.digithink.zsretail.core.model.enumeration.PromotionScope.ITEM_GROUP "
		+ "AND gi.id = :itemId "
		+ "AND (p.startDate IS NULL OR p.startDate <= :today) "
		+ "AND (p.endDate   IS NULL OR p.endDate   >= :today) "
		+ "ORDER BY p.priority DESC")
	List<Promotion> findActiveByGroupItemId(
		@Param("itemId") Long itemId,
		@Param("today") LocalDate today);

	/**
	 * Active cross-product promotions (benefit lands on getItem instead of the
	 * purchased product), valid today. Evaluated only by the cart pass.
	 */
	@Query("SELECT p FROM Promotion p WHERE p.active = true AND p.getItem IS NOT NULL "
		+ "AND (p.startDate IS NULL OR p.startDate <= :today) "
		+ "AND (p.endDate   IS NULL OR p.endDate   >= :today) "
		+ "ORDER BY p.priority DESC")
	List<Promotion> findActiveCrossProductPromotions(@Param("today") LocalDate today);
}
