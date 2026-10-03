package com.digithink.zsretail.holink.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.enumeration.SalesCopyType;
import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Store side of the sales copies (task 2.1): one row per ticket, return or session closing to copy to the head office.
 * It sits beside the documents: no column is added to sales_header, return_header or cashier_session, and the ERP
 * field synchronizationStatus is neither read nor written. Prefix hol_: store tables of the head office link (ho_ is
 * kept for head office tables).
 */
@Entity
@Table(name = "hol_sales_copy",
		uniqueConstraints = @UniqueConstraint(name = "uk_hol_sales_copy_document", columnNames = { "document_type", "local_id" }),
		indexes = @Index(name = "ix_hol_sales_copy_queue", columnList = "document_type, status, attempts, document_date"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class SalesCopy extends _BaseEntity {

	/** Length of last_error; a longer reason is cut. */
	public static final int LAST_ERROR_LENGTH = 1000;

	@Enumerated(EnumType.STRING)
	@Column(name = "document_type", nullable = false, length = 10)
	private SalesCopyType documentType;

	/** Id of the document in this store's database (sales_header, return_header or cashier_session). */
	@Column(name = "local_id", nullable = false)
	private Long localId;

	/** Sales number, return number or session number. */
	@Column(name = "document_number", nullable = false)
	private String documentNumber;

	/** Sales date, return date or session closing date: pending copies are sent oldest first. */
	@Column(name = "document_date")
	private LocalDateTime documentDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private SalesCopyStatus status = SalesCopyStatus.PENDING;

	/**
	 * Pushes of this version of the document that got an answer for it (accepted, rejected) or that the store could
	 * not build. An unreachable head office is not counted. Back to 0 when the document changes.
	 */
	@Column(nullable = false)
	private int attempts;

	/** Reason of the last rejection or build failure; null after an accepted push or a change. */
	@Column(name = "last_error", length = LAST_ERROR_LENGTH)
	private String lastError;

	/** Store clock of the last push that got an answer for this document. */
	@Column(name = "last_push_date")
	private LocalDateTime lastPushDate;

	/**
	 * SHA-256 (hex) of the copy the head office accepted last: a document marked as changed whose copy has the same
	 * hash is marked SENT again without being sent. Null until the first accepted push.
	 */
	@Column(name = "content_hash", length = 64)
	private String contentHash;
}
