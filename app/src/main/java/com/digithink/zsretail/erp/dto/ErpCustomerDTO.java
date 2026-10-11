package com.digithink.zsretail.erp.dto;

import java.time.OffsetDateTime;

public class ErpCustomerDTO implements ErpTimestamped {

	private String externalId;
	private String code;
	private String name;
	private String email;
	private String phone;
	private String address;
	private String city;
	private String country;
	private String taxNumber;
	private Boolean active;
	private OffsetDateTime lastModifiedAt;
	public String customerPriceGroup;
	public String customerDiscGroup;
	public String auxiliaryIndex1;

	public String getCustomerPriceGroup() {
		return customerPriceGroup;
	}

	public void setCustomerPriceGroup(String customerPriceGroup) {
		this.customerPriceGroup = customerPriceGroup;
	}

	public String getCustomerDiscGroup() {
		return customerDiscGroup;
	}

	public void setCustomerDiscGroup(String customerDiscGroup) {
		this.customerDiscGroup = customerDiscGroup;
	}

	public String getAuxiliaryIndex1() {
		return auxiliaryIndex1;
	}

	public void setAuxiliaryIndex1(String auxiliaryIndex1) {
		this.auxiliaryIndex1 = auxiliaryIndex1;
	}

	public OffsetDateTime getLastModifiedAt() {
		return lastModifiedAt;
	}

	public void setLastModifiedAt(OffsetDateTime lastModifiedAt) {
		this.lastModifiedAt = lastModifiedAt;
	}

	public String getExternalId() {
		return externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public String getCity() {
		return city;
	}

	public void setCity(String city) {
		this.city = city;
	}

	public String getCountry() {
		return country;
	}

	public void setCountry(String country) {
		this.country = country;
	}

	public String getTaxNumber() {
		return taxNumber;
	}

	public void setTaxNumber(String taxNumber) {
		this.taxNumber = taxNumber;
	}

	public Boolean getActive() {
		return active;
	}

	public void setActive(Boolean active) {
		this.active = active;
	}
}
