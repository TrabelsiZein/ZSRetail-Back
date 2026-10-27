package com.digithink.zsretail.store.holink.model;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.utils.Quantities;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 3.1.1: one variant of an order to the head office, one line per item (adding the item again replaces its quantity).
 * The item's codes and descriptions are copied when the line is made: the order says what was asked even if the item
 * changes later. See docs/modules/store-orders.md.
 */
@Entity
@Table(name = "hol_order_line",
		uniqueConstraints = @UniqueConstraint(name = "uk_hol_order_line_item", columnNames = { "order_id", "item_id" }))
@Data
@EqualsAndHashCode(callSuper = false, exclude = "order")
@ToString(exclude = "order")
@NoArgsConstructor
public class StoreOrderLine extends _BaseEntity {

	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false)
	private StoreOrder order;

	@Column(name = "item_id", nullable = false)
	private Long itemId;

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

	/** 2.2.1 precision, as the stock: up to 3 decimals, read without trailing zeros. */
	@Column(nullable = false, precision = Quantities.PRECISION, scale = Quantities.SCALE)
	private BigDecimal quantity;

	public BigDecimal getQuantity() {
		return Quantities.normalize(quantity);
	}
}
