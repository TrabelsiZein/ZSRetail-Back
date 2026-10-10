package com.digithink.zsretail.model;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.Transient;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.utils.Quantities;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * ItemComposition entity - components of a kit ("Pack") item. The parent item
 * must be of type PACKAGE; components are normal items added to the ticket
 * when the pack is scanned.
 */
@Entity
@Table(name = "item_composition", uniqueConstraints = @UniqueConstraint(name = "uk_item_composition_parent_component", columnNames = {
		"parent_item_id", "component_item_id" }))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ItemComposition extends _BaseEntity {

	@ManyToOne(optional = false)
	@JoinColumn(name = "parent_item_id", nullable = false)
	private Item parentItem;

	@ManyToOne(optional = false)
	@JoinColumn(name = "component_item_id", nullable = false)
	private Item componentItem;

	@Column(nullable = false)
	private Integer quantity = 1;

	/**
	 * 2.2.1: a quantity sent with decimals (1.5), kept apart and never truncated into {@link #quantity}; the service
	 * refuses it naming the component. Not a column, never written in JSON.
	 */
	@Transient
	@JsonIgnore
	@EqualsAndHashCode.Exclude
	private BigDecimal decimalQuantity;

	/** The quantity of a request body, read exactly: 2 and 2.0 give 2, 1.5 stays apart in {@link #decimalQuantity}. */
	@JsonSetter("quantity")
	public void setQuantityAsSent(BigDecimal sent) {
		decimalQuantity = Quantities.isWhole(sent) ? null : sent;
		quantity = sent == null || decimalQuantity != null ? null : sent.stripTrailingZeros().intValueExact();
	}
}
