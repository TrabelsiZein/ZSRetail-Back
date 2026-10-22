package com.digithink.zsretail.headoffice.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.headoffice.model.HoStoreStock;

public interface HoStoreStockRepository extends _BaseRepository<HoStoreStock, Long> {

	List<HoStoreStock> findByStoreIdAndItemCodeIn(Long storeId, Collection<String> itemCodes);

	/** The rows of these item codes, for one store (storeId) or every store (0). */
	@Query("select s from HoStoreStock s where s.itemCode in :codes and (:storeId = 0L or s.storeId = :storeId)")
	List<HoStoreStock> findByCodes(@Param("codes") Collection<String> codes, @Param("storeId") Long storeId);

	/**
	 * Fiches items, the card's Stock per store tab: [store code, store name, itemCode, quantity, storeTime] of these item
	 * codes at the active stores (a null active counts as active), by store code then item code.
	 */
	@Query("select st.code, st.name, s.itemCode, s.quantity, s.storeTime from HoStoreStock s, Store st"
			+ " where st.id = s.storeId and s.itemCode in :codes and (st.active is null or st.active = true)"
			+ " order by st.code, s.itemCode")
	List<Object[]> findAtActiveStores(@Param("codes") Collection<String> codes);

	/** [storeId, last received_at] per store. */
	@Query("select s.storeId, max(s.receivedAt) from HoStoreStock s group by s.storeId")
	List<Object[]> lastReceivedByStore();

	/**
	 * The stores own items (own TRUE: not from the head office), by store and code; storeId 0 = every store. belowZero
	 * true: only those whose stock is below zero.
	 */
	@Query(value = "select s from HoStoreStock s where s.ownItem = :own and (:storeId = 0L or s.storeId = :storeId)"
			+ " and (:search is null or lower(s.itemCode) like :search or lower(s.itemName) like :search)"
			+ " and (:belowZero = false or s.quantity < 0)"
			+ " order by s.storeId, s.itemCode",
			countQuery = "select count(s) from HoStoreStock s where s.ownItem = :own and (:storeId = 0L or s.storeId = :storeId)"
					+ " and (:search is null or lower(s.itemCode) like :search or lower(s.itemName) like :search)"
					+ " and (:belowZero = false or s.quantity < 0)")
	Page<HoStoreStock> findOwnItems(@Param("own") Boolean own, @Param("storeId") Long storeId,
			@Param("search") String search, @Param("belowZero") Boolean belowZero, Pageable page);
}
