package com.digithink.zsretail.store.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.utils.Quantities;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 3.1.2, bulk sale ("vente en vrac"), step 1: a quick quantity the till will offer for a bulk item (Item.soldInBulk),
 * e.g. "100 ml" = 0.1. One list for the store, not per item. linkedItem: an optional container item (e.g. "Flacon 100
 * ml") the till will add as its own line with the bulk line. A store only (table quick_quantity, db/3.1.2/bulk-sale.sql);
 * active comes from _BaseEntity. Checked by QuickQuantityService (label, quantity above 0 with at most 3 decimals). See
 * docs/modules/bulk-sale.md.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class QuickQuantity extends _BaseEntity {

	/** The longest label kept (the column's length). */
	public static final int LABEL_LENGTH = 100;

	/** The button's text at the till, e.g. "100 ml". */
	@Column(nullable = false, length = LABEL_LENGTH)
	private String label;

	/** The quantity in the item's unit (0.1 for 100 ml of an item sold by the litre), up to 3 decimals. */
	@Column(nullable = false, precision = Quantities.PRECISION, scale = Quantities.SCALE)
	private BigDecimal quantity;

	/** The container item added as its own line with the bulk line; null: none. */
	@ManyToOne
	@JoinColumn(name = "linked_item_id")
	private Item linkedItem;

	/** The order of the buttons (lowest first). */
	private Integer displayOrder = 0;

	/** The quantity without trailing zeros (0.100 is 0.1, 1.000 is 1). */
	public BigDecimal getQuantity() {
		return Quantities.normalize(quantity);
	}
}
