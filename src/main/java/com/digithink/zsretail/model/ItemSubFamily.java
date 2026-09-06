package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * ItemSubFamily entity - secondary grouping for items under a family
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ItemSubFamily extends _BaseEntity {

	@Column(name = "erp_external_id")
	private String erpExternalId;

	@Column(nullable = false, unique = true)
	private String code;

	@Column(nullable = false)
	private String name;

	private String description;

	private Integer displayOrder = 0;

	@ManyToOne
	@JoinColumn(name = "item_family_id", nullable = false)
	private ItemFamily itemFamily;

	/** Filename of the POS image (e.g. "12.jpg"). Null when no image is configured. */
	@Column(name = "image_filename")
	private String imageFilename;
}
