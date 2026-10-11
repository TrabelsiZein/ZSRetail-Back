package com.digithink.zsretail.store.rfid.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.ForeignKey;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * RFID tags step: one RFID tag, one piece of an item of the store. Its EPC is unique, trimmed and upper case
 * (RfidTagService.normaliseEpc). A tag is linked to its item by an assignment (manual, or from a delivery reception
 * line) and stays IN_STOCK until the sale makes it SOLD (a later step fills sold_at and the ticket). The item is a
 * real foreign key: an item with tags is not deleted (RfidItemDeletionCheck). The table exists in every store database,
 * empty without rfid.enabled=true (docs/modules/rfid.md).
 */
@Entity
@Table(name = "rfid_tag", uniqueConstraints = @UniqueConstraint(name = "uk_rfid_tag_epc", columnNames = "epc"),
		indexes = { @Index(name = "ix_rfid_tag_item", columnList = "item_id"),
				@Index(name = "ix_rfid_tag_status", columnList = "status") })
@Data
@EqualsAndHashCode(callSuper = false)
@ToString(exclude = "item")
@NoArgsConstructor
public class RfidTag extends _BaseEntity {

	/** The longest EPC kept (an EPC is 96 bits, 24 hex digits, on today's tags; up to 496 bits by the standard). */
	public static final int MAX_EPC_LENGTH = 128;

	@Column(name = "epc", nullable = false, length = MAX_EPC_LENGTH)
	private String epc;

	@JsonIgnore
	@ManyToOne(optional = false)
	@JoinColumn(name = "item_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rfid_tag_item"))
	private Item item;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private RfidTagStatus status = RfidTagStatus.IN_STOCK;

	@Enumerated(EnumType.STRING)
	@Column(name = "source_type", nullable = false, length = 10)
	private RfidTagSource sourceType = RfidTagSource.MANUAL;

	/** The delivery number of a reception (hol_delivery.delivery_number); null for a manual assignment. */
	@Column(name = "source_document", length = 30)
	private String sourceDocument;

	/** The line of that delivery (hol_delivery_line.line_no); null for a manual assignment. */
	@Column(name = "source_line")
	private Integer sourceLine;

	/** When the sale made it SOLD (a later step). */
	@Column(name = "sold_at")
	@JsonFormat(pattern = "yyyy-MM-dd | HH:mm:ss")
	private LocalDateTime soldAt;

	/** The ticket of that sale (sales_header.sales_number, a later step). */
	@Column(name = "ticket_number", length = 100)
	private String ticketNumber;
}
