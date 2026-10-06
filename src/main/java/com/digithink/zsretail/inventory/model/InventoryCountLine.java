package com.digithink.zsretail.inventory.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.ForeignKey;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.digithink.zsretail.inventory.enumeration.InventoryLineStatus;
import com.digithink.zsretail.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One line of an {@link InventoryCount}: one item (its file rows merged), or one unknown code. The item is kept as an
 * id without a foreign key, so a draft never blocks the deletion of an item. Written in JDBC batches
 * ({@link com.digithink.zsretail.inventory.repository.InventoryLineStore}), read with JPQL.
 */
@Entity
@Table(name = "inventory_count_line", indexes = @Index(name = "ix_inventory_count_line_count", columnList = "count_id"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class InventoryCountLine extends _BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "count_id", nullable = false, foreignKey = @ForeignKey(name = "fk_inventory_count_line_count"))
	@JsonIgnore
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private InventoryCount count;

	/** The item found, null when NOT_FOUND. */
	@Column(name = "item_id")
	private Long itemId;

	/** The code as read in the file (the first row of the item). */
	@Column(name = "code", length = 100)
	private String code;

	/** Sum of the quantities of the merged rows; null when BAD_QUANTITY or NOT_FOUND without a valid quantity. */
	@Column(name = "counted_quantity")
	private Integer countedQuantity;

	@Column(name = "merged_rows")
	private Integer mergedRows;

	@Column(name = "system_quantity_at_import")
	private Integer systemQuantityAtImport;

	@Column(name = "system_quantity_at_validation")
	private Integer systemQuantityAtValidation;

	/** Counted minus the stock at the validation; 0 when equal; null until validated or when not applied. */
	@Column(name = "difference_applied")
	private Integer differenceApplied;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private InventoryLineStatus status;

	@Column(name = "message", length = 255)
	private String message;
}
