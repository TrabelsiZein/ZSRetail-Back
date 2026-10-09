package com.digithink.zsretail.headoffice.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.repository._BaseRepository;

public interface StoreRepository extends _BaseRepository<Store, Long> {

	Optional<Store> findByCodeIgnoreCase(String code);

	/** Invoices from the ERP: the store whose ERP customer number is this one (unique, any case). */
	Optional<Store> findByErpCustomerNoIgnoreCase(String erpCustomerNo);

	/** Step 6: the stores whose selling price list is this one. */
	@Query("select s.id from Store s where s.sellingPriceListId = :listId")
	List<Long> findIdsBySellingPriceListId(@Param("listId") Long listId);

	long countBySellingPriceListId(Long listId);

	/** Step 7B: how many stores have this supply price list. */
	long countBySupplyPriceListId(Long listId);

	/** Stock points, step 1: how many stores have this stock point. */
	long countByStockPointId(Long stockPointId);

	/**
	 * Heartbeat (task 1.4): writes lastContact and appVersion only, by id. A bulk update skips {@code @PreUpdate},
	 * so updatedAt is unchanged. Returns the number of rows updated. Task 3.6: in the same update, what the store owns
	 * (null = unknown), see Store.
	 */
	@Modifying
	@Query("update Store s set s.lastContact = :lastContact, s.appVersion = :appVersion,"
			+ " s.ownerCatalogue = :catalogue, s.ownerCustomers = :customers, s.ownerPromotions = :promotions,"
			+ " s.ownerLoyalty = :loyalty, s.ownerSupply = :supply, s.reportedSalesUpstreams = :upstreams where s.id = :id")
	int updateContact(@Param("id") Long id, @Param("lastContact") LocalDateTime lastContact,
			@Param("appVersion") String appVersion, @Param("catalogue") String catalogue,
			@Param("customers") String customers, @Param("promotions") String promotions,
			@Param("loyalty") String loyalty, @Param("supply") String supply, @Param("upstreams") String upstreams);
}
