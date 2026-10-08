package com.digithink.zsretail.headoffice.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceLineType;
import com.digithink.zsretail.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One line of a {@link HoErpInvoice} (invoices from the ERP, step b), as the ERP gives it: an item line (ITEM) or another
 * line with an amount (OTHER, no item). unit_cost = line_amount / quantity (after the line discount, before the VAT), the
 * cost the store will take; null at amount 0 (a tester), on an OTHER line, or without a whole quantity.
 */
@Entity
@Table(name = "ho_erp_invoice_line",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_erp_invoice_line", columnNames = { "invoice_id", "line_no" }))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoErpInvoiceLine extends _BaseEntity {

	public static final int ITEM_CODE_LENGTH = 100;
	public static final int UNIT_LENGTH = 20;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "invoice_id", nullable = false)
	@JsonIgnore
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private HoErpInvoice invoice;

	/** The ERP's Line_No (10000, 20000...). */
	@Column(name = "line_no", nullable = false)
	private Integer lineNo;

	@Enumerated(EnumType.STRING)
	@Column(name = "line_type", nullable = false, length = 10)
	private ErpInvoiceLineType lineType;

	/** The ERP item number; null on an OTHER line. */
	@Column(name = "item_code", length = ITEM_CODE_LENGTH)
	private String itemCode;

	/** item.id of the head office item with that code; null when it is not in the head office catalogue. */
	@Column(name = "item_id")
	private Long itemId;

	@Column(name = "description")
	private String description;

	/** Null when the ERP's quantity is not whole (the invoice is then held). */
	@Column(name = "quantity")
	private Integer quantity;

	@Column(name = "unit_of_measure", length = UNIT_LENGTH)
	private String unitOfMeasure;

	@Column(name = "unit_price")
	private Double unitPrice;

	@Column(name = "line_discount_percent")
	private Double lineDiscountPercent;

	@Column(name = "line_amount")
	private Double lineAmount;

	@Column(name = "unit_cost")
	private Double unitCost;

	/** Null until the store's confirmation arrives; ITEM lines only. */
	@Column(name = "quantity_received")
	private Integer quantityReceived;
}
