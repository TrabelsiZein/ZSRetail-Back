package com.digithink.zsretail.headoffice.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Head office plan, task 6.4: the price of one item in a price list. Plain ids (the item's lines are deleted with the
 * item, a list's lines with the list). The price has the meaning of item.unitPrice.
 */
@Entity
@Table(name = "ho_price_list_line",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_price_list_line", columnNames = { "price_list_id",
				"item_id" }),
		indexes = @Index(name = "ix_ho_price_list_line_item", columnList = "item_id"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoPriceListLine extends _BaseEntity {

	/** ho_price_list.id. */
	@Column(name = "price_list_id", nullable = false)
	private Long priceListId;

	/** item.id of the head office. */
	@Column(name = "item_id", nullable = false)
	private Long itemId;

	@Column(nullable = false)
	private Double price;
}
