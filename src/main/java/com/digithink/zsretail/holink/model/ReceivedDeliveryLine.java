package com.digithink.zsretail.holink.model;

import java.math.BigDecimal;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.holink.enumeration.ReceivedLineType;
import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.utils.Quantities;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One line of a {@link ReceivedDelivery} (step 7A): the head office item by code, the store item when it exists here
 * (a head office item, origin HEAD_OFFICE), the quantity sent and, once confirmed, the quantity received and whether it
 * went into the stock (false while the item is missing here: applied once by the next cycle that finds it).
 */
@Entity
@Table(name = "hol_delivery_line",
		uniqueConstraints = @UniqueConstraint(name = "uk_hol_delivery_line", columnNames = { "delivery_id", "line_no" }))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ReceivedDeliveryLine extends _BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "delivery_id", nullable = false)
	@JsonIgnore
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private ReceivedDelivery delivery;

	@Column(name = "line_no", nullable = false)
	private Integer lineNo;

	/** The head office item code; "" on an OTHER line of an ERP invoice (no item; the column stays NOT NULL). */
	@Column(name = "item_code", nullable = false, length = 100)
	private String itemCode;

	@Column(name = "item_name")
	private String itemName;

	/** item.id of this store; null while the item is not here. */
	@Column(name = "item_id")
	private Long itemId;

	/** 2.2.1: up to 3 decimals; read through {@link #getQuantitySent()} (50.000 is 50). */
	@Column(name = "quantity_sent", nullable = false, precision = Quantities.PRECISION, scale = Quantities.SCALE)
	private BigDecimal quantitySent;

	/** Null until confirmed. 2.2.1: up to 3 decimals. */
	@Column(name = "quantity_received", precision = Quantities.PRECISION, scale = Quantities.SCALE)
	private BigDecimal quantityReceived;

	/** Null until confirmed; then true once the quantity is in the stock (or nothing to add), false while it waits. */
	@Column(name = "stock_applied")
	private Boolean stockApplied;

	/** Invoices from the ERP, step (c): ITEM or OTHER; null reads as ITEM (every BL line). */
	@Enumerated(EnumType.STRING)
	@Column(name = "line_type", length = 10)
	private ReceivedLineType lineType = ReceivedLineType.ITEM;

	/** ERP invoice: the ERP's unit price before the line discount. */
	@Column(name = "unit_price")
	private Double unitPrice;

	@Column(name = "line_discount_percent")
	private Double lineDiscountPercent;

	/** ERP invoice: after the line discount, before the VAT. */
	@Column(name = "line_amount")
	private Double lineAmount;

	/** ERP invoice: line_amount / quantity invoiced; null at amount 0 and on an OTHER line (never a cost then). */
	@Column(name = "unit_cost")
	private Double unitCost;

	/** ERP invoice: true once the cost went into the item; null while not (yet) or never. */
	@Column(name = "cost_applied")
	private Boolean costApplied;

	/** True for an item line (a BL line, an ERP ITEM line). */
	public boolean isItemLine() {
		return lineType != ReceivedLineType.OTHER;
	}

	public BigDecimal getQuantitySent() {
		return Quantities.normalize(quantitySent);
	}

	public BigDecimal getQuantityReceived() {
		return Quantities.normalize(quantityReceived);
	}
}
