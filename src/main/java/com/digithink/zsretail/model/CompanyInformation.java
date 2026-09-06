package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Lob;
import javax.persistence.Table;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Stores company/store profile information used on all printed documents
 * (receipts, invoices, return vouchers). Always contains exactly one row
 * (id=1), seeded by ZZDataInitializer. No POST/DELETE endpoints exposed.
 */
@Entity
@Table(name = "company_information")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class CompanyInformation extends _BaseEntity {

    @Column(name = "company_name", length = 200)
    private String companyName;

    /** Base64-encoded data-URL of the company logo (e.g. data:image/png;base64,...) */
    @Lob
    @Column(name = "logo_base64", columnDefinition = "TEXT")
    private String logoBase64;

    /** Matricule fiscal (Tunisian tax registration number) — required on legal invoices */
    @Column(name = "matricule_fiscal", length = 100)
    private String matriculeFiscal;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "country", length = 100)
    private String country;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "fax", length = 50)
    private String fax;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "website", length = 200)
    private String website;

    @Column(name = "bank_name", length = 200)
    private String bankName;

    @Column(name = "bank_account", length = 100)
    private String bankAccount;

    /** Relevé d'Identité Bancaire */
    @Column(name = "rib", length = 100)
    private String rib;

    /** Custom footer text shown at the bottom of printed documents */
    @Column(name = "invoice_footer_note", length = 1000)
    private String invoiceFooterNote;
}
