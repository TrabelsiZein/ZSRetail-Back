package com.digithink.zsretail.headoffice.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Invoices from the ERP: an invoice as the head office page shows it (GET /admin/headoffice/erp-invoices). Status,
 * mapping and line type by their codes; mappingMessage says the mapping in words with the customer. lines: on GET /{id}
 * only.
 */
@Data
@NoArgsConstructor
public class ErpInvoiceDTO {

	private Long id;
	private String number;
	private String yearPrefix;
	private LocalDate documentDate;
	private LocalDate postingDate;
	private String customerNo;
	private String customerName;
	private Long storeId;
	private String storeCode;
	private String storeName;
	private Double totalExclVat;
	private Double totalVat;
	private Double totalInclVat;
	/** READ, SENT or RECEIVED. */
	private String status;
	/** ASSIGNED, NO_CUSTOMER, NO_STORE, STORE_INACTIVE, STORE_NOT_SUPPLIED; null while held. */
	private String mappingStatus;
	/** E.g. "no store for customer C-0001"; null when assigned or held. */
	private String mappingMessage;
	private boolean held;
	private String holdReason;
	private List<String> warnings = new ArrayList<>();
	/** Item lines whose item is not in the head office catalogue. */
	private int itemsNotInCatalogue;
	private int lineCount;
	private LocalDateTime readAt;
	private LocalDateTime sentAt;
	private LocalDateTime receivedAt;
	private String receivedBy;
	private String storeNote;
	private LocalDateTime confirmationReceivedAt;
	private Boolean difference;
	private List<Line> lines;

	@Data
	@NoArgsConstructor
	public static class Line {
		private Integer lineNo;
		/** ITEM or OTHER. */
		private String type;
		private String itemCode;
		private Long itemId;
		/** True when the item is in the head office catalogue (an ITEM line). */
		private boolean itemHere;
		private String description;
		private Integer quantity;
		private String unitOfMeasure;
		private Double unitPrice;
		private Double lineDiscountPercent;
		private Double lineAmount;
		private Double unitCost;
		private Integer quantityReceived;
		/** quantityReceived - quantity; null before the confirmation. */
		private Integer difference;
	}
}
