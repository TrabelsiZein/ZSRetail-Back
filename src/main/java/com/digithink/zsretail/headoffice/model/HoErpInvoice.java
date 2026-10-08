package com.digithink.zsretail.headoffice.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Index;
import javax.persistence.OneToMany;
import javax.persistence.OrderBy;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceMapping;
import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceStatus;
import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Invoices from the ERP, step (b): an invoice of the ERP to a franchise store, read by its number (headoffice.supply
 * .source=ERP). Head office only data (prefix ho_). Saved once per number (uk_ho_erp_invoice_number) without a store;
 * the store is found afterwards by its ERP customer number (ho_store.erp_customer_no). The three totals are the ERP's,
 * never recomputed. A held invoice (a quantity not whole, prices including the VAT) is never given to a store. See
 * docs/modules/head-office.md, "Invoices from the ERP".
 */
@Entity
@Table(name = "ho_erp_invoice",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_erp_invoice_number", columnNames = "bc_number"),
		indexes = { @Index(name = "ix_ho_erp_invoice_store_status", columnList = "store_id,status"),
				@Index(name = "ix_ho_erp_invoice_year", columnList = "year_prefix,bc_number") })
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoErpInvoice extends _BaseEntity {

	public static final int NUMBER_LENGTH = 30;
	public static final int CUSTOMER_NO_LENGTH = 100;
	public static final int CUSTOMER_NAME_LENGTH = 200;
	public static final int NOTE_LENGTH = 500;
	public static final int USER_LENGTH = 100;
	public static final int WARNINGS_LENGTH = 2000;

	/** The ERP's number, e.g. FVV26000000123. */
	@Column(name = "bc_number", nullable = false, length = NUMBER_LENGTH)
	private String bcNumber;

	/** The number's start that names its year (FVV26): the head office's highest number is kept per year prefix. */
	@Column(name = "year_prefix", length = 20)
	private String yearPrefix;

	@Column(name = "document_date")
	private LocalDate documentDate;

	@Column(name = "posting_date")
	private LocalDate postingDate;

	/** The invoice's customer, as the configured customer field gives it (erp.navpospages.invoices.customer-field). */
	@Column(name = "customer_no", length = CUSTOMER_NO_LENGTH)
	private String customerNo;

	@Column(name = "customer_name", length = CUSTOMER_NAME_LENGTH)
	private String customerName;

	/** ho_store.id of the store whose ERP customer number is customer_no; null until found. */
	@Column(name = "store_id")
	private Long storeId;

	@Column(name = "total_excl_vat")
	private Double totalExclVat;

	@Column(name = "total_vat")
	private Double totalVat;

	@Column(name = "total_incl_vat")
	private Double totalInclVat;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ErpInvoiceStatus status = ErpInvoiceStatus.READ;

	/** The last search of the store; null while held (never searched). */
	@Enumerated(EnumType.STRING)
	@Column(name = "mapping_status", length = 30)
	private ErpInvoiceMapping mappingStatus;

	/** True when the invoice cannot be used as it is (hold_reason): never given to a store. */
	@Column(nullable = false)
	private Boolean held = Boolean.FALSE;

	@Column(name = "hold_reason", length = NOTE_LENGTH)
	private String holdReason;

	/** What to look at, one per line: the connector's warnings, the items not in the head office catalogue. */
	@Column(length = WARNINGS_LENGTH)
	private String warnings;

	/** Head office clock when it was read from the ERP. */
	@Column(name = "read_at")
	private LocalDateTime readAt;

	/** Head office clock when its store was found. */
	@Column(name = "sent_at")
	private LocalDateTime sentAt;

	/** Store clock of the confirmation. */
	@Column(name = "received_at")
	private LocalDateTime receivedAt;

	@Column(name = "received_by", length = USER_LENGTH)
	private String receivedBy;

	@Column(name = "store_note", length = NOTE_LENGTH)
	private String storeNote;

	/** Head office clock when the confirmation arrived. */
	@Column(name = "confirmation_received_at")
	private LocalDateTime confirmationReceivedAt;

	/** True when a line was received in another quantity than invoiced; null before the confirmation. */
	@Column(name = "difference")
	private Boolean difference;

	@OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNo")
	private List<HoErpInvoiceLine> lines = new ArrayList<>();
}
