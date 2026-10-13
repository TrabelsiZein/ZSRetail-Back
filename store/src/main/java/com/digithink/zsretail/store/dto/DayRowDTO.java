package com.digithink.zsretail.store.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.0: one day of a month, built by StoreDaysService for GET /cashier-session/store-days (docs/modules/cash-sessions.md,
 * "Journal"): its sessions and the tickets and returns of that date. Amounts in TND to the millime.
 */
@Data
@NoArgsConstructor
public class DayRowDTO {

	@JsonFormat(pattern = "yyyy-MM-dd")
	private LocalDate date;

	/** Sessions CLOSED or TERMINATED whose closedAt is on that date (the store ticket Z of the date). */
	private long sessionsClosed;
	/** Sessions opened on that date and still open. */
	private long sessionsOpen;

	/** Finished tickets of that date (by their salesDate, as the sales reports). */
	private long ticketsCount;
	private long returnsCount;
	/** Cancelled tickets of that date (by their salesDate). */
	private long cancelledCount;

	/** The finished tickets' totals. */
	private Double totalTTC;
	/** The returns' amounts, positive. */
	private Double returnsAmount;
	/** totalTTC - returnsAmount. */
	private Double net;

	/** True on today's row while a session is open, whatever its opening day (it sells into today's journal). */
	private boolean inProgress;
}
