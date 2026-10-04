package com.digithink.zsretail.repository;

import java.time.LocalDateTime;
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
	@Query(value = "UPDATE item SET stock_quantity = COALESCE(stock_quantity, 0) + :delta WHERE id = :itemId", nativeQuery = true)
	int addToStockQuantity(@Param("itemId") Long itemId, @Param("delta") int delta);

	/**
	 * Atomic decrement only if current stock (or 0 if null) is >= quantity. Prevents negative stock.
	 * @param itemId item id
	 * @param quantity positive quantity to subtract
	 * @return number of rows updated (1 if sufficient stock, 0 otherwise)
	 */
	@Modifying
	@Query(value = "UPDATE item SET stock_quantity = COALESCE(stock_quantity, 0) - :quantity WHERE id = :itemId AND COALESCE(stock_quantity, 0) >= :quantity", nativeQuery = true)
	int decrementStockQuantityIfSufficient(@Param("itemId") Long itemId, @Param("quantity") int quantity);

	/**
	 * Unconditional decrement — allows stock to go negative.
	 * Used when ALLOW_NEGATIVE_STOCK=true in GeneralSetup.
	 * @param itemId item id
	 * @param quantity positive quantity to subtract
	 */
	@Modifying
	@Query(value = "UPDATE item SET stock_quantity = COALESCE(stock_quantity, 0) - :quantity WHERE id = :itemId", nativeQuery = true)
	void decrementStockQuantityUnconditional(@Param("itemId") Long itemId, @Param("quantity") int quantity);

	Optional<Item> findByItemCode(String itemCode);

	Optional<Item> findByErpExternalId(String erpExternalId);

	List<Item> findByType(ItemType type);

	List<Item> findByStockQuantityLessThan(Integer quantity);

	Optional<Item> findByBarcode(String barcode);

	List<Item> findByItemFamily(ItemFamily itemFamily);

	List<Item> findByItemSubFamily(ItemSubFamily itemSubFamily);

	/** Franchise: returns all items (active and inactive) modified after the given datetime. */
	List<Item> findByUpdatedAtAfter(LocalDateTime updatedAt);

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
}
