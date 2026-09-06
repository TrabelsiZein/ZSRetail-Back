package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * ItemBarcode entity - represents barcodes for items Each item can have
 * multiple barcodes
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ItemBarcode extends _BaseEntity {

	@Column(name = "erp_external_id")
	private String erpExternalId;

	@ManyToOne
	@JoinColumn(name = "item_id", nullable = false)
	private Item item;

	@Column(nullable = false, unique = true)
	private String barcode;

	private String description;

	private Boolean isPrimary = false;
}
