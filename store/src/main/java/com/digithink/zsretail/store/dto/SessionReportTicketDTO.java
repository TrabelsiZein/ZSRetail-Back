package com.digithink.zsretail.store.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.0: the session ticket, built by SessionReportService for GET /cashier-session/{id}/report-ticket: a ticket X for
 * an OPENED session, a ticket Z for a CLOSED or TERMINATED one (docs/modules/cash-sessions.md). Amounts in TND, rounded
 * to the millime; total HT + total VAT + fiscal stamp = total TTC.
 */
@Data
@NoArgsConstructor
public class SessionReportTicketDTO {

	/** "X" (session OPENED) or "Z" (CLOSED or TERMINATED). */
	private String type;

	private Long sessionId;
	private String sessionNumber;
	private SessionStatus status;
	private String cashierName;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime openedAt;

	/** Null on a ticket X. */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime closedAt;

	private Double openingCash;

	private Sales sales;
	private Returns returns;

	/** Sales total TTC minus every return (simple and voucher). */
	private Double netSales;

	/** PAIEMENTS: what each method took (cash: net of the change given). */
	private List<PaymentLine> payments = new ArrayList<>();

	/** CONTRÔLE: per method, expected; on a ticket Z also the cashier's count and the difference. */
	private List<ControlLine> control = new ArrayList<>();

	/** The responsible's verification, only for a TERMINATED session. */
	private Verification verification;

	/** A payment method of the session's tickets was changed after the closing (PaymentChangeLog). */
	private boolean modifiedAfterClosing;
	private long paymentChangesAfterClosing;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime printedAt;
	private String printedBy;

	@Data
	@NoArgsConstructor
	public static class Sales {
		/** Tickets of the session, PENDING and CANCELLED left out. */
		private long ticketsCount;
		private Double totalHT;
		/** One line per VAT rate, the ticket discount and the loyalty points spread over the rates. */
		private List<VatLine> vatLines = new ArrayList<>();
		private Double totalVat;
		/** The fiscal stamp, on its own line (never a VAT 0 % line). */
		private Double taxStamp;
		private Double totalTTC;
		/** Line discounts (manual, promotions, free items), TTC. */
		private Double lineDiscounts;
		/** Ticket-level discounts (SalesHeader.discountAmount). */
		private Double ticketDiscounts;
		/** Loyalty points spent (SalesHeader.loyaltyDeductionAmount). */
		private Double loyaltyDiscounts;
		private long cancelledCount;
		/** Tickets still pending, on a ticket X only (null on a ticket Z: a session closes without any). */
		private Long pendingCount;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class VatLine {
		private int rate;
		private Double baseHT;
		private Double vatAmount;
		private Double totalTTC;
	}

	@Data
	@NoArgsConstructor
	public static class Returns {
		private long count;
		private long simpleCount;
		/** SIMPLE_RETURN: refunded in cash. */
		private Double simpleAmount;
		private long voucherCount;
		/** RETURN_VOUCHER: given as vouchers. */
		private Double voucherAmount;
		private Double totalAmount;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class PaymentLine {
		private String code;
		private String name;
		private boolean cash;
		/** Tickets paid (partly) with this method: a ticket paid with two methods counts under both. */
		private long ticketsCount;
		private Double amount;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ControlLine {
		private String code;
		private String name;
		private boolean cash;
		/** Cash: opening cash + cash sales - change - cash refunds; other methods: their payments. */
		private Double expected;
		/** Null on a ticket X, or when nothing was declared for the method. */
		private Double counted;
		/** counted - expected; null when counted is null. */
		private Double difference;
	}

	@Data
	@NoArgsConstructor
	public static class Verification {
		private String verifiedBy;

		@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
		private LocalDateTime verifiedAt;

		/** True when the responsible counted per method (RESPONSIBLE rows): lines; false: the session total only. */
		private boolean perMethod;
		private List<ControlLine> lines = new ArrayList<>();
		/** Without RESPONSIBLE rows: every method's expected amount added up. */
		private Double totalExpected;
		/** Without RESPONSIBLE rows: responsibleClosureCash. */
		private Double totalCounted;
		private Double totalDifference;
	}
}
