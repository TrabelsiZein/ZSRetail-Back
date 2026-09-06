package com.digithink.zsretail.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.model.SalesDiscount;
import com.digithink.zsretail.model.enumeration.SalesDiscountSalesType;
import com.digithink.zsretail.model.enumeration.SalesDiscountType;

public interface SalesDiscountRepository extends _BaseRepository<SalesDiscount, Long> {

	Optional<SalesDiscount> findByErpExternalId(String erpExternalId);

	Optional<SalesDiscount> findByTypeAndCodeAndSalesTypeAndSalesCodeAndResponsibilityCenterAndStartingDateAndAuxiliaryIndex1AndAuxiliaryIndex2AndAuxiliaryIndex3AndAuxiliaryIndex4(
			SalesDiscountType type, String code,
			SalesDiscountSalesType salesType, String salesCode, String responsibilityCenter,
			LocalDate startingDate, String auxiliaryIndex1, String auxiliaryIndex2, String auxiliaryIndex3,
			Integer auxiliaryIndex4);

	// Legacy method - note: startingDate parameter is String but entity uses LocalDate
	// This method may not work correctly and should be updated if used
	Optional<SalesDiscount> findByTypeAndCodeAndSalesTypeAndSalesCodeAndResponsibilityCenterAndStartingDateAndAuxiliaryIndex1AndAuxiliaryIndex2AndAuxiliaryIndex3AndAuxiliaryIndex4(
			SalesDiscountType type, String code, 
			SalesDiscountSalesType salesType, String salesCode, String responsibilityCenter,
			String startingDate, String auxiliaryIndex1, String auxiliaryIndex2, String auxiliaryIndex3,
			Integer auxiliaryIndex4);

	/**
	 * Find SalesDiscount by type, code, salesType, salesCode with date validation
	 */
	@Query("SELECT sd FROM SalesDiscount sd WHERE sd.type = :type AND sd.code = :code " +
			"AND sd.salesType = :salesType AND sd.salesCode = :salesCode " +
			"AND (sd.startingDate <= :currentDate OR sd.startingDate IS NULL) " +
			"AND (sd.endingDate >= :currentDate OR sd.endingDate IS NULL) " +
			"ORDER BY sd.startingDate DESC")
	List<SalesDiscount> findByTypeAndCodeAndSalesTypeAndSalesCodeWithDate(
			@Param("type") SalesDiscountType type,
			@Param("code") String code,
			@Param("salesType") SalesDiscountSalesType salesType,
			@Param("salesCode") String salesCode,
			@Param("currentDate") LocalDate currentDate);

	/**
	 * Find SalesDiscount by type, code with "All Customers" (empty salesCode)
	 */
	@Query("SELECT sd FROM SalesDiscount sd WHERE sd.type = :type AND sd.code = :code " +
			"AND sd.salesType = :salesType AND (sd.salesCode = '' OR sd.salesCode IS NULL) " +
			"AND (sd.startingDate <= :currentDate OR sd.startingDate IS NULL) " +
			"AND (sd.endingDate >= :currentDate OR sd.endingDate IS NULL) " +
			"ORDER BY sd.startingDate DESC")
	List<SalesDiscount> findByTypeAndCodeAndAllCustomersWithDate(
			@Param("type") SalesDiscountType type,
			@Param("code") String code,
			@Param("salesType") SalesDiscountSalesType salesType,
			@Param("currentDate") LocalDate currentDate);

	/**
	 * Find all matching SalesDiscount records across all sales types (Customer Disc. Group, Customer, All Customers)
	 * Returns all valid matches sorted by highest discount first (best for customer)
	 * 
	 * @param type Discount type (ITEM or ITEM_DISC_GROUP)
	 * @param code Item code or Item Disc. Group code
	 * @param customerDiscGroup Customer discount group (can be null/empty to skip this condition)
	 * @param customerCode Customer code (can be null/empty to skip this condition)
	 * @param currentDate Current date for validation
	 * @return List of matching SalesDiscount records, sorted by lineDiscount DESC, startingDate DESC
	 */
	@Query("SELECT sd FROM SalesDiscount sd WHERE sd.type = :type AND sd.code = :code "
			+ "AND ("
			+ "  (:customerDiscGroup IS NOT NULL AND :customerDiscGroup != '' AND sd.salesType = :customerDiscGroupType AND sd.salesCode = :customerDiscGroup) OR "
			+ "  (:customerCode IS NOT NULL AND :customerCode != '' AND sd.salesType = :customerType AND sd.salesCode = :customerCode) OR "
			+ "  (sd.salesType = :allCustomersType AND (sd.salesCode = '' OR sd.salesCode IS NULL)) "
			+ ") "
			+ "AND (sd.startingDate <= :currentDate OR sd.startingDate IS NULL) "
			+ "AND (sd.endingDate >= :currentDate OR sd.endingDate IS NULL) "
			+ "ORDER BY sd.lineDiscount DESC, sd.startingDate DESC")
	List<SalesDiscount> findAllMatchingDiscounts(
			@Param("type") SalesDiscountType type,
			@Param("code") String code,
			@Param("customerDiscGroupType") SalesDiscountSalesType customerDiscGroupType,
			@Param("customerDiscGroup") String customerDiscGroup,
			@Param("customerType") SalesDiscountSalesType customerType,
			@Param("customerCode") String customerCode,
			@Param("allCustomersType") SalesDiscountSalesType allCustomersType,
			@Param("currentDate") LocalDate currentDate);
}

