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
 * Stock points, step 1: an item as the ERP gives it for one stock point. Written by the items run (never deleted; gone
 * from the point = inactive); a store with this point receives these fields. Plain ids, like the price list lines. The
 * family and the sub-family are kept by code, as the ERP import receives them (familyExternalId, subFamilyExternalId).
 * unitPrice has the meaning of item.unitPrice (before VAT). {@code active} from {@link _BaseEntity}.
 */
@Entity
@Table(name = "ho_stock_point_item",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_stock_point_item", columnNames = { "stock_point_id",
				"item_id" }),
		indexes = @Index(name = "ix_ho_stock_point_item_item", columnList = "item_id"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoStockPointItem extends _BaseEntity {

	/** ho_stock_point.id. */
	@Column(name = "stock_point_id", nullable = false)
	private Long stockPointId;

	/** item.id of the head office. */
	@Column(name = "item_id", nullable = false)
	private Long itemId;

	@Column(nullable = false)
	private String name;

	private String description;

	/** item_family.code; null when the ERP gives none. */
	@Column(name = "family_code")
	private String familyCode;

	/** item_sub_family.code; null when the ERP gives none. */
	@Column(name = "sub_family_code")
	private String subFamilyCode;

	@Column(name = "unit_price", nullable = false)
	private Double unitPrice;
}
