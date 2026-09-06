package com.digithink.zsretail.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.digithink.zsretail.dto.PricingResult;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.SalesDiscount;
import com.digithink.zsretail.model.SalesPrice;
import com.digithink.zsretail.model.enumeration.SalesDiscountSalesType;
import com.digithink.zsretail.model.enumeration.SalesDiscountType;
import com.digithink.zsretail.model.enumeration.SalesPriceType;
import com.digithink.zsretail.repository.SalesDiscountRepository;
import com.digithink.zsretail.repository.SalesPriceRepository;

import lombok.extern.log4j.Log4j2;

/**
 * Service for calculating item prices using SalesPrice and SalesDiscount tables
 * Implements best-value selection: always chooses lowest price and highest
 * discount
 */
@Service
@Log4j2
public class PricingService {

	@Value("${pos.pricing.enable-sales-price-group:false}")
	private boolean priceGroupEnabled;

	@Autowired
	private SalesPriceRepository salesPriceRepository;

	@Autowired
	private SalesDiscountRepository salesDiscountRepository;

	/**
	 * Calculate the final price for an item
	 * 
	 * @param item                 The item to price
	 * @param customer             The customer (can be null)
	 * @param quantity             The quantity (currently not used in matching)
	 * @param responsibilityCenter The responsibility center (currently ignored)
	 * @return PricingResult with final price, discount, and source
	 */
	public PricingResult calculateItemPrice(Item item, Customer customer, Integer quantity,
			String responsibilityCenter) {
		if (item == null) {
			log.warn("calculateItemPrice called with null item");
			return new PricingResult(0.0, false, null, "ERROR");
		}

		// If price group is disabled, use item price directly
		if (!priceGroupEnabled) {
			return new PricingResult(item.getUnitPrice() != null ? item.getUnitPrice() : 0.0, false, null, "ITEM");
		}

		// Find sales price using best-value selection (lowest price)
		SalesPrice salesPrice = findSalesPrice(item, customer, responsibilityCenter);
		Double unitPrice;
		Boolean priceIncludesVat;
		String source;

		if (salesPrice != null && salesPrice.getUnitPrice() != null) {
			unitPrice = salesPrice.getUnitPrice();
			priceIncludesVat = salesPrice.getPriceIncludesVat() != null ? salesPrice.getPriceIncludesVat() : false;
			source = "SALES_PRICE";

			// If price includes VAT (TTC), convert to HT (excluding VAT)
			// Frontend expects HT and will apply VAT again, so we must convert TTC to HT
			if (priceIncludesVat) {
				if (item.getDefaultVAT() != null && item.getDefaultVAT() > 0) {
					// Convert TTC to HT: HT = TTC / (1 + VAT%)
					double vatRate = item.getDefaultVAT() / 100.0;
					unitPrice = unitPrice / (1.0 + vatRate);
					log.debug("Converted TTC price {} to HT price {} for item {} (VAT: {}%)", salesPrice.getUnitPrice(),
							unitPrice, item.getItemCode(), item.getDefaultVAT());
				} else {
					// VAT is null or 0, cannot convert - log warning
					// If VAT is 0%, TTC = HT, so no conversion needed
					log.warn(
							"Price includes VAT but item {} has no VAT rate (defaultVAT: {}). "
									+ "Assuming price is already HT or VAT is 0%.",
							item.getItemCode(), item.getDefaultVAT());
				}
			}
//			if (item.getUnitPrice() != null && unitPrice > item.getUnitPrice()) {
//				unitPrice = item.getUnitPrice();
//				priceIncludesVat = false; // Item price is always HT
//				source = "ITEM";
//			}
		} else {
			// Fallback to item price
			unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : 0.0;
			priceIncludesVat = false; // Item price is always HT
			source = "ITEM";
		}

		// Find discount
		Double discountPercentage = findSalesDiscount(item, customer, responsibilityCenter);

		// Always return HT price (priceIncludesVat flag is kept for reference but price
		// is always HT)
		return new PricingResult(unitPrice, priceIncludesVat, discountPercentage, source);
	}

	/**
	 * Find SalesPrice using best-value selection: finds all matching records and
	 * returns lowest price No priority - all matching types (Customer Price Group,
	 * Customer, All Customers) are considered equally
	 * 
	 * @param item                 The item
	 * @param customer             The customer (can be null)
	 * @param responsibilityCenter The responsibility center (ignored)
	 * @return SalesPrice with lowest price if found, null otherwise
	 */
	private SalesPrice findSalesPrice(Item item, Customer customer, String responsibilityCenter) {
		if (item == null || !StringUtils.hasText(item.getItemCode())) {
			return null;
		}

		LocalDate currentDate = LocalDate.now();
		String itemNo = item.getItemCode();

		// Get customer info (can be null) - normalize empty strings to null
		String customerPriceGroup = (customer != null && StringUtils.hasText(customer.getCustomerPriceGroup()))
				? customer.getCustomerPriceGroup()
				: null;
		String customerCode = (customer != null && StringUtils.hasText(customer.getCustomerCode()))
				? customer.getCustomerCode()
				: null;

		// Find all matching SalesPrice records across all types
		List<SalesPrice> prices = salesPriceRepository.findAllMatchingSalesPrices(itemNo,
				SalesPriceType.CUSTOMER_PRICE_GROUP, customerPriceGroup, SalesPriceType.CUSTOMER, customerCode,
				SalesPriceType.ALL_CUSTOMERS, currentDate);

		if (!prices.isEmpty()) {
			// Sort by comparable price (price including VAT) so we compare apples to apples
			prices.sort(Comparator.comparingDouble((SalesPrice sp) -> getComparablePriceIncludingVat(sp, item))
					.thenComparing(SalesPrice::getStartingDate, Comparator.nullsLast(Comparator.reverseOrder())));
			SalesPrice bestPrice = prices.get(0);
			log.debug("Found best SalesPrice for item {}: price={}, type={}, code={}", itemNo, bestPrice.getUnitPrice(),
					bestPrice.getSalesType(), bestPrice.getSalesCode());
			return bestPrice;
		}

		log.debug("No SalesPrice found for item {}", itemNo);
		return null;
	}

	/**
	 * Returns the price including VAT for comparison (same basis for all rows). If
	 * price is VAT-inclusive, returns unit_price; otherwise unit_price * (1 +
	 * VAT%).
	 */
	private double getComparablePriceIncludingVat(SalesPrice sp, Item item) {
		Double unitPrice = sp.getUnitPrice();
		if (unitPrice == null) {
			return Double.MAX_VALUE;
		}
		if (Boolean.TRUE.equals(sp.getPriceIncludesVat())) {
			return unitPrice;
		}
		double vatRate = (item != null && item.getDefaultVAT() != null && item.getDefaultVAT() > 0)
				? item.getDefaultVAT() / 100.0
				: 0.0;
		return unitPrice * (1.0 + vatRate);
	}

	/**
	 * Find SalesDiscount using best-value selection: finds all matching records and
	 * returns highest discount No priority - all matching types (ITEM_DISC_GROUP
	 * and ITEM) and sales types are considered equally
	 * 
	 * @param item                 The item
	 * @param customer             The customer (can be null)
	 * @param responsibilityCenter The responsibility center (ignored)
	 * @return Highest discount percentage if found, null otherwise
	 */
	private Double findSalesDiscount(Item item, Customer customer, String responsibilityCenter) {
		if (item == null) {
			return null;
		}

		LocalDate currentDate = LocalDate.now();

		// Get customer info (can be null) - normalize empty strings to null
		String customerDiscGroup = (customer != null && StringUtils.hasText(customer.getCustomerDiscGroup()))
				? customer.getCustomerDiscGroup()
				: null;
		String customerCode = (customer != null && StringUtils.hasText(customer.getCustomerCode()))
				? customer.getCustomerCode()
				: null;

		Double bestDiscount = null;

		// Try ITEM_DISC_GROUP type
		if (StringUtils.hasText(item.getItemDiscGroup())) {
			List<SalesDiscount> discounts = salesDiscountRepository.findAllMatchingDiscounts(
					SalesDiscountType.ITEM_DISC_GROUP, item.getItemDiscGroup(),
					SalesDiscountSalesType.CUSTOMER_DISC_GROUP, customerDiscGroup, SalesDiscountSalesType.CUSTOMER,
					customerCode, SalesDiscountSalesType.ALL_CUSTOMERS, currentDate);

			if (!discounts.isEmpty()) {
				Double discount = discounts.get(0).getLineDiscount(); // First result is highest (sorted DESC)
				if (bestDiscount == null || (discount != null && discount > bestDiscount)) {
					bestDiscount = discount;
					log.debug("Found SalesDiscount for item {} with Item Disc. Group {}: {}%", item.getItemCode(),
							item.getItemDiscGroup(), discount);
				}
			}
		}

		// Try ITEM type
		if (StringUtils.hasText(item.getItemCode())) {
			List<SalesDiscount> discounts = salesDiscountRepository.findAllMatchingDiscounts(SalesDiscountType.ITEM,
					item.getItemCode(), SalesDiscountSalesType.CUSTOMER_DISC_GROUP, customerDiscGroup,
					SalesDiscountSalesType.CUSTOMER, customerCode, SalesDiscountSalesType.ALL_CUSTOMERS, currentDate);

			if (!discounts.isEmpty()) {
				Double discount = discounts.get(0).getLineDiscount(); // First result is highest (sorted DESC)
				if (bestDiscount == null || (discount != null && discount > bestDiscount)) {
					bestDiscount = discount;
					log.debug("Found SalesDiscount for item {}: {}%", item.getItemCode(), discount);
				}
			}
		}

		if (bestDiscount == null) {
			log.debug("No SalesDiscount found for item {}", item.getItemCode());
		}

		return bestDiscount;
	}

	/**
	 * Check if date is valid (current date is between start and end dates) Handles
	 * null endDate or "0001-01-01" as always valid
	 */
	@SuppressWarnings("unused")
	private boolean isDateValid(LocalDate startDate, LocalDate endDate) {
		LocalDate currentDate = LocalDate.now();
		if (startDate != null && startDate.isAfter(currentDate)) {
			return false;
		}
		if (endDate != null && !endDate.equals(LocalDate.of(1, 1, 1)) && endDate.isBefore(currentDate)) {
			return false;
		}
		return true;
	}
}
