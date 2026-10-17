package com.digithink.zsretail.core.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;

import com.digithink.zsretail.core.utils.Quantities;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Invoice line - can represent a single sales line, an aggregated item, family, or sub-family.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class InvoiceLine extends _BaseEntity {

	@ManyToOne
	@JoinColumn(name = "invoice_id", nullable = false)
	@JsonIgnore
	private InvoiceHeader invoice;

	/**
	 * When grouping by item or not grouping at all, this points to the concrete item.
	 * For family/subfamily grouping this will be null.
	 */
	@ManyToOne
	@JoinColumn(name = "item_id")
	private Item item;

	/**
	 * When grouping by family, this points to the family that was aggregated.
	 */
	@ManyToOne
	@JoinColumn(name = "item_family_id")
	private ItemFamily itemFamily;

	/**
	 * When grouping by sub-family, this points to the sub-family that was aggregated.
	 */
	@ManyToOne
	@JoinColumn(name = "item_sub_family_id")
	private ItemSubFamily itemSubFamily;

	/**
	 * Optional human-readable description for the line when grouping (e.g. family/sub-family name).
	 */
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

	/**
	 * 3.1.0: the line's money amounts are stored to the millime, half up ({@link Quantities#roundAmount}), on every
	 * save, as the ticket lines (test log I-07); unit prices stay as computed.
	 */
	@PrePersist
	@PreUpdate
	public void roundAmounts() {
		subtotal = Quantities.roundAmountOrNull(subtotal);
		taxAmount = Quantities.roundAmountOrNull(taxAmount);
		totalAmount = Quantities.roundAmountOrNull(totalAmount);
		lineTotalIncludingVat = Quantities.roundAmountOrNull(lineTotalIncludingVat);
	}
}

