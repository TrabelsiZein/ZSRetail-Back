package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Customer entity - represents customers in the POS system
 */
@Entity
// defaultLocation: field of the franchise profiles removed at step 9 (task 9.4a), still sent by older screens: ignored,
// never refused. Its column default_location stays in the table.
@JsonIgnoreProperties({ "defaultLocation" })
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
}
