package com.digithink.zsretail.core.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import com.digithink.zsretail.core.model.enumeration.CostChangeSource;
import com.digithink.zsretail.core.utils.Quantities;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.2, the cost history: one row per change of an item's CMP (Item.costPrice), written in the transaction of the
 * change by CostHistoryService (none when the cost does not change, none on a sale), never updated. Where the
 * installation keeps its own stock only. It gives the CMP of a past day to the stock value at a date
 * (ReportService.getStockReport) and the card's "Historique du coût" (GET /item/{id}/cost-history).
 * <p>
 * An append-only log with its own columns (changed_at, changed_by), not a _BaseEntity: no update, no active flag. No
 * foreign key to item: an item may be deleted with its history (db/3.1.2/item-cost-history.sql).
 */
@Entity
@Table(name = "item_cost_history", indexes = @Index(name = "ix_item_cost_history_item", columnList = "item_id, changed_at"))
@Data
@NoArgsConstructor
public class ItemCostHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "item_id", nullable = false)
	private Long itemId;

	@Column(name = "changed_at", nullable = false)
	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime changedAt;

	/** The CMP before; null when the item had none. */
	@Column(name = "old_cost")
	private Double oldCost;

	@Column(name = "new_cost", nullable = false)
	private Double newCost;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private CostChangeSource source;

	/** The document of the change (PURCHASE, ERP_INVOICE, BL), null for a change by hand, an import, a creation. */
	@Column(name = "document_type", length = 20)
	private String documentType;

	@Column(name = "document_number", length = 100)
	private String documentNumber;

	/** The quantity of the entry (an item created or imported: its opening stock). */
	@Column(name = "qty_in", precision = Quantities.PRECISION, scale = Quantities.SCALE)
	private BigDecimal qtyIn;

	/** The net unit cost of the entry (after the line discount, before VAT); the cost typed for the others. */
	@Column(name = "unit_cost_in")
	private Double unitCostIn;

	/** The user's name, or the writer of a change without one (HEAD_OFFICE, Import). */
	@Column(name = "changed_by", length = 100)
	private String changedBy;

	/** The reason typed for a change by hand ("Ajuster le CMP", MANUAL), cut to 500; null for the others. */
	@Column(length = NOTE_LENGTH)
	private String note;

	public static final int NOTE_LENGTH = 500;
}
