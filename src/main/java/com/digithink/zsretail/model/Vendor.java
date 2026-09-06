package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Vendor / Supplier entity - same structure as Customer for consistency.
 * Used in standalone mode only; in ERP mode vendors may be synced from ERP.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class Vendor extends _BaseEntity {

	@Column(name = "erp_external_id")
	private String erpExternalId;

	@Column(nullable = false, unique = true)
	private String vendorCode;

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

	private String notes;
}
