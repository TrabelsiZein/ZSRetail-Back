package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.BadgeScanLog;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.model.enumeration.GrantedBy;
import com.digithink.zsretail.store.dto.ProcessReturnRequestDTO;
import com.digithink.zsretail.store.dto.ProcessSaleRequestDTO;
import com.digithink.zsretail.store.exception.TillRightRequiredException;
import com.digithink.zsretail.store.exception.TillRightRequiredException.Reason;
import com.digithink.zsretail.store.support.InMemoryTillRights;

/**
 * Till authorisation, step D (3.1.0): the server's check of the till rights on the requests (TillRightsGuard), with the
 * real TillGrantService and BadgeService over in-memory grants and scan log. The matrix of a sale, a pending save and
 * completion, and a return: the role's right, a badge's grant (used once, of its action and session, not expired), the
 * discounts that need nothing, what a pending ticket held, and each refusal with its reason and line.
 */
class TillRightsGuardTest {

	private static final BadgePermission LINE = BadgePermission.APPLY_LINE_DISCOUNT;
	private static final BadgePermission TOTAL = BadgePermission.APPLY_TOTAL_DISCOUNT;
	private static final BadgePermission RETURN = BadgePermission.MAKE_RETURN;

	private InMemoryTillRights rights;
	private TillRightsGuard guard;
	private final CashierSession session = session(7L);
	private final UserAccount cashier = user("cashier", "POS_USER");
	private final UserAccount holder = user("chef", "CHEF_CAISSE", LINE, TOTAL, RETURN);
	private final UserAccount responsible = user("responsible", "RESPONSIBLE", LINE, TOTAL, RETURN);

	@BeforeEach
	void setUp() {
		rights = new InMemoryTillRights();
		guard = rights.guard;
	}

	private static CashierSession session(long id) {
		CashierSession session = new CashierSession();
		session.setId(id);
		session.setSessionNumber("SESSION" + id);
		return session;
	}

	private static UserAccount user(String login, String roleName, BadgePermission... held) {
		UserAccount user = new UserAccount();
		user.setUsername(login);
		user.setId((long) login.hashCode());
		AppRole role = new AppRole(roleName, roleName, "POS_USER".equals(roleName));
		role.setPermissions(new HashSet<>(Arrays.asList("read:cashier-interface")));
		for (BadgePermission action : held) {
			role.getPermissions().add(action.key());
		}
		user.setAppRole(role);
		return user;
	}

	private static ProcessSaleRequestDTO.SaleLineDTO line(long itemId, String source, Double percentage, Double amount) {
		ProcessSaleRequestDTO.SaleLineDTO line = new ProcessSaleRequestDTO.SaleLineDTO();
		line.setItemId(itemId);
		line.setDiscountSource(source);
		line.setDiscountPercentage(percentage);
		line.setDiscountAmount(amount);
		return line;
	}

	private static ProcessSaleRequestDTO sale(ProcessSaleRequestDTO.SaleLineDTO... lines) {
		ProcessSaleRequestDTO request = new ProcessSaleRequestDTO();
		request.setLines(new ArrayList<>(Arrays.asList(lines)));
		return request;
	}

	private static ProcessSaleRequestDTO withHeader(ProcessSaleRequestDTO request, String source, Double percentage,
			Double amount) {
		request.setDiscountSource(source);
		request.setDiscountPercentage(percentage);
		request.setDiscountAmount(amount);
		return request;
	}

	private TillRightRequiredException refused(Runnable request) {
		return assertThrows(TillRightRequiredException.class, request::run);
	}

	private void checkSale(ProcessSaleRequestDTO request, UserAccount user) {
		guard.checkSale(request, user, session, null, "SALE T1");
	}

	// ─── Sales ─────────────────────────────────────────────────────

	@Test
	@DisplayName("Role holding the rights: manual line and header discounts pass, no grant; one ROLE row per action and request")
	void roleRightPasses() {
		ProcessSaleRequestDTO request = withHeader(sale(line(1, "MANUAL", 10.0, 2.0), line(2, "MANUAL", null, 1.5),
				line(3, null, null, null)), "MANUAL", 5.0, 3.0);

		assertDoesNotThrow(() -> checkSale(request, holder));

		assertEquals(2, rights.log.size(), "APPLY_LINE_DISCOUNT once (two lines), APPLY_TOTAL_DISCOUNT once");
		assertEquals(Arrays.asList(LINE, TOTAL), Arrays.asList(rights.log.get(0).getFunctionality(),
				rights.log.get(1).getFunctionality()));
		for (BadgeScanLog row : rights.log) {
			assertEquals(GrantedBy.ROLE, row.getGrantedBy());
			assertSame(holder, row.getScannedBy());
			assertSame(holder, row.getScannedUser());
			assertNull(row.getScannedBadgeCode());
			assertEquals(7L, row.getSessionId());
		}
		assertTrue(rights.grants.isEmpty());
	}

	@Test
	@DisplayName("Without the right and without a grant: 403 for the first manual line, its index; then for the header")
	void noRightNoGrant() {
		rights.promotion(50, true);
		ProcessSaleRequestDTO request = sale(promoted(1, 50L, 20.0), line(2, "MANUAL", 10.0, 1.0));
		TillRightRequiredException lineRefused = refused(() -> checkSale(request, cashier));
		assertEquals(LINE, lineRefused.getAction());
		assertEquals(Reason.NO_GRANT, lineRefused.getReason());
		assertEquals(Integer.valueOf(1), lineRefused.getLineIndex());
		assertEquals(1, lineRefused.body().get("lineIndex"));
		assertEquals("APPLY_LINE_DISCOUNT", lineRefused.body().get("action"));

		TillRightRequiredException headerRefused = refused(
				() -> checkSale(withHeader(sale(line(1, null, null, null)), "MANUAL", null, 5.0), cashier));
		assertEquals(TOTAL, headerRefused.getAction());
		assertNull(headerRefused.getLineIndex());
		assertTrue(rights.log.isEmpty());
	}

	private static ProcessSaleRequestDTO.SaleLineDTO promoted(long itemId, Long promotionId, Double percentage) {
		ProcessSaleRequestDTO.SaleLineDTO line = line(itemId, "PROMOTION", percentage, 1.0);
		line.setPromotionId(promotionId);
		return line;
	}

	@Test
	@DisplayName("Sources the server verifies need nothing: an active promotion, the item's price list (not a larger percentage), a cart promotion; no discount at all needs nothing either")
	void verifiedSourcesNeedNothing() {
		rights.promotion(50, true);
		rights.promotion(60, true);
		rights.priceListDiscounts.put(2L, 5.0);
		rights.priceListDiscounts.put(3L, 8.0);
		ProcessSaleRequestDTO request = sale(promoted(1, 50L, 100.0), line(2, "SALES_DISCOUNT", 5.0, 1.0),
				line(3, "SALES_PRICE", 8.0, 0.5), line(4, null, null, null), line(5, "MANUAL", 0.0, 0.0),
				line(6, "FOO", null, null));
		withHeader(request, "PROMOTION", 10.0, 8.0).setPromotionId(60L);
		assertDoesNotThrow(() -> checkSale(request, cashier));
		assertTrue(rights.log.isEmpty());
	}

	@Test
	@DisplayName("A price list source is verified for the customer the till priced for: the request's, else the default customer (as POST /price-calculate)")
	void priceListCustomer() {
		com.digithink.zsretail.core.model.Customer passenger = new com.digithink.zsretail.core.model.Customer();
		rights.defaultCustomer = passenger;
		rights.priceListDiscounts.put(2L, 5.0);
		assertDoesNotThrow(() -> checkSale(sale(line(2, "SALES_DISCOUNT", 5.0, 1.0)), cashier));
		assertEquals(java.util.Collections.singletonList(passenger), rights.pricedFor, "no customer id: the default one");
	}

	@Test
	@DisplayName("A source missing, unknown or not verified counts as MANUAL: 403 NO_GRANT for the line, passes on the role's right or a grant")
	void unverifiedSourcesCountAsManual() {
		rights.promotion(51, false); // inactive
		rights.priceListDiscounts.put(12L, 5.0);
		Object[][] rows = {
				{ line(10, null, 10.0, 1.0), "no source" },
				{ line(10, "FOO", 10.0, 1.0), "an unknown source" },
				{ line(10, "", null, 1.0), "an empty source" },
				{ promoted(10, null, 10.0), "PROMOTION without a promotion" },
				{ promoted(10, 999L, 10.0), "PROMOTION of an unknown promotion" },
				{ promoted(10, 51L, 10.0), "PROMOTION of an inactive promotion" },
				{ line(11, "SALES_DISCOUNT", 5.0, 1.0), "SALES_DISCOUNT on an item without a price list discount" },
				{ line(12, "SALES_DISCOUNT", 7.0, 1.0), "SALES_DISCOUNT above the price list's 5%" },
				{ line(12, "SALES_PRICE", 9.0, 1.0), "SALES_PRICE above the price list's 5%" },
		};
		for (Object[] row : rows) {
			ProcessSaleRequestDTO.SaleLineDTO line = (ProcessSaleRequestDTO.SaleLineDTO) row[0];
			TillRightRequiredException refused = refused(() -> checkSale(sale(line), cashier));
			assertEquals(LINE, refused.getAction(), (String) row[1]);
			assertEquals(Reason.NO_GRANT, refused.getReason(), (String) row[1]);
			assertEquals(Integer.valueOf(0), refused.getLineIndex(), (String) row[1]);

			assertDoesNotThrow(() -> checkSale(sale(line), holder), (String) row[1] + ", role's right");
			line.setTillGrant(rights.grant(LINE, 7L, cashier, responsible));
			assertDoesNotThrow(() -> checkSale(sale(line), cashier), (String) row[1] + ", a grant");
		}
		// the header: a discount without a source, or a cart promotion that is not one, needs the total right
		ProcessSaleRequestDTO noSource = withHeader(sale(line(1, null, null, null)), null, null, 5.0);
		assertEquals(TOTAL, refused(() -> checkSale(noSource, cashier)).getAction());
		ProcessSaleRequestDTO fakeCart = withHeader(sale(line(1, null, null, null)), "PROMOTION", 10.0, 5.0);
		fakeCart.setPromotionId(999L);
		assertEquals(TOTAL, refused(() -> checkSale(fakeCart, cashier)).getAction());
	}

	@Test
	@DisplayName("A pending ticket of before (its lines stored without a source): recalled unchanged, its discounts count as MANUAL and pass on what it held; a larger one asks")
	void oldPendingWithoutSources() {
		rights.storedLines.add(stored(1, 10.0, 2.0)); // no source, as before this fix
		SalesHeader held = pending(null, null);
		assertDoesNotThrow(() -> guard.checkSale(sale(line(1, null, 10.0, 2.0)), cashier, session, held, "SALE P40"),
				"no re-ask for the discount it held");
		assertTrue(rights.log.isEmpty());
		assertEquals(LINE, refused(() -> guard.checkSale(sale(line(1, null, 12.0, 2.4)), cashier, session, held,
				"SALE P40")).getAction());
	}

	@Test
	@DisplayName("A badge's grant per discounted line and for the header: passes, each used once, BADGE rows with the owner and the session")
	void grantsPass() {
		ProcessSaleRequestDTO.SaleLineDTO first = line(1, "MANUAL", 10.0, 2.0);
		ProcessSaleRequestDTO.SaleLineDTO second = line(2, "MANUAL", null, 1.0);
		first.setTillGrant(rights.grant(LINE, 7L, cashier, responsible));
		second.setTillGrant(rights.grant(LINE, 7L, cashier, responsible));
		ProcessSaleRequestDTO request = withHeader(sale(first, second), "MANUAL", 5.0, 3.0);
		request.setTillGrant(rights.grant(TOTAL, 7L, cashier, responsible));

		assertDoesNotThrow(() -> checkSale(request, cashier));

		assertTrue(rights.grants.values().stream().allMatch(g -> g.getConsumedAt() != null && "SALE T1".equals(
				g.getConsumedByRef())));
		assertEquals(3, rights.log.size());
		for (BadgeScanLog row : rights.log) {
			assertEquals(GrantedBy.BADGE, row.getGrantedBy());
			assertSame(cashier, row.getScannedBy());
			assertSame(responsible, row.getScannedUser());
			assertEquals("B-responsible", row.getScannedBadgeCode());
			assertEquals(7L, row.getSessionId());
			assertTrue(row.getSuccess());
		}

		// the same tokens again: used
		TillRightRequiredException again = refused(() -> checkSale(request, cashier));
		assertEquals(Reason.GRANT_USED, again.getReason());
		assertEquals(Integer.valueOf(0), again.getLineIndex());
	}

	@Test
	@DisplayName("Grants that do not count: another action, another session, expired, unknown, used by another request first")
	void grantsRefused() {
		ProcessSaleRequestDTO.SaleLineDTO wrongAction = line(1, "MANUAL", 10.0, 2.0);
		wrongAction.setTillGrant(rights.grant(TOTAL, 7L, cashier, responsible));
		assertEquals(Reason.GRANT_OTHER_ACTION, refused(() -> checkSale(sale(wrongAction), cashier)).getReason());

		ProcessSaleRequestDTO.SaleLineDTO otherSession = line(1, "MANUAL", 10.0, 2.0);
		otherSession.setTillGrant(rights.grant(LINE, 8L, cashier, responsible));
		assertEquals(Reason.GRANT_OTHER_SESSION, refused(() -> checkSale(sale(otherSession), cashier)).getReason());
		assertEquals(Reason.GRANT_OTHER_SESSION, refused(() -> guard.checkSale(sale(otherSession), cashier, null, null,
				"SALE T1")).getReason(), "no open session: a grant never counts");

		ProcessSaleRequestDTO.SaleLineDTO expired = line(1, "MANUAL", 10.0, 2.0);
		expired.setTillGrant(rights.grant(LINE, 7L, cashier, responsible));
		rights.last().setExpiresAt(rights.last().getIssuedAt());
		assertEquals(Reason.GRANT_EXPIRED, refused(() -> checkSale(sale(expired), cashier)).getReason());

		ProcessSaleRequestDTO.SaleLineDTO unknown = line(1, "MANUAL", 10.0, 2.0);
		unknown.setTillGrant("not-a-token");
		assertEquals(Reason.GRANT_UNKNOWN, refused(() -> checkSale(sale(unknown), cashier)).getReason());

		ProcessSaleRequestDTO.SaleLineDTO raced = line(1, "MANUAL", 10.0, 2.0);
		raced.setTillGrant(rights.grant(LINE, 7L, cashier, responsible));
		rights.racesLost = 1;
		assertEquals(Reason.GRANT_USED, refused(() -> checkSale(sale(raced), cashier)).getReason());

		assertTrue(rights.log.isEmpty(), "no pass logged");
	}

	@Test
	@DisplayName("A role holding only the line right: its lines pass (ROLE), the header still needs a grant")
	void rightsAreSeparate() {
		UserAccount linesOnly = user("lines", "LINES_ONLY", LINE);
		ProcessSaleRequestDTO request = withHeader(sale(line(1, "MANUAL", 10.0, 2.0)), "MANUAL", 5.0, 3.0);
		TillRightRequiredException header = refused(() -> checkSale(request, linesOnly));
		assertEquals(TOTAL, header.getAction());
		request.setTillGrant(rights.grant(TOTAL, 7L, linesOnly, responsible));
		assertDoesNotThrow(() -> checkSale(request, linesOnly));
		assertEquals(Arrays.asList(GrantedBy.ROLE, GrantedBy.ROLE, GrantedBy.BADGE),
				Arrays.asList(rights.log.get(0).getGrantedBy(), rights.log.get(1).getGrantedBy(),
						rights.log.get(2).getGrantedBy()), "the refused request logged its ROLE pass, rolled back with it");
	}

	// ─── Pending tickets ───────────────────────────────────────────

	private static SalesLine stored(long itemId, Double percentage, Double amount) {
		Item item = new Item();
		item.setId(itemId);
		SalesLine line = new SalesLine();
		line.setItem(item);
		line.setDiscountPercentage(percentage);
		line.setDiscountAmount(amount);
		return line;
	}

	private static SalesHeader pending(Double percentage, Double amount) {
		SalesHeader header = new SalesHeader();
		header.setId(40L);
		header.setDiscountPercentage(percentage);
		header.setDiscountAmount(amount);
		return header;
	}

	@Test
	@DisplayName("Pending ticket saved again or completed: a discount it held passes (not a larger percentage, or not a larger amount); a new or larger one needs the right")
	void pendingCredit() {
		rights.storedLines.addAll(Arrays.asList(stored(1, 10.0, 2.0), stored(2, null, 1.5)));
		SalesHeader held = pending(5.0, 3.0);

		ProcessSaleRequestDTO same = withHeader(sale(line(1, "MANUAL", 10.0, 2.0), line(2, "MANUAL", null, 1.5)),
				"MANUAL", 5.0, 3.0);
		assertDoesNotThrow(() -> guard.checkSale(same, cashier, session, held, "SALE P40"));
		ProcessSaleRequestDTO moreItems = withHeader(sale(line(1, "MANUAL", 10.0, 4.0)), "MANUAL", 2.5, 3.0);
		assertDoesNotThrow(() -> guard.checkSale(moreItems, cashier, session, held, "SALE P40"),
				"same rate on more pieces; same amount on a larger total");
		assertTrue(rights.log.isEmpty(), "authorised and logged when the ticket was stored");

		TillRightRequiredException larger = refused(() -> guard.checkSale(sale(line(1, "MANUAL", 15.0, 3.0)), cashier,
				session, held, "SALE P40"));
		assertEquals(LINE, larger.getAction());
		TillRightRequiredException newLine = refused(() -> guard.checkSale(sale(line(3, "MANUAL", 5.0, 1.0)), cashier,
				session, held, "SALE P40"));
		assertEquals(Integer.valueOf(0), newLine.getLineIndex());
		TillRightRequiredException largerHeader = refused(() -> guard.checkSale(
				withHeader(sale(line(9, null, null, null)), "MANUAL", 8.0, 6.0), cashier, session, held, "SALE P40"));
		assertEquals(TOTAL, largerHeader.getAction());
		TillRightRequiredException newTicket = refused(() -> guard.checkSale(same, cashier, session, null, "SALE T2"));
		assertEquals(LINE, newTicket.getAction(), "a new ticket is credited nothing");
	}

	// ─── Returns ───────────────────────────────────────────────────

	@Test
	@DisplayName("A return: the role's right (ROLE), a MAKE_RETURN grant (BADGE, once), else 403 MAKE_RETURN")
	void returns() {
		ProcessReturnRequestDTO request = new ProcessReturnRequestDTO();
		request.setTicketNumber("T1");
		assertDoesNotThrow(() -> guard.checkReturn(request, holder, session, "RETURN of T1"));
		assertEquals(GrantedBy.ROLE, rights.log.get(0).getGrantedBy());
		assertEquals(RETURN, rights.log.get(0).getFunctionality());

		TillRightRequiredException none = refused(() -> guard.checkReturn(request, cashier, session, "RETURN of T1"));
		assertEquals(RETURN, none.getAction());
		assertEquals(Reason.NO_GRANT, none.getReason());

		request.setTillGrant(rights.grant(RETURN, 7L, cashier, responsible));
		assertDoesNotThrow(() -> guard.checkReturn(request, cashier, session, "RETURN of T1"));
		assertEquals(GrantedBy.BADGE, rights.log.get(1).getGrantedBy());
		assertEquals("RETURN of T1", rights.last().getConsumedByRef());
		assertEquals(Reason.GRANT_USED, refused(() -> guard.checkReturn(request, cashier, session, "RETURN of T1"))
				.getReason());
	}

	// ─── Grants ────────────────────────────────────────────────────

	@Test
	@DisplayName("The grant of a scan: only for an action checked on its request and with a session; a random token, its SHA-256 kept; TILL_GRANT_MAX_MINUTES (240 when absent or not positive)")
	void issue() {
		assertNull(rights.grant(BadgePermission.CONSULT_CUSTOMER_LIST, 7L, cashier, responsible), "the customer list");
		assertNull(rights.grant(LINE, null, cashier, responsible), "no open session");

		String token = rights.grant(LINE, 7L, cashier, responsible);
		assertNotNull(token);
		assertEquals(43, token.length(), "32 bytes, base64url without padding");
		assertEquals(TillGrantService.hash(token), rights.last().getTokenHash());
		assertEquals(64, rights.last().getTokenHash().length());
		assertTrue(!rights.last().getTokenHash().contains(token));
		assertEquals(240, java.time.Duration.between(rights.last().getIssuedAt(), rights.last().getExpiresAt())
				.toMinutes());
		assertTrue(!token.equals(rights.grant(LINE, 7L, cashier, responsible)), "a new token per scan");

		rights.settings.put(TillGrantService.MAX_MINUTES, "30");
		rights.grant(LINE, 7L, cashier, responsible);
		assertEquals(30, java.time.Duration.between(rights.last().getIssuedAt(), rights.last().getExpiresAt())
				.toMinutes());
		for (String invalid : new String[] { "0", "-5", "abc", "" }) {
			rights.settings.put(TillGrantService.MAX_MINUTES, invalid);
			rights.grant(LINE, 7L, cashier, responsible);
			assertEquals(240, java.time.Duration.between(rights.last().getIssuedAt(), rights.last().getExpiresAt())
					.toMinutes(), invalid);
		}
		List<String> hashes = new ArrayList<>();
		rights.grants.values().forEach(g -> hashes.add(g.getTokenHash()));
		assertEquals(hashes.size(), new HashSet<>(hashes).size());
	}

	@Test
	@DisplayName("The comparison of a discount with the one held: a percentage with a percentage, else an amount with an amount")
	void notAbove() {
		assertTrue(TillRightsGuard.notAbove(10.0, 5.0, 10.0, 2.0));
		assertTrue(TillRightsGuard.notAbove(9.9995, null, 10.0, null));
		assertTrue(TillRightsGuard.notAbove(null, 2.0, null, 2.0));
		assertTrue(TillRightsGuard.notAbove(15.0, 1.0, 10.0, 2.0), "a larger rate on fewer pieces: not more money");
		assertTrue(!TillRightsGuard.notAbove(15.0, 3.0, 10.0, 2.0));
		assertTrue(!TillRightsGuard.notAbove(null, 2.5, null, 2.0));
		assertTrue(!TillRightsGuard.notAbove(10.0, null, null, null));
		assertTrue(TillRightsGuard.discounted(null, 0.01));
		assertTrue(TillRightsGuard.discounted(10.0, null));
		assertTrue(!TillRightsGuard.discounted(0.0005, null));
		assertTrue(!TillRightsGuard.discounted(null, null));
	}
}
