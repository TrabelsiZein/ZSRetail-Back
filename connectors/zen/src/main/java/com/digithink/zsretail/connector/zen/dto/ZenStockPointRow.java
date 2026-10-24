package com.digithink.zsretail.connector.zen.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A row of the page stockPoints: one item and variant of one location (a point de stock), with the variant's fields,
 * the item's classification, the location's prices and clearance, the VAT and the three blocked flags (the page of
 * 2026-10-24). The names are those of the API (no $select: the fields not used here are ignored). The prices come
 * twice: before VAT (unitPrice, clearancePriceExclVAT, the ones read) and with VAT (publicPrice, clearancePrice, read
 * for the mapper's check only). variantDescription is not read: on ZEN's platform it is always the item description
 * (the mapper builds the variant's own).
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ZenStockPointRow {

	private String locationCode;
	private String itemNo;
	private String variantCode;
	/** ISO with its offset; 0001-01-01T00:00:00Z when NAV has none. */
	private String lastModifiedDateTime;
	private String itemDescription;
	private String stockPointDescription;
	private String sizeCode;
	private String sizeDescription;
	private Integer sizeSortOrder;
	private String colorCode;
	private String colorDescription;
	private String choiceCode;
	private String choiceDescription;
	private String seasonCode;
	private String divisionCode;
	private String categoryCode;
	private String familyCode;
	private String subFamilyCode;
	private String sectionCode;
	private String brandCode;
	/** The item's VAT rate in percent; null when absent (erp.zen.default-vat applies). */
	private BigDecimal vatPercent;
	/** The selling price before VAT (the item's price). */
	private BigDecimal unitPrice;
	/** The selling price with VAT, unitPrice × (1 + vatPercent / 100) at 3 decimals: never the price, a check only. */
	private BigDecimal publicPrice;
	private Boolean clearance;
	/** The location's clearance price before VAT. */
	private BigDecimal clearancePriceExclVAT;
	/** The clearance price with VAT: never the price, a check only. */
	private BigDecimal clearancePrice;
	/** The location blocks this item and variant; null is false. */
	private Boolean stockPointBlocked;
	/** The item is blocked in NAV; null is false. */
	private Boolean itemBlocked;
	/** The variant is blocked in NAV; null is false. */
	private Boolean variantBlocked;
}
