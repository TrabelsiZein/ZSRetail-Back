package com.digithink.zsretail.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for reading and updating company information.
 * Maps to the single-row CompanyInformation entity (id=1).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanyInformationDTO {

    private String companyName;
    private String logoBase64;
    private String matriculeFiscal;
    private String address;
    private String city;
    private String postalCode;
    private String country;
    private String phone;
    private String fax;
    private String email;
    private String website;
    private String bankName;
    private String bankAccount;
    private String rib;
    private String invoiceFooterNote;
}
