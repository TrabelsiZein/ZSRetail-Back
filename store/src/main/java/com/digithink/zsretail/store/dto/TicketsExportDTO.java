package com.digithink.zsretail.store.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.2, the Excel export of the tickets history (GET /sales-header/history/export): the tickets of the list's filters,
 * newest first, and their lines. docs/admin-pages.md, "Tickets History Page", "Excel export".
 */
@Data
@NoArgsConstructor
public class TicketsExportDTO {
	/** The tickets of the filters; tickets holds the newest of them when truncated. */
	private long totalTickets;
	private boolean truncated;
	private int limit;
	/** The unit cost of the lines is sent (the margins right). */
	private boolean withCost;
	/** The payment methods of the tickets, in their display order: one column each. */
	private List<String> paymentMethods = new ArrayList<>();
	private List<Ticket> tickets = new ArrayList<>();
	private List<Line> lines = new ArrayList<>();

	@Data
	@NoArgsConstructor
	public static class Ticket {
		private Long id;
		private String salesNumber;
		@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
		private LocalDateTime salesDate;
		private String status;
		private String cashier;
		private String sessionNumber;
		private String customerCode;
		private String customerName;
		private String loyaltyCardNumber;
		private int lineCount;
		private BigDecimal quantity = BigDecimal.ZERO;
		/** As recorded: before VAT and the VAT of the lines, before the ticket's discount (with VAT). */
		private Double subtotal;
		private Double taxAmount;
		private Double discountAmount;
		private Double totalAmount;
		private Double paidAmount;
		private Double changeAmount;
		/** Amount by payment method name. */
		private Map<String, Double> payments = new LinkedHashMap<>();
		private Integer loyaltyPointsEarned;
		private Integer loyaltyPointsRedeemed;
		private String synchronizationStatus;
		private String erpNo;
		private Boolean invoiced;
		private String invoiceNumber;
		private String fiscalRegistration;
		private String notes;
	}

	@Data
	@NoArgsConstructor
	public static class Line {
		private Long ticketId;
		private String salesNumber;
		@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
		private LocalDateTime salesDate;
		private String itemCode;
		private String itemName;
		private String variantDescription;
		private String familyName;
		private String subFamilyName;
		private BigDecimal quantity;
		private Double unitPrice;
		private Double unitPriceIncludingVat;
		private Double discountPercentage;
		/** With VAT. */
		private Double discountAmount;
		private Integer vatPercent;
		private Double vatAmount;
		private Double lineTotal;
		private Double lineTotalIncludingVat;
		/** Only with withCost. */
		private BigDecimal unitCost;
	}
}
