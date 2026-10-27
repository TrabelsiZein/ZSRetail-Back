package com.digithink.zsretail.headoffice.model;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.utils.Quantities;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 3.1.1: one variant of a store's order at the head office, by codes as the store sent it (the store's item id means
 * nothing here). See docs/modules/store-orders.md.
 */
@Entity
@Table(name = "ho_store_order_line")
@Data
@EqualsAndHashCode(callSuper = false, exclude = "order")
@ToString(exclude = "order")
@NoArgsConstructor
public class HoStoreOrderLine extends _BaseEntity {

	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false)
	private HoStoreOrder order;

	@Column(name = "item_code", nullable = false, length = 100)
	private String itemCode;

	@Column(name = "nav_item_no", length = 255)
	private String navItemNo;

	@Column(name = "variant_code", length = 50)
	private String variantCode;

	@Column(length = 255)
	private String description;

	@Column(name = "variant_description", length = 255)
	private String variantDescription;

	@Column(nullable = false, precision = Quantities.PRECISION, scale = Quantities.SCALE)
	private BigDecimal quantity;

	/** The quantity NAV accepted (its quantite) when the order was posted; null: NAV gave none (or not posted yet). */
	@Column(name = "nav_accepted_qty", precision = Quantities.PRECISION, scale = Quantities.SCALE)
	private BigDecimal navAcceptedQty;

	/** NAV's message on the line when the order was posted; null when it gave none. */
	@Column(name = "nav_line_message", length = HoStoreOrder.NAV_MESSAGE_LENGTH)
	private String navLineMessage;

	public BigDecimal getQuantity() {
		return Quantities.normalize(quantity);
	}

	public BigDecimal getNavAcceptedQty() {
		return navAcceptedQty == null ? null : Quantities.normalize(navAcceptedQty);
	}
}
