package com.digithink.zsretail.headoffice.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One line of a {@link HoSupplyInvoice} (step 7B): one line of a received BL (its confirmed quantity, the BL number on
 * the line), or the tax stamp line (no BL, VAT 0). Amounts to the millime: lineTotal = quantity x unitPrice before VAT,
 * vatAmount from the item's VAT rate, lineTotalIncludingVat their sum.
 */
@Entity
@Table(name = "ho_supply_invoice_line",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_supply_invoice_line", columnNames = { "invoice_id",
				"line_no" }))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoSupplyInvoiceLine extends _BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "invoice_id", nullable = false)
	@JsonIgnore
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private HoSupplyInvoice invoice;

	@Column(name = "line_no", nullable = false)
	private Integer lineNo;

	/** The BL of the line; null for the tax stamp. */
	@Column(name = "delivery_number", length = 30)
	private String deliveryNumber;

	@Column(name = "item_id")
	private Long itemId;

	@Column(name = "item_code", nullable = false, length = 100)
	private String itemCode;

	@Column(name = "item_name")
	private String itemName;

	@Column(nullable = false)
	private Integer quantity;

	/** Before VAT. */
	@Column(name = "unit_price", nullable = false)
	private Double unitPrice;

	@Column(name = "vat_percent")
	private Integer vatPercent;

	@Column(name = "vat_amount")
	private Double vatAmount;

	/** Before VAT. */
	@Column(name = "line_total")
	private Double lineTotal;

	@Column(name = "line_total_including_vat")
	private Double lineTotalIncludingVat;
}
