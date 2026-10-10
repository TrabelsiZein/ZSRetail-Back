package com.digithink.zsretail.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.model.enumeration.RecordOrigin;

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

	List<Item> findByType(ItemType type);

	List<Item> findByStockQuantityLessThan(BigDecimal quantity);

	Optional<Item> findByBarcode(String barcode);

	List<Item> findByItemFamily(ItemFamily itemFamily);

	List<Item> findByItemSubFamily(ItemSubFamily itemSubFamily);

	/** 2.2.2: the items of a family without a sub-family (the till's extra tile). */
	List<Item> findByItemFamilyAndItemSubFamilyIsNull(ItemFamily itemFamily);

	/**
	 * 2.2.2, the one grouped query of the POS grid's counts: the items the grid lists ({@code ItemService.listedInPos},
	 * the same conditions), in an active sub-family (or none), counted by the sub-family's family, the item's own
	 * family and the sub-family: rows {sub-family's family id, item's family id, sub-family id, count}. An item with a
	 * sub-family belongs to the sub-family's family (where the grid shows it); one without, to its own family.
	 */
	String POS_GRID_COUNTS = "select sff.id, f.id, sf.id, count(i) from Item i left join i.itemSubFamily sf"
			+ " left join sf.itemFamily sff left join i.itemFamily f"
			+ " where (i.active is null or i.active = true) and (i.showInPos is null or i.showInPos = true)"
			+ " and i.unitPrice > 0 and (sf.id is null or sf.active is null or sf.active = true)"
			+ " group by sff.id, f.id, sf.id";

	@Query(POS_GRID_COUNTS)
	List<Object[]> countPosGridItems();

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
}
