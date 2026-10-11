package com.digithink.zsretail.headoffice.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 7B: the base supply price of a head office item (what a store whose deliveries are invoiced pays
 * when no supply price list line applies). Before VAT, like item.unitPrice. Kept apart from the item: the item form
 * sends the whole item (a column there would be wiped by an edit), and the item copy sent to the stores must never
 * carry it. Head office only data (prefix ho_).
 */
@Entity
@Table(name = "ho_item_supply_price",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_item_supply_price_item", columnNames = "item_id"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoItemSupplyPrice extends _BaseEntity {

	/** item.id of the head office. */
	@Column(name = "item_id", nullable = false)
	private Long itemId;

	@Column(nullable = false)
	private Double price;
}
