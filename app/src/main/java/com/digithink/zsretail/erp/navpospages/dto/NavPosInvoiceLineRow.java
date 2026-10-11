package com.digithink.zsretail.erp.navpospages.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/**
 * Invoices from the ERP, step (a): a line of an invoice (FactureFranchiseSalesInvLines), only the fields used. The three
 * totals of the invoice are repeated on each line. Unit_Cost_LCY, Invoice_Discount_Amount and Transferred are never read.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NavPosInvoiceLineRow {

	/** The fields read ($select of the expanded lines). */
	public static final String FIELDS = "Document_No,Line_No,Type,No,Description,Quantity,Unit_of_Measure_Code,"
			+ "Unit_Price,Line_Discount_Percent,Line_Amount,Total_Amount_Excl_VAT,Total_VAT_Amount,Total_Amount_Incl_VAT";

	@JsonProperty("Document_No")
	private String documentNo;

	@JsonProperty("Line_No")
	private Integer lineNo;

	/** Item, G/L Account, Resource...; a comment line has a blank type (" "). */
	@JsonProperty("Type")
	private String type;

	@JsonProperty("No")
	private String no;

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
