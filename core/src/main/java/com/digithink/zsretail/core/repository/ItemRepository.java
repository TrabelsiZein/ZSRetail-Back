package com.digithink.zsretail.core.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;

public interface ItemRepository extends _BaseRepository<Item, Long> {

	/**
	 * Atomic increment/decrement of stock. Single DB update to avoid lost updates under concurrency.
	 * Treats NULL stock_quantity as 0.
	 * @param itemId item id
	 * @param delta positive to add, negative to subtract
	 * @return number of rows updated (1 or 0)
	 */
	@Modifying
	@Query(value = "UPDATE item SET stock_quantity = COALESCE(stock_quantity, 0) + CAST(:delta AS DECIMAL(18,3)) WHERE id = :itemId", nativeQuery = true)
	int addToStockQuantity(@Param("itemId") Long itemId, @Param("delta") BigDecimal delta);

	/**
	 * Atomic decrement only if current stock (or 0 if null) is >= quantity. Prevents negative stock.
	 * @param itemId item id
	 * @param quantity positive quantity to subtract
	 * @return number of rows updated (1 if sufficient stock, 0 otherwise)
	 */
	@Modifying
	@Query(value = "UPDATE item SET stock_quantity = COALESCE(stock_quantity, 0) - CAST(:quantity AS DECIMAL(18,3)) WHERE id = :itemId AND COALESCE(stock_quantity, 0) >= CAST(:quantity AS DECIMAL(18,3))", nativeQuery = true)
	int decrementStockQuantityIfSufficient(@Param("itemId") Long itemId, @Param("quantity") BigDecimal quantity);

	/**
	 * Unconditional decrement — allows stock to go negative.
	 * Used when ALLOW_NEGATIVE_STOCK=true in GeneralSetup.
	 * @param itemId item id
	 * @param quantity positive quantity to subtract
	 */
	@Modifying
	@Query(value = "UPDATE item SET stock_quantity = COALESCE(stock_quantity, 0) - CAST(:quantity AS DECIMAL(18,3)) WHERE id = :itemId", nativeQuery = true)
	void decrementStockQuantityUnconditional(@Param("itemId") Long itemId, @Param("quantity") BigDecimal quantity);

	/**
	 * Invoices from the ERP: the costs of an item received on an ERP invoice, by id: last direct cost = the gross unit
	 * price, last direct net cost and cost price = the net unit cost. Never the selling price (unit price) nor the stock:
	 * a bulk update, because the same reception changes the stock by native updates (saving the item entity would put
	 * back the stock it was loaded with).
	 */
	@Modifying(flushAutomatically = true)
	@Query("update Item x set x.lastDirectCost = :gross, x.lastDirectNetCost = :net, x.costPrice = :net,"
			+ " x.updatedBy = :by where x.id = :id")
	int updateCost(@Param("id") Long id, @Param("gross") Double gross, @Param("net") Double net, @Param("by") String by);

	Optional<Item> findByItemCode(String itemCode);

	Optional<Item> findByErpExternalId(String erpExternalId);

	/** 3.1.0 speed: the items of these ERP ids (one packet of the ERP import, at most 1,000 ids). */
	List<Item> findByErpExternalIdIn(Collection<String> erpExternalIds);

	List<Item> findByType(ItemType type);

	List<Item> findByStockQuantityLessThan(BigDecimal quantity);

	Optional<Item> findByBarcode(String barcode);

	List<Item> findByItemFamily(ItemFamily itemFamily);

	List<Item> findByItemSubFamily(ItemSubFamily itemSubFamily);

	/** 2.2.2: the items of a family without a sub-family (the till's extra tile). */
	List<Item> findByItemFamilyAndItemSubFamilyIsNull(ItemFamily itemFamily);

	/** 3.1.0 (ZEN): the items without famille and sous-famille that carry this catégorie code (the till's fall-through). */
	List<Item> findByCategoryCodeAndItemFamilyIsNullAndItemSubFamilyIsNull(String categoryCode);

	/** The conditions of {@code ItemService.listedInPos} on the item {@code i}: the items the POS grid lists. */
	String POS_LISTED = "(i.active is null or i.active = true) and (i.showInPos is null or i.showInPos = true)"
			+ " and i.unitPrice > 0";

	/**
	 * 2.2.2, the one grouped query of the POS grid's counts: the items the grid lists ({@code ItemService.listedInPos},
	 * the same conditions), in an active sub-family (or none), counted by the sub-family's family, the item's own
	 * family and the sub-family: rows {sub-family's family id, item's family id, sub-family id, count}. An item with a
	 * sub-family belongs to the sub-family's family (where the grid shows it); one without, to its own family.
	 */
	String POS_GRID_COUNTS = "select sff.id, f.id, sf.id, count(i) from Item i left join i.itemSubFamily sf"
			+ " left join sf.itemFamily sff left join i.itemFamily f"
			+ " where " + POS_LISTED + " and (sf.id is null or sf.active is null or sf.active = true)"
			+ " group by sff.id, f.id, sf.id";

	@Query(POS_GRID_COUNTS)
	List<Object[]> countPosGridItems();

	/**
	 * 3.1.0 (ZEN), the till's sections: the items the grid lists in an active sub-family that carry a section code,
	 * counted by sub-family and section code: rows {sub-family id, section code, count}. A section holds the items of its
	 * own sub-family that carry its code (PosCatalogueService).
	 */
	String POS_SECTION_COUNTS = "select sf.id, i.sectionCode, count(i) from Item i join i.itemSubFamily sf"
			+ " where " + POS_LISTED + " and (sf.active is null or sf.active = true) and i.sectionCode is not null"
			+ " group by sf.id, i.sectionCode";

	@Query(POS_SECTION_COUNTS)
	List<Object[]> countPosSectionItems();

	/**
	 * 3.1.0 (ZEN), the till's catégories: the items the grid lists with no famille and no sous-famille, counted by their
	 * own catégorie code: rows {catégorie code, count}. They fall through to their catégorie (no famille to open).
	 */
	String POS_CATEGORY_COUNTS = "select i.categoryCode, count(i) from Item i"
			+ " where " + POS_LISTED + " and i.itemFamily is null and i.itemSubFamily is null and i.categoryCode is not null"
			+ " group by i.categoryCode";

	@Query(POS_CATEGORY_COUNTS)
	List<Object[]> countPosCategoryItems();

	/** Head office plan, step 6: the items of these codes. */
	List<Item> findByItemCodeIn(Collection<String> itemCodes);

	/** Step 6: every item code (the startup backfill of the catalogue copies). */
	@Query("select i.itemCode from Item i")
	List<String> findAllCodes();

	/**
	 * Head office plan, step 6: the origin of one row (the column is not updatable through a save). Pending changes are
	 * flushed first. Returns the number of rows updated.
	 */
	@Modifying(flushAutomatically = true)
	@Query("update Item x set x.origin = :origin where x.id = :id")
	int setOrigin(@Param("id") Long id, @Param("origin") RecordOrigin origin);

	/** Step 6: the head office items that carry an own price of this store. */
	List<Item> findByOwnPriceTrue();

	/** Step 6: how many head office items carry an own price of this store. */
	long countByOwnPriceTrue();

	/** Step 6: the items whose old single barcode field holds this value (several may). */
	List<Item> findAllByBarcode(String barcode);

	/** Inventory count, at the import: [id, itemCode, type, stockQuantity] of every item, read once. */
	@Query("select i.id, i.itemCode, i.type, i.stockQuantity from Item i")
	List<Object[]> findInventorySnapshot();

	/** Inventory count, at the validation: [id, stockQuantity] of these items (at most 2,000 ids per call). */
	@Query("select i.id, i.stockQuantity from Item i where i.id in :ids")
	List<Object[]> findStockByIds(@Param("ids") Collection<Long> ids);

	/**
	 * Item variants (ZEN), step 11a: at least one item carries a NAV item number (GET /config navItems, the "NAV items"
	 * menu entry). One seek on ix_item_nav_item_no.
	 */
	boolean existsByNavItemNoIsNotNull();

	/**
	 * Promotions for variants, step 3: an item of this installation carries this ERP item number (a promotion received
	 * on it whose variants are not synced yet is only logged). One seek on ix_item_nav_item_no.
	 */
	boolean existsByNavItemNo(String navItemNo);

	/**
	 * Step 11a, page "NAV items": the items counted in a line and in the count of lines. search: lower case with % already
	 * around it (the NAV item number or the item's name), null = not filtered; barcodeItemIds: the items whose barcode
	 * matches the search, their NAV item numbers match too (never empty: {@link #NO_ITEM_IDS} for none); activeOnly: TRUE
	 * = the active variants only, null = every variant.
	 * <p>
	 * 3.1.1 (store orders): the level filters, each null = not filtered: categoryCode (the item's own catégorie, else its
	 * famille's, as the promotions match it), familyId, subFamilyId, sectionCode. The name and the classification are per
	 * NAV item (its variants share them) and a barcode matches through its NAV item number, so a line keeps all its
	 * variants in its count.
	 */
	String NAV_ITEMS_WHERE = " where i.navItemNo is not null"
			+ " and (:search is null or lower(i.navItemNo) like :search or lower(i.name) like :search"
			+ " or i.navItemNo in (select b.navItemNo from Item b where b.id in :barcodeItemIds))"
			+ " and (:activeOnly is null or i.active is null or i.active = true)"
			+ " and (:categoryCode is null or coalesce(i.categoryCode, f.categoryCode) = :categoryCode)"
			+ " and (:familyId is null or f.id = :familyId)"
			+ " and (:subFamilyId is null or sf.id = :subFamilyId)"
			+ " and (:sectionCode is null or i.sectionCode = :sectionCode)";

	/** The item ids of {@link #NAV_ITEMS_WHERE} when no barcode matches (an IN list is never empty). */
	List<Long> NO_ITEM_IDS = Collections.singletonList(-1L);

	String NAV_ITEMS_FROM = " from Item i left join i.itemFamily f left join i.itemSubFamily sf";

	/**
	 * Step 11a: one line per NAV item number: [NAV item number, name, family name, sub-family name, catégorie code,
	 * section code, number of variants, stock of the variants]. The ERP gives the name and the classification per NAV
	 * item, so its variants share them; min keeps one when they differ. 3.1.1: the order is the page's sort
	 * (NavItemService: by number, or by name then number).
	 */
	String NAV_ITEMS = "select i.navItemNo, min(i.name), min(f.name), min(sf.name), min(i.categoryCode),"
			+ " min(i.sectionCode), count(i), sum(i.stockQuantity)" + NAV_ITEMS_FROM + NAV_ITEMS_WHERE
			+ " group by i.navItemNo";

	String NAV_ITEMS_COUNT = "select count(distinct i.navItemNo)" + NAV_ITEMS_FROM + NAV_ITEMS_WHERE;

	@Query(value = NAV_ITEMS, countQuery = NAV_ITEMS_COUNT)
	Page<Object[]> findNavItems(@Param("search") String search, @Param("barcodeItemIds") Collection<Long> barcodeItemIds,
			@Param("activeOnly") Boolean activeOnly, @Param("categoryCode") String categoryCode,
			@Param("familyId") Long familyId, @Param("subFamilyId") Long subFamilyId,
			@Param("sectionCode") String sectionCode, Pageable page);

	/**
	 * 3.1.1 (store orders): the variants of these NAV item numbers (a page's, at most 200), activeOnly as
	 * {@link #NAV_ITEMS_WHERE}, each number's in the order of {@link #NAV_ITEM_VARIANTS}: its first is the line's price
	 * and image.
	 */
	@Query("select i from Item i where i.navItemNo in :navItemNos"
			+ " and (:activeOnly is null or i.active is null or i.active = true)"
			+ " order by i.navItemNo, case when i.sizeSortOrder is null then 1 else 0 end, i.sizeSortOrder, i.colorCode,"
			+ " i.choiceCode, i.itemCode")
	List<Item> findVariantsOfNavItems(@Param("navItemNos") Collection<String> navItemNos,
			@Param("activeOnly") Boolean activeOnly);

	/**
	 * Step 11a: the variants of one NAV item number, by size order (no order last), colour, choice, then item code.
	 * activeOnly as {@link #NAV_ITEMS_WHERE}.
	 */
	String NAV_ITEM_VARIANTS = "select i from Item i where i.navItemNo = :navItemNo"
			+ " and (:activeOnly is null or i.active is null or i.active = true)"
			+ " order by case when i.sizeSortOrder is null then 1 else 0 end, i.sizeSortOrder, i.colorCode,"
			+ " i.choiceCode, i.itemCode";

	@Query(NAV_ITEM_VARIANTS)
	List<Item> findNavItemVariants(@Param("navItemNo") String navItemNo, @Param("activeOnly") Boolean activeOnly);

	/**
	 * Promotions for variants, step 1b: [NAV item number, name] of these numbers (the name of a promotion target on a
	 * NAV item number); every variant counts. A seek on ix_item_nav_item_no on ZEN's French_CI_AS (plan checked).
	 */
	String NAV_ITEM_NAMES = "select i.navItemNo, min(i.name) from Item i where i.navItemNo in :navItemNos"
			+ " group by i.navItemNo";

	@Query(NAV_ITEM_NAMES)
	List<Object[]> findNavItemNames(@Param("navItemNos") Collection<String> navItemNos);
}
