package com.digithink.pos.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

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
}
