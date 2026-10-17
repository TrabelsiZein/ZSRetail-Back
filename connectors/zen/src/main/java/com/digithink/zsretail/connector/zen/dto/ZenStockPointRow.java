package com.digithink.zsretail.connector.zen.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A row of the page stockPoints: one item and variant of one location (a point de stock), with the variant's fields,
 * the item's classification and the location's price and clearance. The names are those of the API (no $select: the
 * fields not used here are ignored, and active and vat are read when the API gives them). variantDescription is not
 * read: on ZEN's platform it is always the item description (the mapper builds the variant's own).
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
	/** The selling price before VAT. */
	private BigDecimal publicPrice;
	private Boolean clearance;
	private BigDecimal clearancePrice;
	/** Not in the API (seen on ZEN's test NAV, step 12a; assumed name): null when absent, read as true. */
	private Boolean active;
	/**
	 * Not in the API (seen on ZEN's test NAV, step 12a; assumed name): the item's VAT rate in percent; null when absent
	 * (erp.zen.default-vat applies).
	 */
	private BigDecimal vat;
}
