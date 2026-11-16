package com.digithink.zsretail.store.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.cashmovement.dto.CashMovementDTO;
import com.digithink.zsretail.store.cashmovement.dto.ClosingWithdrawalDTO;
import com.digithink.zsretail.store.cashmovement.dto.OpeningFloatDTO;
import com.digithink.zsretail.store.dto.SessionDashboardDTO;

/**
 * 3.2.0: the one rule of "Voir montants session" (read:view-session-amounts, docs/modules/cash-sessions.md, "Amounts
 * hidden"). Without it, the amounts that enter or reveal the expected cash of a session not finally closed (OPENED or
 * CLOSED) are not sent: the responsible's count at the clôture stays blind. Counts are kept; a TERMINATED session is
 * sent whole. Used by the Sessions list, the session details, the session ticket X / Z and the page Écarts de caisse.
 * A user with the right gets every answer unchanged.
 * <p>
 * The cashier's own count at the fermeture (posUserClosureCash, the POS_USER count rows) is hidden too: it is close to
 * the expected cash, so showing it would tell the responsible what to find. The session ticket is refused (403), not
 * masked, except to the session's own cashier (the till prints their ticket Z when they close, CASHIER_CLOSING_TICKET).
 */
public final class SessionAmounts {

	public static final String PERMISSION = "read:view-session-amounts";

	private SessionAmounts() {
	}

	/** The logged user holds "Voir montants session". */
	public static boolean visible(CurrentUserProvider currentUserProvider) {
		return currentUserProvider != null && currentUserProvider.currentUserHasPermission(PERMISSION);
	}

	/** The rule: hidden without the right while the session is not finally closed. */
	public static boolean hidden(boolean amountsVisible, SessionStatus status) {
		return !amountsVisible && status != SessionStatus.TERMINATED;
	}

	/**
	 * The session ticket X / Z: with the right, a finally closed session, or the session's own cashier (the till's ticket
	 * Z at his fermeture); anyone else is refused.
	 */
	public static boolean ticketAllowed(boolean amountsVisible, CashierSession session, UserAccount user) {
		if (!hidden(amountsVisible, session.getStatus())) {
			return true;
		}
		return user != null && user.getId() != null && session.getCashier() != null
				&& user.getId().equals(session.getCashier().getId());
	}

	/** A line of the Sessions list (or the details' session) masked: its amounts null, its counts kept. */
	public static void mask(SessionDashboardDTO dto) {
		dto.setOpeningCash(null);
		dto.setPosUserClosureCash(null);
		dto.setTotalSalesAmount(null);
		dto.setTotalReturnsAmount(null);
		dto.setSimpleReturnsAmount(null);
		dto.setVoucherReturnsAmount(null);
		dto.setCashDifference(null);
		dto.setResponsibleDifference(null);
		dto.setOpeningGap(null);
		dto.setAmountsHidden(true);
	}

	/**
	 * The session details (CashierSessionService.getSessionDetails) masked in place: the session's amounts, the totals,
	 * the system and counted amounts per method, the cash movements' amounts, the opening float; the tickets, returns and
	 * payment documents (entities that carry amounts, not read by the popup) not sent. Counts, names, dates and the ERP
	 * statuses stay.
	 */
	@SuppressWarnings("unchecked")
	public static void maskDetails(Map<String, Object> details) {
		Object session = details.get("session");
		if (session instanceof SessionDashboardDTO) {
			mask((SessionDashboardDTO) session);
		}
		details.put("totalSalesAmount", null);
		details.put("totalReturnsAmount", null);
		details.put("simpleReturnsAmount", null);
		details.put("voucherReturnsAmount", null);
		details.put("sales", new ArrayList<>());
		details.put("returns", new ArrayList<>());
		details.put("paymentHeaders", new ArrayList<>());
		Object summary = details.get("paymentSummary");
		if (summary instanceof List) {
			for (Map<String, Object> row : (List<Map<String, Object>>) summary) {
				for (String key : new String[] { "systemAmount", "netAmount", "posClosureAmount", "deltaPOS",
						"respClosureAmount", "deltaResp", "systemCount", "posClosureCount", "deltaCountPOS",
						"respClosureCount", "deltaCountResp" }) {
					if (row.containsKey(key)) {
						row.put(key, null);
					}
				}
			}
		}
		Object movements = details.get("cashMovements");
		if (movements instanceof List) {
			for (Object movement : (List<Object>) movements) {
				if (movement instanceof CashMovementDTO) {
					((CashMovementDTO) movement).setAmount(null);
				}
			}
		}
		Object opening = details.get("openingFloat");
		if (opening instanceof OpeningFloatDTO) {
			OpeningFloatDTO dto = (OpeningFloatDTO) opening;
			dto.setProposedFloat(null);
			dto.setOpeningCash(null);
			dto.setOpeningGap(null);
		}
		Object withdrawal = details.get("closingWithdrawal");
		if (withdrawal instanceof ClosingWithdrawalDTO) { // only after the clôture (TERMINATED); kept off all the same
			details.put("closingWithdrawal", null);
		}
		details.put("amountsHidden", true);
	}
}
