package com.digithink.zsretail.core.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Item variants (ZEN), step 1: the catégorie, the level above the family (item_family.category_code). Same pattern as
 * {@link ItemFamily}; empty for a customer whose ERP gives no catégorie. Copied to the stores later, like the families.
 */
@Entity
@Table(name = "item_category", uniqueConstraints = @UniqueConstraint(name = "uk_item_category_code", columnNames = "code"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ItemCategory extends _BaseEntity {

	@Column(name = "erp_external_id")
	private String erpExternalId;

	@Column(nullable = false)
	private String code;

	@Column(nullable = false)
	private String name;

	private String description;

	private Integer displayOrder = 0;

	/**
	 * HEAD_OFFICE when received from the head office (copies down of the catalogue); null = LOCAL, made here. Written
	 * only by the pull: never read from JSON, never changed by a save (as {@link ItemFamily#getOrigin()}).
	 */
	@Enumerated(EnumType.STRING)
	@Column(length = 20, updatable = false)
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	private RecordOrigin origin;
}
