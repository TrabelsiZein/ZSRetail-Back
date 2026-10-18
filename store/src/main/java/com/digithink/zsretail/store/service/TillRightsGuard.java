package com.digithink.zsretail.store.service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.service.BadgeService;
import com.digithink.zsretail.store.dto.ProcessReturnRequestDTO;
import com.digithink.zsretail.store.dto.ProcessSaleRequestDTO;
import com.digithink.zsretail.store.exception.TillRightRequiredException;
import com.digithink.zsretail.store.exception.TillRightRequiredException.Reason;
import com.digithink.zsretail.store.model.TillGrant;

/**
 * Step D (3.1.0): the server's check of the till rights on the requests that do the actions (docs/modules/
 * badge-permissions.md). For each action of a request: the logged user's role holds the right → it passes, logged once
 * per request (grantedBy ROLE); else a valid grant of a badge scan came with it (this action, this session, unused, not
 * expired) → it passes, the grant is used and the pass logged (grantedBy BADGE, the badge's owner); else
 * {@link TillRightRequiredException} (403 TILL_RIGHT_REQUIRED). Called inside the request's transaction, before it
 * writes anything: a refusal keeps nothing of the request, grants used by it included.
 * <ul>
 * <li>A sale, a pending save or update, a pending completion: each line with a manual discount (source MANUAL, a
 * percentage or an amount above 0) needs APPLY_LINE_DISCOUNT, the header's manual discount APPLY_TOTAL_DISCOUNT. A
 * pending ticket saved again or completed keeps what it held: a discount not above the one stored (same item; the
 * percentage, else the amount) passes, authorised when it was stored. Discounts of other sources (promotions, price
 * lists) need nothing; the split payment of a table ticket takes its lines from the stored ticket.</li>
 * <li>A return: MAKE_RETURN.</li>
 * <li>Closing with a cash difference: {@link #closeWithDifference}.</li>
 * </ul>
 */
@Service
public class TillRightsGuard {

	/** The amounts and percentages compared (a millime). */
	static final double EPSILON = 0.001;

	@Autowired
	private BadgeService badgeService;

	@Autowired
	private TillGrantService tillGrantService;

	@Autowired
	private SalesLineRepository salesLineRepository;

	/**
	 * The discounts of a sale or pending request.
	 * @param pending the pending ticket saved again or completed (its stored discounts pass), null for a new ticket
	 * @param ref what uses the grants, e.g. "SALE LOC0012610180012"
	 */
	public void checkSale(ProcessSaleRequestDTO request, UserAccount user, CashierSession session, SalesHeader pending,
			String ref) {
		Set<BadgePermission> rolePassed = EnumSet.noneOf(BadgePermission.class);
		List<SalesLine> stored = pending != null ? salesLineRepository.findBySalesHeader(pending)
				: Collections.<SalesLine>emptyList();
		List<ProcessSaleRequestDTO.SaleLineDTO> lines = request.getLines() != null ? request.getLines()
				: Collections.<ProcessSaleRequestDTO.SaleLineDTO>emptyList();
		for (int i = 0; i < lines.size(); i++) {
			ProcessSaleRequestDTO.SaleLineDTO line = lines.get(i);
			if (manual(line.getDiscountSource(), line.getDiscountPercentage(), line.getDiscountAmount())
					&& !creditedLine(line, stored)) {
				require(BadgePermission.APPLY_LINE_DISCOUNT, line.getTillGrant(), i, user, session, ref, rolePassed);
			}
		}
		if (manual(request.getDiscountSource(), request.getDiscountPercentage(), request.getDiscountAmount())
				&& !creditedHeader(request, pending)) {
			require(BadgePermission.APPLY_TOTAL_DISCOUNT, request.getTillGrant(), null, user, session, ref, rolePassed);
		}
	}

	/** A return: the right to make it, or the grant of the scan at the Returns button. */
	public void checkReturn(ProcessReturnRequestDTO request, UserAccount user, CashierSession session, String ref) {
		require(BadgePermission.MAKE_RETURN, request.getTillGrant(), null, user, session, ref,
				EnumSet.noneOf(BadgePermission.class));
	}

	/**
	 * Closing a session with a cash difference. True when it passes: the logged user confirmed the difference and his
	 * role holds the right (ROLE), or a valid grant came (BADGE, used). False when neither a confirmation nor a grant
	 * came: the caller shows the difference (CASH_DISCREPANCY). {@link TillRightRequiredException} when a confirmation
	 * came from a role without the right, or the grant is not valid.
	 */
	public boolean closeWithDifference(UserAccount actor, CashierSession session, boolean confirmed, String token) {
		BadgePermission action = BadgePermission.CLOSE_SESSION_WITH_DISCREPANCY;
		if (confirmed && badgeService.roleHolds(actor, action)) {
			badgeService.logRolePass(actor, action, idOf(session));
			return true;
		}
		if (token != null && !token.trim().isEmpty()) {
			useGrant(action, token, null, session, "CLOSE " + (session != null ? session.getSessionNumber() : ""));
			return true;
		}
		if (confirmed) {
			throw new TillRightRequiredException(action, Reason.NO_GRANT, null);
		}
		return false;
	}

	private void require(BadgePermission action, String token, Integer lineIndex, UserAccount user,
			CashierSession session, String ref, Set<BadgePermission> rolePassed) {
		if (badgeService.roleHolds(user, action)) {
			if (rolePassed.add(action)) {
				badgeService.logRolePass(user, action, idOf(session));
			}
			return;
		}
		useGrant(action, token, lineIndex, session, ref);
	}

	private void useGrant(BadgePermission action, String token, Integer lineIndex, CashierSession session, String ref) {
		TillGrant grant = tillGrantService.valid(token, action, idOf(session), lineIndex);
		tillGrantService.consume(grant, ref, lineIndex);
		badgeService.logBadgePass(grant.getScannedBy(), grant.getBadgeOwner(), grant.getBadgeCode(), action,
				idOf(session));
	}

	/** A manual discount: source MANUAL with a percentage or an amount above 0. */
	static boolean manual(String source, Double percentage, Double amount) {
		return "MANUAL".equals(source) && (positive(percentage) || positive(amount));
	}

	/** A line discount the pending ticket held for the same item: not a larger percentage, else not a larger amount. */
	static boolean creditedLine(ProcessSaleRequestDTO.SaleLineDTO line, List<SalesLine> stored) {
		for (SalesLine held : stored) {
			if (held.getItem() == null || line.getItemId() == null || !line.getItemId().equals(held.getItem().getId())) {
				continue;
			}
			if (notAbove(line.getDiscountPercentage(), line.getDiscountAmount(), held.getDiscountPercentage(),
					held.getDiscountAmount())) {
				return true;
			}
		}
		return false;
	}

	/** The header discount the pending ticket held: not a larger percentage, else not a larger amount. */
	static boolean creditedHeader(ProcessSaleRequestDTO request, SalesHeader pending) {
		return pending != null && notAbove(request.getDiscountPercentage(), request.getDiscountAmount(),
				pending.getDiscountPercentage(), pending.getDiscountAmount());
	}

	/** A percentage asked is compared with the one held, else (or when none was held) the amount with the amount. */
	static boolean notAbove(Double percentage, Double amount, Double heldPercentage, Double heldAmount) {
		if (positive(percentage) && positive(heldPercentage) && percentage <= heldPercentage + EPSILON) {
			return true;
		}
		return positive(amount) && positive(heldAmount) && amount <= heldAmount + EPSILON;
	}

	/** The session of the request (null: none open; a grant then never counts). */
	private static Long idOf(CashierSession session) {
		return session != null ? session.getId() : null;
	}

	private static boolean positive(Double value) {
		return value != null && value > EPSILON;
	}
}
