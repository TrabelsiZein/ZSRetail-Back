package com.digithink.zsretail.connector.zen.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/**
 * Item variants (ZEN), step 9: a line of a posted sales invoice (salesInvoiceLines of ZEN's franchise API), only the
 * fields used, as Happyness's NavPosInvoiceLineRow plus variantCode (the item code is number~variantCode). The totals
 * are on the header. amount, amountIncludingVAT and vatPercent are never read (an invoice line of the head office keeps
 * no VAT rate; its cost is lineAmount); sizeCode, colorCode, choiceCode and locationCode neither.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ZenInvoiceLineRow {

	/** The fields read ($select of the expanded lines). */
	public static final String FIELDS = "documentNo,lineNo,lineType,number,variantCode,description,quantity,"
			+ "unitOfMeasureCode,unitPrice,lineDiscountPercent,lineAmount";

	@JsonProperty("documentNo")
	private String documentNo;

	@JsonProperty("lineNo")
	private Integer lineNo;

	/**
	 * NAV's enum salesLineType as the API writes it: Item, G_x002F_L_x0020_Account, Resource...; a comment line has the
	 * blank option (_x0020_).
	 */
	@JsonProperty("lineType")
	private String type;

	@JsonProperty("number")
	private String no;

	@JsonProperty("variantCode")
	private String variantCode;

	@JsonProperty("description")
	private String description;

	@JsonProperty("quantity")
	private BigDecimal quantity;

	@JsonProperty("unitOfMeasureCode")
	private String unitOfMeasureCode;

	@JsonProperty("unitPrice")
	private BigDecimal unitPrice;

	@JsonProperty("lineDiscountPercent")
	private BigDecimal lineDiscountPercent;

	@JsonProperty("lineAmount")
	private BigDecimal lineAmount;
}
