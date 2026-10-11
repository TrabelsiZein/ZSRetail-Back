package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;

import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * ItemFamily entity - top-level grouping for items
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ItemFamily extends _BaseEntity {

	@Column(name = "erp_external_id")
	private String erpExternalId;

	@Column(nullable = false, unique = true)
	private String code;

	@Column(nullable = false)
	private String name;

	private String description;

	private Integer displayOrder = 0;

	/** Filename of the POS image (e.g. "12.jpg"). Null when no image is configured. */
	@Column(name = "image_filename")
	private String imageFilename;

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
