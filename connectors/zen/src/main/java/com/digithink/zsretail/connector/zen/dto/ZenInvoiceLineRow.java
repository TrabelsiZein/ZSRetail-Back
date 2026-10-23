package com.digithink.zsretail.connector.zen.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/**
 * Item variants (ZEN), step 9: a line of a posted sales invoice (SalesInvoiceSalesInvLines), only the fields used, as
 * Happyness's NavPosInvoiceLineRow plus Variant_Code (the item code is No~Variant_Code). The three totals of the invoice
 * are repeated on each line. Unit_Cost_LCY and Invoice_Discount_Amount are never read; VAT_Percent neither (an invoice
 * line of the head office keeps no VAT rate).
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ZenInvoiceLineRow {

	/** The fields read ($select of the expanded lines). */
	public static final String FIELDS = "Document_No,Line_No,Type,No,Variant_Code,Description,Quantity,"
			+ "Unit_of_Measure_Code,Unit_Price,Line_Discount_Percent,Line_Amount,Total_Amount_Excl_VAT,Total_VAT_Amount,"
			+ "Total_Amount_Incl_VAT";

	@JsonProperty("Document_No")
	private String documentNo;

	@JsonProperty("Line_No")
	private Integer lineNo;

	/** Item, G/L Account, Resource...; a comment line has a blank type (" "). */
	@JsonProperty("Type")
	private String type;

	@JsonProperty("No")
	private String no;

	@JsonProperty("Variant_Code")
	private String variantCode;

	@JsonProperty("Description")
	private String description;

	@JsonProperty("Quantity")
	private BigDecimal quantity;

	@JsonProperty("Unit_of_Measure_Code")
	private String unitOfMeasureCode;

	@JsonProperty("Unit_Price")
	private BigDecimal unitPrice;

	@JsonProperty("Line_Discount_Percent")
	private BigDecimal lineDiscountPercent;

	@JsonProperty("Line_Amount")
	private BigDecimal lineAmount;

	@JsonProperty("Total_Amount_Excl_VAT")
	private BigDecimal totalAmountExclVat;

	@JsonProperty("Total_VAT_Amount")
	private BigDecimal totalVatAmount;

	@JsonProperty("Total_Amount_Incl_VAT")
	private BigDecimal totalAmountInclVat;
}
