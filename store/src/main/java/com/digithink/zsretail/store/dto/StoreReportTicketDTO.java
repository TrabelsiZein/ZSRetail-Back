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
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.ScannedTickets;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

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

	/** Step 6: the sessions' scanned-tickets blocks added up per method, the issuers merged by name. */
	private List<ScannedTickets> scannedTickets = new ArrayList<>();

	/**
	 * 3.2.0, POS machines step 5: MOUVEMENTS DE CAISSE, the sessions' cash movements added up per type; absent from the
	 * JSON when none of the sessions has one.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private SessionReportTicketDTO.CashMovements cashMovements;

	/**
	 * Z only (null on a ticket X): how many of the sessions had a payment method changed after their closing (the
	 * session ticket's modifiedAfterClosing).
	 */
	private Long modifiedAfterClosingCount;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime printedAt;
	private String printedBy;

	/**
	 * 3.2.0, POS machines step 3: the ticket of one POS machine (?posMachineId=): the same ticket limited to its
	 * sessions, the machine in the header. Absent from the JSON on the store's ticket.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Long posMachineId;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String posMachineName;

	/**
	 * 3.2.0, POS machines step 3: the store's ticket only, and only when the drawer belongs to the POS machine
	 * (CASH_DRAWER_OWNER = POS_MACHINE): the sessions grouped per POS machine, each group with the blocks of this ticket
	 * over its sessions; the machines by name, the sessions without a machine last (posMachineId null). Absent from the
	 * JSON otherwise, so the answer with CASHIER is the one of before.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private List<PosMachineGroup> posMachines;

	/**
	 * 3.2.0, POS machines step 3: one POS machine's sessions of the ticket and their blocks, added up as the ticket's own
	 * (StoreReportService): the same rules, so the groups add up to the ticket.
	 */
	@Data
	@NoArgsConstructor
	public static class PosMachineGroup {
		/** null: the sessions without a POS machine ("—"). */
		private Long posMachineId;
		private String posMachineName;
		private List<SessionRow> sessions = new ArrayList<>();
		private Sales sales;
		private Returns returns;
		private Double netSales;
		private List<PaymentLine> payments = new ArrayList<>();
		private Double openingCash;
		private List<ControlLine> control = new ArrayList<>();
		private List<ScannedTickets> scannedTickets = new ArrayList<>();
		/** 3.2.0, step 5: its sessions' cash movements added up; absent without any. */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		private SessionReportTicketDTO.CashMovements cashMovements;
		/** Z only, null on a ticket X. */
		private Long modifiedAfterClosingCount;
	}

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

		/** 3.2.0, POS machines step 2: the POS machine the session was opened on; null without one. */
		private String posMachineName;
	}
}
