package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Purchase line entity - line items for a purchase.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class PurchaseLine extends _BaseEntity {

	@ManyToOne
	@JoinColumn(name = "purchase_header_id", nullable = false)
	@JsonIgnore
	private PurchaseHeader purchaseHeader;

	@ManyToOne
	@JoinColumn(name = "item_id", nullable = false)
	private Item item;

	@Column(nullable = false)
	private Integer quantity;

	@Column(nullable = false)
	private Double unitPrice;

	/** Discount percentage applied to this line (e.g. 10.0 = 10%). Null if no discount. */
	@Column(name = "discount_percent")
	private Double discountPercent;

	/** VAT rate for this line, copied from item defaultVAT at time of purchase (e.g. 19). */
	@Column(name = "vat_percent")
	private Integer vatPercent;

	/** Calculated VAT amount: lineTotal * vatPercent / 100. */
	@Column(name = "vat_amount")
	private Double vatAmount;

	/** Line total HT after discount: quantity * (unitPrice * (1 - discountPercent/100)). */
	@Column(nullable = false)
	private Double lineTotal;

	/** Line total TTC: lineTotal + vatAmount. */
	private Double lineTotalIncludingVat;
}
