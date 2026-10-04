package com.digithink.zsretail.holink.dto;

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
	/** The confirmation up: null until confirmed, then PENDING, SENT or ERROR. */
	private String pushStatus;
	private String lastError;
	private int lineCount;
	private int quantitySent;
	private Integer quantityReceived;
	private boolean difference;
	/** Lines whose item is not in this store yet. */
	private int missingItems;
	/** Confirmed lines whose stock in waits for their item. */
	private int stockWaiting;
	private List<Line> lines;

	@Data
	@NoArgsConstructor
	public static class Line {
		private int lineNo;
		private String itemCode;
		private String itemName;
		/** False while the item is not in this store. */
		private boolean itemHere;
		private int quantitySent;
		private Integer quantityReceived;
		/** Received minus sent; null until confirmed. */
		private Integer difference;
		/** Null until confirmed. */
		private Boolean stockApplied;
		/** The store stock of the item now; null while the item is not here. */
		private Integer storeStock;
	}
}
