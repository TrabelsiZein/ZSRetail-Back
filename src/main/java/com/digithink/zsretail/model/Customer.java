package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Customer entity - represents customers in the POS system
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class Customer extends _BaseEntity {

	@Column(name = "erp_external_id")
	private String erpExternalId;

	@Column(nullable = false, unique = true)
	private String customerCode;

	@Column(nullable = false)
	private String name;

	private String email;

	@Column(nullable = false)
	private String phone;

	private String address;

	private String city;

	private String country;

	private String taxId;

	private String taxRegistrationNo;

	private Double creditLimit;

	private String notes;

	private Boolean isDefault = false;

	private String customerPriceGroup;
	private String customerDiscGroup;
	private String auxiliaryIndex1;

	/**
	 * Franchise: location code identifying this customer as a franchise client.
	 * When set, invoices created for this customer will be automatically tagged
	 * with this location code so the franchise client can pull them via the sync API.
	 * Null for regular (non-franchise) customers — no impact on existing behaviour.
	 */
	@Column(name = "default_location")
	private String defaultLocation;
}
