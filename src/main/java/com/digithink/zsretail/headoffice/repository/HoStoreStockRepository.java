package com.digithink.zsretail.headoffice.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.model.HoStoreStock;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoStoreStockRepository extends _BaseRepository<HoStoreStock, Long> {

	List<HoStoreStock> findByStoreIdAndItemCodeIn(Long storeId, Collection<String> itemCodes);

	/** The rows of these item codes, for one store (storeId) or every store (0). */
	@Query("select s from HoStoreStock s where s.itemCode in :codes and (:storeId = 0L or s.storeId = :storeId)")
	List<HoStoreStock> findByCodes(@Param("codes") Collection<String> codes, @Param("storeId") Long storeId);

	/** [storeId, last received_at] per store. */
	@Query("select s.storeId, max(s.receivedAt) from HoStoreStock s group by s.storeId")
	List<Object[]> lastReceivedByStore();

	/**
	 * [itemCode, name, stockQuantity] of the head office items, by code: a product or a pack (or no type), not the
	 * excluded code; search (lower case, with % around) on the code and the name, null for every item.
	 */
	@Query(value = "select i.itemCode, i.name, i.stockQuantity from Item i where (i.type is null or i.type in :types)"
			+ " and i.itemCode <> :excluded and (:search is null or lower(i.itemCode) like :search"
			+ " or lower(i.name) like :search) order by i.itemCode",
			countQuery = "select count(i) from Item i where (i.type is null or i.type in :types)"
					+ " and i.itemCode <> :excluded and (:search is null or lower(i.itemCode) like :search"
					+ " or lower(i.name) like :search)")
	Page<Object[]> findHeadOfficeItems(@Param("types") Collection<ItemType> types, @Param("excluded") String excluded,
			@Param("search") String search, Pageable page);

	/** The stores own items (own TRUE: not from the head office), by store and code; storeId 0 = every store. */
	@Query(value = "select s from HoStoreStock s where s.ownItem = :own and (:storeId = 0L or s.storeId = :storeId)"
			+ " and (:search is null or lower(s.itemCode) like :search or lower(s.itemName) like :search)"
			+ " order by s.storeId, s.itemCode",
			countQuery = "select count(s) from HoStoreStock s where s.ownItem = :own and (:storeId = 0L or s.storeId = :storeId)"
					+ " and (:search is null or lower(s.itemCode) like :search or lower(s.itemName) like :search)")
	Page<HoStoreStock> findOwnItems(@Param("own") Boolean own, @Param("storeId") Long storeId,
			@Param("search") String search, Pageable page);
}
