package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.fasterxml.jackson.annotation.JsonProperty;

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

	/**
	 * Head office plan, step 6: HEAD_OFFICE when received from the head office (copies down of the catalogue); null =
	 * LOCAL, made here. Written only by the pull (on insert, or by a query when a local record of the same code becomes
	 * the head office record): never read from JSON, never changed by a save.
	 */
	@Enumerated(EnumType.STRING)
	@Column(length = 20, updatable = false)
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	private RecordOrigin origin;
}
