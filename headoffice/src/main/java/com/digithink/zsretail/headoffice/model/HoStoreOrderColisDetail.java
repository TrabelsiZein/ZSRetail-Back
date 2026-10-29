package com.digithink.zsretail.headoffice.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.digithink.zsretail.core.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 3.1.1: the pieces of one variant in one colis of a store order's colis row, as the store sent them, by codes (the
 * store's item id means nothing here). See docs/modules/store-orders.md.
 */
@Entity
@Table(name = "ho_store_order_colis_detail",
		indexes = @Index(name = "ix_ho_store_order_colis_detail_colis", columnList = "colis_id"))
@Data
@EqualsAndHashCode(callSuper = false, exclude = "colis")
@ToString(exclude = "colis")
@NoArgsConstructor
public class HoStoreOrderColisDetail extends _BaseEntity {

	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "colis_id", nullable = false)
	private HoStoreOrderColis colis;

	/** The store's item code (navItemNo~variantCode for a ZEN variant). */
	@Column(name = "item_code", nullable = false, length = 100)
	private String itemCode;

	@Column(name = "variant_code", length = 50)
	private String variantCode;

	@Column(name = "qty_per_colis", nullable = false)
	private int qtyPerColis;

	/** Its size and colour, copied: the composition read again as a matrix (sizes, colours) on the order's pages. */
	@Column(name = "size_code", length = 50)
	private String sizeCode;

	@Column(name = "size_description", length = 255)
	private String sizeDescription;

	@Column(name = "color_code", length = 50)
	private String colorCode;

	@Column(name = "color_description", length = 255)
	private String colorDescription;
}
