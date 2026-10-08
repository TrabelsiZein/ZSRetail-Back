package com.digithink.zsretail.holink.model;

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

import com.digithink.zsretail.holink.enumeration.ReceivedDeliveryStatus;
import com.digithink.zsretail.holink.enumeration.ReceivedDocumentKind;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 7A: a BL of the head office at the store, received by the copies down (domain SUPPLY) and
 * confirmed here; the confirmation is sent up until the head office accepts it (push_status, like the other documents
 * up). Store table of the head office link (prefix hol_). See docs/modules/head-office.md, "BLs at the store".
 */
@Entity
@Table(name = "hol_delivery",
		uniqueConstraints = @UniqueConstraint(name = "uk_hol_delivery_number", columnNames = "delivery_number"),
		indexes = @Index(name = "ix_hol_delivery_push", columnList = "push_status, attempts"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ReceivedDelivery extends _BaseEntity {

	public static final int NOTE_LENGTH = 500;
	public static final int USER_LENGTH = 100;
	public static final int LAST_ERROR_LENGTH = 1000;
	public static final int NAME_LENGTH = 200;

	@Column(name = "delivery_number", nullable = false, length = 30)
	private String number;

	@Column(name = "document_date")
	private LocalDate documentDate;

	/** Head office clock of the validation, as sent. */
	@Column(name = "sent_at")
	private LocalDateTime sentAt;

	/** The head office note. */
	@Column(length = NOTE_LENGTH)
	private String note;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReceivedDeliveryStatus status = ReceivedDeliveryStatus.TO_RECEIVE;

	/** Store clock of the confirmation. */
	@Column(name = "received_at")
	private LocalDateTime receivedAt;

	@Column(name = "received_by", length = USER_LENGTH)
	private String receivedBy;

	/** The store note on its confirmation. */
	@Column(name = "store_note", length = NOTE_LENGTH)
	private String storeNote;

	/** The confirmation up: null until confirmed, then PENDING, SENT (accepted by the head office) or ERROR (retried). */
	@Enumerated(EnumType.STRING)
	@Column(name = "push_status", length = 10)
	private SalesCopyStatus pushStatus;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "last_error", length = LAST_ERROR_LENGTH)
	private String lastError;

	@Column(name = "last_push_date")
	private LocalDateTime lastPushDate;

	/** Step 7B: the head office invoice of this BL, once it arrived; null otherwise. */
	@Column(name = "invoice_number", length = 30)
	private String invoiceNumber;

	/** Invoices from the ERP, step (c): a BL or an ERP invoice; null reads as BL (rows before step c). */
	@Enumerated(EnumType.STRING)
	@Column(name = "document_kind", length = 20)
	private ReceivedDocumentKind documentKind = ReceivedDocumentKind.BL;

	/** ERP invoice: the seller named on the purchase invoice. */
	@Column(name = "seller_name", length = NAME_LENGTH)
	private String sellerName;

	/** ERP invoice: the customer of the invoice (this store, as the ERP names it). */
	@Column(name = "customer_name", length = NAME_LENGTH)
	private String customerName;

	/** ERP invoice: the ERP's three totals, copied, never recomputed. */
	@Column(name = "total_excl_vat")
	private Double totalExclVat;

	@Column(name = "total_vat")
	private Double totalVat;

	@Column(name = "total_incl_vat")
	private Double totalInclVat;

	/** BL or ERP_INVOICE; a row without a kind is a BL. */
	public ReceivedDocumentKind kindOrBl() {
		return documentKind == null ? ReceivedDocumentKind.BL : documentKind;
	}

	/** True for an invoice of the ERP. */
	public boolean isErpInvoice() {
		return kindOrBl() == ReceivedDocumentKind.ERP_INVOICE;
	}

	@OneToMany(mappedBy = "delivery", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNo")
	private List<ReceivedDeliveryLine> lines = new ArrayList<>();
}
