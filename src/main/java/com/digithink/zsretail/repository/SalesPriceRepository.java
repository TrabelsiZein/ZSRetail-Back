package com.digithink.zsretail.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.model.SalesPrice;
import com.digithink.zsretail.model.enumeration.SalesPriceType;

public interface SalesPriceRepository extends _BaseRepository<SalesPrice, Long> {

	Optional<SalesPrice> findByErpExternalId(String erpExternalId);

	Optional<SalesPrice> findByItemNoAndSalesTypeAndSalesCodeAndResponsibilityCenterAndStartingDateAndCurrencyCodeAndVariantCodeAndUnitOfMeasureCodeAndMinimumQuantity(
			String itemNo, SalesPriceType salesType, String salesCode, String responsibilityCenter, LocalDate startingDate,
			String currencyCode, String variantCode, String unitOfMeasureCode, Double minimumQuantity);

	// Legacy method - note: startingDate parameter is String but entity uses LocalDate
	// This method may not work correctly and should be updated if used
	Optional<SalesPrice> findByItemNoAndSalesTypeAndSalesCodeAndResponsibilityCenterAndStartingDateAndCurrencyCodeAndVariantCodeAndUnitOfMeasureCodeAndMinimumQuantity(
			String itemNo, SalesPriceType salesType, String salesCode, String responsibilityCenter, String startingDate,
			String currencyCode, String variantCode, String unitOfMeasureCode, Double minimumQuantity);

	/**
	 * Find SalesPrice by itemNo, salesType, salesCode with date validation Priority
	 * 1: Customer Price Group
	 */
	@Query("SELECT sp FROM SalesPrice sp WHERE sp.itemNo = :itemNo "
			+ "AND sp.salesType = :salesType AND sp.salesCode = :salesCode "
			+ "AND (sp.startingDate <= :currentDate OR sp.startingDate IS NULL) "
			+ "AND (sp.endingDate >= :currentDate OR sp.endingDate IS NULL) " + "ORDER BY sp.startingDate DESC")
	List<SalesPrice> findByItemNoAndSalesTypeAndSalesCodeWithDate(@Param("itemNo") String itemNo,
			@Param("salesType") SalesPriceType salesType, 
			@Param("salesCode") String salesCode,
			@Param("currentDate") LocalDate currentDate);

	/**
	 * Find SalesPrice by itemNo with "All Customers" (empty salesCode)
	 */
	@Query("SELECT sp FROM SalesPrice sp WHERE sp.itemNo = :itemNo "
			+ "AND sp.salesType = :salesType AND (sp.salesCode = '' OR sp.salesCode IS NULL) "
			+ "AND (sp.startingDate <= :currentDate OR sp.startingDate IS NULL) "
			+ "AND (sp.endingDate >= :currentDate OR sp.endingDate IS NULL) " + "ORDER BY sp.startingDate DESC")
	List<SalesPrice> findByItemNoAndAllCustomersWithDate(@Param("itemNo") String itemNo,
			@Param("salesType") SalesPriceType salesType, 
			@Param("currentDate") LocalDate currentDate);

	/**
	 * Find all matching SalesPrice records across all types (Customer Price Group, Customer, All Customers)
	 * Returns all valid matches sorted by lowest price first (best for customer)
	 * 
	 * @param itemNo The item number
	 * @param customerPriceGroup Customer price group (can be null/empty to skip this condition)
	 * @param customerCode Customer code (can be null/empty to skip this condition)
	 * @param currentDate Current date for validation
	 * @return List of matching SalesPrice records, sorted by unitPrice ASC, startingDate DESC
	 */
	@Query("SELECT sp FROM SalesPrice sp WHERE sp.itemNo = :itemNo "
			+ "AND ("
			+ "  (:customerPriceGroup IS NOT NULL AND :customerPriceGroup != '' AND sp.salesType = :customerPriceGroupType AND sp.salesCode = :customerPriceGroup) OR "
			+ "  (:customerCode IS NOT NULL AND :customerCode != '' AND sp.salesType = :customerType AND sp.salesCode = :customerCode) OR "
			+ "  (sp.salesType = :allCustomersType AND (sp.salesCode = '' OR sp.salesCode IS NULL)) "
			+ ") "
			+ "AND (sp.startingDate <= :currentDate OR sp.startingDate IS NULL) "
			+ "AND (sp.endingDate >= :currentDate OR sp.endingDate IS NULL) "
			+ "ORDER BY sp.unitPrice ASC, sp.startingDate DESC")
	List<SalesPrice> findAllMatchingSalesPrices(
			@Param("itemNo") String itemNo,
			@Param("customerPriceGroupType") SalesPriceType customerPriceGroupType,
			@Param("customerPriceGroup") String customerPriceGroup,
			@Param("customerType") SalesPriceType customerType,
			@Param("customerCode") String customerCode,
			@Param("allCustomersType") SalesPriceType allCustomersType,
			@Param("currentDate") LocalDate currentDate);
}
