package com.digithink.zsretail.store.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.ControlLine;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.PaymentLine;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.Returns;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.Sales;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.0: the store ticket X (today) or Z (one day), built by StoreReportService: the session tickets of the day's
 * sessions (SessionReportTicketDTO) added up (docs/modules/cash-sessions.md, "Store ticket X / Z"). The blocks have the
 * shapes of the session ticket; amounts in TND to the millime.
 */
@Data
@NoArgsConstructor
public class StoreReportTicketDTO {

	/** "X" (today: the sessions closed today and those open now) or "Z" (the sessions closed on the date). */
	private String type;

	@JsonFormat(pattern = "yyyy-MM-dd")
	private LocalDate date;

	private String storeName;

	/** The sessions added up, oldest opening first. */
	private List<SessionRow> sessions = new ArrayList<>();

	private Sales sales;
	private Returns returns;
	private Double netSales;
	private List<PaymentLine> payments = new ArrayList<>();

	/**
	 * CONTRÔLE, its "Fond de caisse" line: the sessions' opening cash added up. Shown apart, for information: the cash
	 * line's expected amount (and its count) already holds it, as on the session ticket.
	 */
	private Double openingCash;

	/**
	 * CONTRÔLE per method: expected = the sessions' expected added up; counted = their counts added up, null on a ticket
	 * X and when a session declared nothing for the method; difference = counted - expected.
	 */
	private List<ControlLine> control = new ArrayList<>();

	/**
	 * Z only (null on a ticket X): how many of the sessions had a payment method changed after their closing (the
	 * session ticket's modifiedAfterClosing).
	 */
	private Long modifiedAfterClosingCount;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime printedAt;
	private String printedBy;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class SessionRow {
		/** The session's id: the admin page opens its details with it (the session history, ?sessionId=). */
		private Long id;
		private String sessionNumber;
		private String cashierName;

		@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
		private LocalDateTime openedAt;

		@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
		private LocalDateTime closedAt;

		private SessionStatus status;
	}
}
