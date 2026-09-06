package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Purchase invoice line - can represent a single purchase line or an aggregated
 * item, family, or sub-family. Standalone mode only.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class PurchaseInvoiceLine extends _BaseEntity {

	@ManyToOne
	@JoinColumn(name = "purchase_invoice_id", nullable = false)
	private PurchaseInvoiceHeader purchaseInvoice;

	@ManyToOne
	@JoinColumn(name = "item_id")
	private Item item;

	@ManyToOne
	@JoinColumn(name = "item_family_id")
	private ItemFamily itemFamily;

	@ManyToOne
	@JoinColumn(name = "item_sub_family_id")
	private ItemSubFamily itemSubFamily;

	private String lineDescription;

	@Column(nullable = false)
	private Integer quantity;

	private Double unitPrice;

	/** Unit price including VAT (TTC). */
	private Double unitPriceIncludingVat;

	private Double subtotal;

	private Double taxAmount;

	private Double totalAmount;

	/** Same as totalAmount — total for this line including VAT. */
	private Double lineTotalIncludingVat;

	private Integer vatPercent;
}
