package com.digithink.zsretail.store.cashdifference;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.store.cashmovement.dto.OpeningUnverifiedDTO;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.2.0, the differences, step B: GET /cash-differences (docs/modules/cash-sessions.md, "Cash differences"): the
 * sessions of a period with a difference or a remark, newest first and paged, and the summary of the whole period and
 * filters (not only the page).
 */
@Data
@NoArgsConstructor
public class CashDifferencesDTO {

	private LocalDate from;
	private LocalDate to;
	private Long posMachineId;
	private Long cashierId;

	/** The sessions of the period and filters; those listed (with at least one reason). */
	private long periodSessions;
	private long listedSessions;

	/**
	 * True when the user lacks "Voir montants session" (read:view-session-amounts): the cashier's closing difference of a
	 * session not finally closed is not sent (as the Sessions list masks it), nor counted in the summary.
	 */
	private boolean amountsHidden;
	private long hiddenClosingDifferences;
	/**
	 * 3.2.0: the listed lines whose écart d'ouverture is not sent (without "Voir montants session", a session not finally
	 * closed; a 0 too, so the count tells nothing), nor summed.
	 */
	private long hiddenOpeningGaps;

	/** The switch "Afficher les sessions en attente de clôture" as read (off: the waiting-only sessions not listed). */
	private boolean showPending;

	private List<Line> content = new ArrayList<>();
	private long totalElements;
	private int totalPages;
	private int number;
	private int size;

	/** One row per cashier of the listed sessions (by name), then the total of the period. */
	private List<CashierRow> cashiers = new ArrayList<>();
	private CashierRow total;

	/**
	 * Step C, the block "À surveiller": one row per cashier of the period's sessions (every session, whatever the
	 * switch; the same cashiers as "Par caissier"), by name, and the total.
	 */
	private List<WatchRow> watch = new ArrayList<>();
	private WatchRow watchTotal;
	/**
	 * The sessions not finally closed whose returns, dépenses and prélèvements amounts are not sent nor summed (without
	 * "Voir montants session": they enter the expected cash of the blind count); their counts are.
	 */
	private long watchHiddenSessions;

	@Data
	@NoArgsConstructor
	public static class WatchRow {
		private Long cashierId;
		private String cashierName;
		/** The finished tickets (neither PENDING nor CANCELLED, as the Sessions list and the Journal). */
		private long tickets;
		private long cancelledCount;
		/** The cancelled tickets' total as stored (the Journal prints them at 0). */
		private double cancelledAmount;
		private long returnsCount;
		private double returnsAmount;
		/** The tickets with a discount (a line or the ticket) and the discounts, as the tickets X / Z add them. */
		private long discountTickets;
		private double discountAmount;
		/** Of them, the discounts the cashier typed (discount_source MANUAL, line or ticket). */
		private long manualDiscountTickets;
		private double manualDiscountAmount;
		/** The cash movements out of the drawer: DEPENSE and PRELEVEMENT. */
		private long expenseCount;
		private double expenseAmount;
		private long withdrawalCount;
		private double withdrawalAmount;
		/** The refused badge scans in the cashier's sessions. */
		private long refusedAuthorisations;
		/** The cashier's sessions whose amounts are hidden (see watchHiddenSessions). */
		private long hiddenSessions;
	}

	@Data
	@NoArgsConstructor
	public static class Line {
		private Long sessionId;
		private String sessionNumber;
		private LocalDateTime openedAt;
		private LocalDateTime closedAt;
		private Long cashierId;
		private String cashierName;
		private String posMachineName;
		private SessionStatus status;
		/** The cashier's difference at the fermeture (SessionDashboardDTO.cashDifference); null: none or hidden. */
		private Double closingDifference;
		private boolean closingDifferenceHidden;
		/** The responsible's difference at the clôture (SessionDashboardDTO.responsibleDifference); null: none. */
		private Double finalClosingDifference;
		/** The écart d'ouverture (SessionDashboardDTO.openingGap); null: no float proposed. */
		private Double openingGap;
		/** 3.2.0: true when the écart d'ouverture exists and is hidden (openingGap then null). */
		private boolean openingGapHidden;
		/** Opened while the previous session of its drawer was not finally closed; null: no. */
		private OpeningUnverifiedDTO openedWithoutFinalClosing;
		/** Closed by the cashier (CLOSED) and not finally closed when the page is read. */
		private boolean pendingFinalClosing;
	}

	@Data
	@NoArgsConstructor
	public static class CashierRow {
		private Long cashierId;
		private String cashierName;
		/** The listed sessions of the cashier (the switch applied); the other counts are of every session with a reason. */
		private long sessions;
		/** Each total keeps its sign; each shortage is the sum of the negative values alone (0 or below). */
		private double closingTotal;
		private double closingShortage;
		private double finalClosingTotal;
		private double finalClosingShortage;
		private double openingGapTotal;
		private double openingGapShortage;
		private long openedWithoutFinalClosing;
		private long pendingFinalClosing;
	}
}
