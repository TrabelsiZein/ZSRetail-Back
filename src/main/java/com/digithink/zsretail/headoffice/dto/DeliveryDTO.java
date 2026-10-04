package com.digithink.zsretail.headoffice.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 7A: a BL as the head office pages read it (GET /admin/headoffice/deliveries and /{id}). In the
 * list, {@code lines} is null; the totals are given in both.
 */
@Data
@NoArgsConstructor
public class DeliveryDTO {

	private Long id;
	private String number;
	private Long storeId;
	private String storeCode;
	private String storeName;
	private String status;
	private LocalDate documentDate;
	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime sentAt;
	private String sentBy;
	/** Store clock of the confirmation. */
	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime receivedAt;
	private String receivedBy;
	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime confirmationReceivedAt;
	private String note;
	private String storeNote;
	/** Step 7B: the invoice of the BL (id and number), null until invoiced; why the automatic invoice failed. */
	private Long invoiceId;
	private String invoiceNumber;
	private String invoiceNote;
	private int lineCount;
	private int quantitySent;
	/** Null until received. */
	private Integer quantityReceived;
	/** True when a line was received in another quantity than sent. */
	private boolean difference;
	private List<Line> lines;

	@Data
	@NoArgsConstructor
	public static class Line {
		private int lineNo;
		private Long itemId;
		private String itemCode;
		private String itemName;
		private int quantitySent;
		private Integer quantityReceived;
		/** Received minus sent; null until received. */
		private Integer difference;
		/** The head office stock of the item now. */
		private Integer headOfficeStock;
	}
}
