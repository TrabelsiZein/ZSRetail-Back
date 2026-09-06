package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Location entity - represents physical locations/stores
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class Location extends _BaseEntity {

	@Column(name = "erp_external_id")
	private String erpExternalId;

	@Column(nullable = false, unique = true)
	private String locationCode;

	@Column(nullable = false)
	private String name;

	private String responsibilityCenter;

	private String description;

	private String address;

	private String city;

	private String state;

	private String country;

	private String postalCode;

	private String phone;

	private String email;

	private String contactPerson;

	private String notes;

	private Boolean isDefault = false;
}
