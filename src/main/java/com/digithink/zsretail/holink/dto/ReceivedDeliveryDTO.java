package com.digithink.zsretail.holink.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 7A: a BL as the store reception page reads it (GET /admin/deliveries and /{id}). In the list,
 * {@code lines} is null; the totals are given in both.
 */
@Data
@NoArgsConstructor
public class ReceivedDeliveryDTO {

	private Long id;
	private String number;
	private LocalDate documentDate;
	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime sentAt;
	private String note;
	/** TO_RECEIVE or RECEIVED. */
	private String status;
	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime receivedAt;
	private String receivedBy;
	private String storeNote;
	/** Step 7B: the head office invoice of this BL; null until it arrives. */
	private String invoiceNumber;
	/** The confirmation up: null until confirmed, then PENDING, SENT or ERROR. */
	private String pushStatus;
	private String lastError;
	private int lineCount;
	private BigDecimal quantitySent; // 2.2.1: sums with their decimals
	private BigDecimal quantityReceived;
	private boolean difference;
	/** Lines whose item is not in this store yet. */
	private int missingItems;
	/** Confirmed lines whose stock in waits for their item. */
	private int stockWaiting;
	/** Invoices from the ERP, step (c): BL or ERP_INVOICE. */
	private String documentKind;
	/** ERP invoice: the seller, the customer and the ERP's three totals; null on a BL. */
	private String sellerName;
	private String customerName;
	private Double totalExclVat;
	private Double totalVat;
	private Double totalInclVat;
	private List<Line> lines;

	@Data
	@NoArgsConstructor
	public static class Line {
		private int lineNo;
		private String itemCode;
		private String itemName;
		/** False while the item is not in this store. */
		private boolean itemHere;
		private BigDecimal quantitySent; // 2.2.1: up to 3 decimals
		private BigDecimal quantityReceived;
		/** Received minus sent; null until confirmed. */
		private BigDecimal difference;
		/** Null until confirmed. */
		private Boolean stockApplied;
		/** The store stock of the item now; null while the item is not here. */
		private BigDecimal storeStock; // 2.2.1: up to 3 decimals (a store may sell 0.2)
		/** Invoices from the ERP, step (c): ITEM or OTHER (an amount without item: no quantity). */
		private String lineType;
		/** ERP invoice: the ERP's unit price, line discount, line amount (before VAT) and unit cost; null on a BL. */
		private Double unitPrice;
		private Double lineDiscountPercent;
		private Double lineAmount;
		private Double unitCost;
		/** ERP invoice: true once the cost went into the item. */
		private Boolean costApplied;
		/** ERP invoice: this store's own price of the item, VAT included; null while the item is not here. */
		private Double sellingPrice;
		/** ERP invoice: quantity received (or invoiced, before the reception) x unit cost; null without a cost. */
		private Double lineTotal;
	}
}
