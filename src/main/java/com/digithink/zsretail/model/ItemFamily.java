package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;

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
}
