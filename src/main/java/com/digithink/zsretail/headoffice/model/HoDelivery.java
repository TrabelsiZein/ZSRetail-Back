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

import com.digithink.zsretail.headoffice.enumeration.DeliveryStatus;
import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 7A: a BL (delivery note) from the head office to one store. Head office only data (prefix ho_).
 * The number is given at validation from the head office sequence (BL-000001); a draft has none. No unique constraint on
 * the number: SQL Server allows only one null in a unique column and every draft has one; the sequence (a locked row)
 * makes the numbers unique. No price on a BL (step 7B invoices the received BLs). See docs/modules/head-office.md, "BLs".
 */
@Entity
@Table(name = "ho_delivery", indexes = { @Index(name = "ix_ho_delivery_number", columnList = "delivery_number"),
		@Index(name = "ix_ho_delivery_store_status", columnList = "store_id,status") })
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoDelivery extends _BaseEntity {

	public static final int NUMBER_LENGTH = 30;
	public static final int NOTE_LENGTH = 500;
	public static final int USER_LENGTH = 100;

	/** BL-000001; null while DRAFT. */
	@Column(name = "delivery_number", length = NUMBER_LENGTH)
	private String number;

	/** ho_store.id of the store the goods go to. */
	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DeliveryStatus status = DeliveryStatus.DRAFT;

	@Column(name = "document_date", nullable = false)
	private LocalDate documentDate;

	/** Head office clock of the validation. */
	@Column(name = "sent_at")
	private LocalDateTime sentAt;

	@Column(name = "sent_by", length = USER_LENGTH)
	private String sentBy;

	/** Store clock of the confirmation. */
	@Column(name = "received_at")
	private LocalDateTime receivedAt;

	/** The store user who confirmed (login). */
	@Column(name = "received_by", length = USER_LENGTH)
	private String receivedBy;

	/** Head office clock when the confirmation arrived. */
	@Column(name = "confirmation_received_at")
	private LocalDateTime confirmationReceivedAt;

	@Column(length = NOTE_LENGTH)
	private String note;

	/** The store's note on its confirmation. */
	@Column(name = "store_note", length = NOTE_LENGTH)
	private String storeNote;

	@OneToMany(mappedBy = "delivery", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNo")
	private List<HoDeliveryLine> lines = new ArrayList<>();
}
