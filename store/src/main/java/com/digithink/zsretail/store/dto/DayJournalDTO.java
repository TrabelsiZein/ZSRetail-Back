package com.digithink.zsretail.store.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.0: the journal of one day, built by DayJournalService for GET /cashier-session/day-journal: every finished
 * ticket, return and cancelled ticket of that day in time order, with the day's totals (docs/modules/cash-sessions.md,
 * "Journal"). Amounts in TND to the millime; a line's totalHT + totalVat + taxStamp = totalTTC.
 */
@Data
@NoArgsConstructor
public class DayJournalDTO {

	public static final String TICKET = "TICKET";
	public static final String RETURN = "RETURN";
	public static final String CANCELLED = "CANCELLED";

	@JsonFormat(pattern = "yyyy-MM-dd")
	private LocalDate date;

	private String storeName;

	private List<JournalLine> lines = new ArrayList<>();

	private Totals totals;

	@Data
	@NoArgsConstructor
	public static class JournalLine {
		/** A ticket (finished or cancelled): its salesDate, as the sales reports; a return: its returnDate. */
		@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
		private LocalDateTime time;

		private String number;
		/** TICKET, RETURN or CANCELLED. */
		private String type;
		private String sessionNumber;
		private String cashierName;
		/** Null for the passenger customer (PASSENGER_CUSTOMER) and for a return. */
		private String customerName;
		/** Negative for a return, 0 for a cancelled ticket. */
		private Double totalHT;
		private Double totalVat;
		/** The fiscal stamp of a ticket (0 for a return or a cancelled ticket), so that HT + VAT + stamp = TTC. */
		private Double taxStamp;
		private Double totalTTC;
		/**
		 * A ticket: what each method took (cash net of the change); a return: the money given back, negative (a simple
		 * return in cash, a return voucher under the voucher method); a cancelled ticket: none.
		 */
		private List<MethodAmount> payments = new ArrayList<>();
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class MethodAmount {
		private String methodName;
		private Double amount;
	}

	@Data
	@NoArgsConstructor
	public static class Totals {
		private long ticketsCount;
		private long returnsCount;
		private long cancelledCount;
		/** The finished tickets only (the returns are in returnsAmount). */
		private Double totalHT;
		private Double totalVat;
		private Double taxStamp;
		private Double totalTTC;
		/** The returns' amounts, positive. */
		private Double returnsAmount;
		/** totalTTC - returnsAmount. */
		private Double net;
		/** Per method, the lines' payments added up (returns included): they add up to net. */
		private List<MethodAmount> payments = new ArrayList<>();
	}
}
