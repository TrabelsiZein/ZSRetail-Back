package com.digithink.zsretail.store.livecash;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.2.0, "Caisse en direct" (GET /cashier-session/live, docs/modules/cash-sessions.md, "Caisse en direct"): the store's
 * day and its drawers now. Without "Voir montants session" ({@code amountsHidden}) no amount is sent: every amount
 * field is null, the counts stay.
 */
@Data
@NoArgsConstructor
public class LiveCashDTO {

	/** When the server read it. */
	private LocalDateTime readAt;
	/** True without "Voir montants session": every amount below is null. */
	private boolean amountsHidden;
	/** CASH_DRAWER_OWNER: CASHIER ("au caissier") or POS_MACHINE ("au poste de caisse"). */
	private String drawerOwner;
	private Day day = new Day();
	/** The POS machines shown (by name), then each open session without POS machine. */
	private List<Drawer> drawers = new ArrayList<>();

	@Data
	@NoArgsConstructor
	public static class Day {
		private long openSessions;
		/** The Journal's day: the finished tickets dated today, every session. */
		private long tickets;
		private Double sales;
		/** The cash expected in all the open drawers (the sum of the open sessions'). */
		private Double expectedCash;
	}

	/** A POS machine, or (posMachineId null) an open session without POS machine. */
	@Data
	@NoArgsConstructor
	public static class Drawer {
		private Long posMachineId;
		private String posMachineName;
		private boolean active = true;
		/** OPEN (a session open on it), WAITING (its latest session fermée, not clôturée) or NONE. */
		private String state;
		/** Its open sessions (one; several only with the drawer "au caissier"). */
		private List<OpenSession> sessions = new ArrayList<>();
		/** Its latest session when it is fermée and waiting for the clôture, whatever its date (else null). */
		private Waiting waiting;
	}

	@Data
	@NoArgsConstructor
	public static class Waiting {
		private Long sessionId;
		private String sessionNumber;
		private String cashierName;
		private LocalDateTime closedAt;
	}

	@Data
	@NoArgsConstructor
	public static class OpenSession {
		private Long sessionId;
		private String sessionNumber;
		private Long cashierId;
		private String cashierName;
		private LocalDateTime openedAt;
		private Double openingCash;
		private long tickets;
		private Double sales;
		private LocalDateTime lastTicketAt;
		private long apportCount;
		private Double apportTotal;
		private long withdrawalCount;
		private Double withdrawalTotal;
		private long expenseCount;
		private Double expenseTotal;
		/** The cash expected in the drawer now: the closing's computation (CashierSessionService.expectedCash). */
		private Double expectedCash;
		private Watch watch = new Watch();
	}

	/** "À surveiller" of the session: the counting of the page Écarts de caisse (CashWatch), this session only. */
	@Data
	@NoArgsConstructor
	public static class Watch {
		private long cancelledCount;
		private Double cancelledAmount;
		private long returnsCount;
		private Double returnsAmount;
		private long discountTickets;
		private Double discountAmount;
		private long manualDiscountTickets;
		private Double manualDiscountAmount;
		private long refusedAuthorisations;
	}
}
