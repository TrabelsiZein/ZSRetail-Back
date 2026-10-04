package com.digithink.zsretail.headoffice.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.OneToMany;
import javax.persistence.OrderBy;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 7B: an invoice of the head office to one store whose deliveries are invoiced, made of the
 * confirmed quantities of one or several received BLs of that store, at the store's supply price of the invoice date.
 * Numbered FHO-yyyy-000001 from a sequence per year. The buyer (the store's billing details) and the seller (the head
 * office company information) are copied when it is created. Paid or unpaid. Never cancelled (a correction is a credit
 * note, later). Head office only data (prefix ho_). See docs/modules/head-office.md, "Supply invoices".
 */
@Entity
@Table(name = "ho_supply_invoice",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_supply_invoice_number", columnNames = "invoice_number"),
		indexes = @Index(name = "ix_ho_supply_invoice_store", columnList = "store_id, paid"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoSupplyInvoice extends _BaseEntity {

	public static final int NOTE_LENGTH = 500;

	@Column(name = "invoice_number", nullable = false, length = 30)
	private String invoiceNumber;

	/** ho_store.id of the buyer. */
	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(name = "invoice_date", nullable = false)
	private LocalDate invoiceDate;

	/** Before VAT. */
	private Double subtotal;

	@Column(name = "tax_amount")
	private Double taxAmount;

	/** Including VAT (and the tax stamp when there is one). */
	@Column(name = "total_amount")
	private Double totalAmount;

	@Column(name = "buyer_name", length = 200)
	private String buyerName;

	@Column(name = "buyer_tax_number", length = 50)
	private String buyerTaxNumber;

	@Column(name = "buyer_address", length = 500)
	private String buyerAddress;

	@Column(name = "seller_name", length = 255)
	private String sellerName;

	@Column(name = "seller_tax_number", length = 100)
	private String sellerTaxNumber;

	@Column(name = "seller_address", length = 500)
	private String sellerAddress;

	@Column(length = NOTE_LENGTH)
	private String note;

	/** False until the head office marks it paid. */
	@Column(nullable = false)
	private Boolean paid = Boolean.FALSE;

	@Column(name = "paid_date")
	private LocalDate paidDate;

	@Column(name = "paid_note", length = NOTE_LENGTH)
	private String paidNote;

	@OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNo")
	private List<HoSupplyInvoiceLine> lines = new ArrayList<>();
}
