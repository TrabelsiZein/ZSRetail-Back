package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.dto.LoyaltyProgramDTO;
import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.model.LoyaltyEarningTier;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.LoyaltyProgram;
import com.digithink.zsretail.model.LoyaltyTransaction;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.enumeration.LoyaltyTransactionType;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.repository.LoyaltyProgramRepository;
import com.digithink.zsretail.repository.LoyaltyTransactionRepository;

/**
 * Loyalty earning tiers: rate resolution, point calculation and program
 * create/update rules. Plain JUnit with in-memory repository stubs (no Spring,
 * no database, no mocking library), so it runs on any JDK.
 */
class LoyaltyEarningTiersTest {

	private static final double STAMP = 0.1; // fiscal stamp of 100 millimes

	private LoyaltyService service;
	private final List<LoyaltyProgram> programs = new ArrayList<>();
	private final Map<Long, LoyaltyMember> members = new HashMap<>();
	private final List<LoyaltyTransaction> transactions = new ArrayList<>();
	private final Map<Long, Long> txCountByProgramId = new HashMap<>();
	private String loyaltyEnabled;
	private LoyaltyProgram currentProgram;
	private long nextId;

	@BeforeEach
	void setUp() throws Exception {
		programs.clear();
		members.clear();
		transactions.clear();
		txCountByProgramId.clear();
		loyaltyEnabled = "true";
		currentProgram = null;
		nextId = 100;

		service = new LoyaltyService();
		inject("loyaltyMemberRepository", stub(LoyaltyMemberRepository.class, (method, a) -> {
			switch (method) {
				case "findById": return Optional.ofNullable(members.get(a[0]));
				case "save": return a[0];
				default: return UNHANDLED;
			}
		}));
		inject("loyaltyProgramRepository", stub(LoyaltyProgramRepository.class, (method, a) -> {
			switch (method) {
				case "findCurrentActivePrograms":
					return currentProgram == null ? new ArrayList<>() : new ArrayList<>(Collections.singletonList(currentProgram));
				case "findByActiveTrue":
					return programs.stream().filter(p -> Boolean.TRUE.equals(p.getActive())).collect(Collectors.toList());
				case "findAllByOrderByStartDateDesc":
					return new ArrayList<>(programs);
				case "findById":
					return programs.stream().filter(p -> a[0].equals(p.getId())).findFirst();
				case "save": {
					LoyaltyProgram p = (LoyaltyProgram) a[0];
					if (p.getId() == null) p.setId(nextId++);
					if (programs.stream().noneMatch(existing -> existing == p)) programs.add(p);
					return p;
				}
				default: return UNHANDLED;
			}
		}));
		inject("loyaltyTransactionRepository", stub(LoyaltyTransactionRepository.class, (method, a) -> {
			switch (method) {
				case "save":
					transactions.add((LoyaltyTransaction) a[0]);
					return a[0];
				case "countByLoyaltyProgram":
					return txCountByProgramId.getOrDefault(((LoyaltyProgram) a[0]).getId(), 0L);
				default: return UNHANDLED;
			}
		}));
		inject("generalSetupRepository", stub(GeneralSetupRepository.class, (method, a) -> {
			if (!"findByCode".equals(method)) return UNHANDLED;
			if (!"LOYALTY_ENABLED".equals(a[0]) || loyaltyEnabled == null) return Optional.empty();
			GeneralSetup setup = new GeneralSetup();
			setup.setCode("LOYALTY_ENABLED");
			setup.setValeur(loyaltyEnabled);
			return Optional.of(setup);
		}));
	}

	// ─── Rate resolution ─────────────────────────────────────────────

	@Test
	@DisplayName("Rate: without tiers the base rate applies to every amount")
	void noTiersUsesBaseRate() {
		LoyaltyProgram p = program(10.0);
		assertEquals(10.0, LoyaltyService.resolvePointsPerDinar(p, 0.0));
		assertEquals(10.0, LoyaltyService.resolvePointsPerDinar(p, 650.0));
		p.setEarningTiers(null);
		assertEquals(10.0, LoyaltyService.resolvePointsPerDinar(p, 650.0));
	}

	@Test
	@DisplayName("Rate: client grid, a ticket exactly on a limit stays in the lower tier")
	void clientGridBoundaries() {
		LoyaltyProgram p = clientProgram();
		double[][] cases = { { 0, 1 }, { 1, 1 }, { 150, 1 }, { 200, 1 }, { 200.001, 1.5 }, { 350, 1.5 },
				{ 500, 1.5 }, { 500.001, 2 }, { 650, 2 }, { 10000, 2 } };
		for (double[] c : cases) {
			assertEquals(c[1], LoyaltyService.resolvePointsPerDinar(p, c[0]), "amount " + c[0]);
		}
	}

	@Test
	@DisplayName("Rate: the order of the tiers in the program does not matter")
	void unsortedTiers() {
		LoyaltyProgram p = program(1.0, tier(500, 2), tier(200, 1.5));
		assertEquals(1.0, LoyaltyService.resolvePointsPerDinar(p, 200));
		assertEquals(1.5, LoyaltyService.resolvePointsPerDinar(p, 350));
		assertEquals(2.0, LoyaltyService.resolvePointsPerDinar(p, 650));
	}

	// ─── Earning: flat programs must not change ──────────────────────

	@Test
	@DisplayName("Flat program: points identical to the previous formula, fiscal stamp still included")
	void flatProgramUnchanged() {
		double[] totals = { 0.5, 1, 99.99, 150.1, 200.1, 350.75, 999.999, 1000.1 };
		double[] rates = { 1.0, 10.0, 0.5, 1.5 };
		for (double rate : rates) {
			for (double total : totals) {
				int legacy = (int) Math.floor(total * rate); // formula before tiers existed
				int expected = legacy > 0 ? legacy : 0;
				assertEquals(expected, earn(program(rate), total, STAMP), "rate " + rate + ", total " + total);
				LoyaltyMember m = member(0);
				assertEquals(expected, service.earnPoints(m.getId(), sale(total), null), "3-arg, rate " + rate + ", total " + total);
			}
		}
	}

	@Test
	@DisplayName("Flat program: the history text is unchanged")
	void flatDescriptionUnchanged() {
		earn(program(1.0), 150, STAMP);
		assertEquals("Points earned from sale #T-1", lastTx().getDescription());
	}

	// ─── Earning: tiered programs ────────────────────────────────────

	@Test
	@DisplayName("Tiered: the client's examples (150, 350, 650 DT), with and without a fiscal stamp")
	void clientExamples() {
		LoyaltyProgram p = clientProgram();
		assertEquals(150, earn(p, 150 + STAMP, STAMP));
		assertEquals(525, earn(p, 350 + STAMP, STAMP));
		assertEquals(1300, earn(p, 650 + STAMP, STAMP));
		assertEquals(150, earn(p, 150, 0));
		assertEquals(525, earn(p, 350, 0));
		assertEquals(1300, earn(p, 650, 0));
	}

	@Test
	@DisplayName("Tiered: tickets exactly on 200 or 500 DT stay in the lower tier")
	void limitsStayInLowerTier() {
		LoyaltyProgram p = clientProgram();
		assertEquals(200, earn(p, 200 + STAMP, STAMP));
		assertEquals(750, earn(p, 500 + STAMP, STAMP));
		assertEquals(300, earn(p, 200.001 + STAMP, STAMP)); // 300.0015
		assertEquals(1000, earn(p, 500.001 + STAMP, STAMP)); // 1000.002
	}

	@Test
	@DisplayName("Tiered: the fiscal stamp never pushes a ticket into the next tier")
	void stampExcludedFromTierChoice() {
		// 199.950 DT of goods + 0.100 DT stamp = 200.050 DT paid, still the 1 pt/DT tier
		assertEquals(199, earn(clientProgram(), 199.95 + STAMP, STAMP));
	}

	@Test
	@DisplayName("Tiered: points are rounded down")
	void roundsDown() {
		assertEquals(526, earn(clientProgram(), 350.75, 0)); // 526.125
		assertEquals(0, earn(clientProgram(), 0.5, 0));
		assertEquals(0, earn(clientProgram(), 0, 0));
	}

	@Test
	@DisplayName("Tiered: the history line shows the rate that was applied")
	void descriptionShowsRate() {
		LoyaltyProgram p = clientProgram();
		earn(p, 150, 0);
		assertEquals("Points earned from sale #T-1 (1 pts/TND)", lastTx().getDescription());
		earn(p, 350, 0);
		assertEquals("Points earned from sale #T-1 (1.5 pts/TND)", lastTx().getDescription());
		earn(p, 650, 0);
		assertEquals("Points earned from sale #T-1 (2 pts/TND)", lastTx().getDescription());
	}

	@Test
	@DisplayName("Tiered: balance, totals and the EARNED transaction are recorded")
	void balanceAndTransactionRecorded() {
		LoyaltyProgram p = clientProgram();
		currentProgram = p;
		LoyaltyMember m = member(100);
		SalesHeader s = sale(350 + STAMP);
		assertEquals(525, service.earnPoints(m.getId(), s, null, STAMP));
		assertEquals(625, m.getLoyaltyPoints().intValue());
		assertEquals(525, m.getTotalPointsEarned().intValue());
		LoyaltyTransaction tx = lastTx();
		assertEquals(LoyaltyTransactionType.EARNED, tx.getType());
		assertEquals(525, tx.getPoints().intValue());
		assertEquals(100, tx.getBalanceBefore().intValue());
		assertEquals(625, tx.getBalanceAfter().intValue());
		assertSame(p, tx.getLoyaltyProgram());
		assertSame(s, tx.getSalesHeader());
	}

	@Test
	@DisplayName("No points when loyalty is disabled, no program is active, or the member is inactive")
	void noPointsGuards() {
		LoyaltyProgram p = clientProgram();
		loyaltyEnabled = "false";
		assertEquals(0, earn(p, 650, 0));
		loyaltyEnabled = "true";
		currentProgram = null;
		LoyaltyMember m = member(0);
		assertEquals(0, service.earnPoints(m.getId(), sale(650), null, 0));
		currentProgram = p;
		m.setActive(false);
		assertEquals(0, service.earnPoints(m.getId(), sale(650), null, 0));
		assertEquals(0, m.getLoyaltyPoints().intValue());
		assertTrue(transactions.isEmpty());
	}

	// ─── Program create / update ─────────────────────────────────────

	@Test
	@DisplayName("Create: tiers are sorted and their amounts rounded to the millime")
	void createNormalizesTiers() {
		LoyaltyProgram request = newProgramRequest();
		request.setEarningTiers(new ArrayList<>(Arrays.asList(tier(500.0004, 2), tier(200, 1.5))));
		LoyaltyProgramDTO dto = service.activateNewProgram(request);
		List<LoyaltyEarningTier> expected = Arrays.asList(tier(200, 1.5), tier(500, 2));
		assertEquals(expected, request.getEarningTiers());
		assertEquals(expected, dto.getEarningTiers());
	}

	@Test
	@DisplayName("Create: no tiers gives an empty list, a flat program")
	void createWithoutTiers() {
		LoyaltyProgram request = newProgramRequest();
		request.setEarningTiers(null);
		LoyaltyProgramDTO dto = service.activateNewProgram(request);
		assertNotNull(request.getEarningTiers());
		assertTrue(request.getEarningTiers().isEmpty());
		assertTrue(dto.getEarningTiers().isEmpty());
	}

	@Test
	@DisplayName("Create: invalid tiers are rejected before the current program is closed")
	void createRejectsInvalidTiers() {
		LoyaltyProgram current = clientProgram();
		List<List<LoyaltyEarningTier>> invalid = Arrays.asList(
				Arrays.asList(tier(0, 1.5)),
				Arrays.asList(tier(-10, 1.5)),
				Arrays.asList(tier(0.0004, 1.5)), // rounds to 0
				Arrays.asList(tier(200, 0)),
				Arrays.asList(tier(200, -1)),
				Arrays.asList(new LoyaltyEarningTier(null, 1.5)),
				Arrays.asList(new LoyaltyEarningTier(200.0, null)),
				Arrays.asList(tier(200, 1.5), tier(200, 2)),
				Collections.singletonList((LoyaltyEarningTier) null));
		for (List<LoyaltyEarningTier> tiers : invalid) {
			LoyaltyProgram request = newProgramRequest();
			request.setEarningTiers(new ArrayList<>(tiers));
			assertThrows(IllegalArgumentException.class, () -> service.activateNewProgram(request), String.valueOf(tiers));
			assertTrue(Boolean.TRUE.equals(current.getActive()), "current program must stay active");
			assertNull(current.getEndDate(), "current program must not be closed");
		}
	}

	@Test
	@DisplayName("Update of an unused program: tiers can be replaced, kept when not sent, or cleared")
	void updateUnusedProgram() {
		LoyaltyProgram existing = clientProgram();

		LoyaltyProgram replaced = patchOf(existing);
		replaced.setEarningTiers(new ArrayList<>(Arrays.asList(tier(300, 3))));
		service.updateProgram(existing.getId(), replaced);
		assertEquals(Arrays.asList(tier(300, 3)), existing.getEarningTiers());

		LoyaltyProgram notSent = patchOf(existing);
		notSent.setEarningTiers(null);
		service.updateProgram(existing.getId(), notSent);
		assertEquals(Arrays.asList(tier(300, 3)), existing.getEarningTiers());

		LoyaltyProgram cleared = patchOf(existing);
		cleared.setEarningTiers(new ArrayList<>());
		service.updateProgram(existing.getId(), cleared);
		assertTrue(existing.getEarningTiers().isEmpty());
	}

	@Test
	@DisplayName("Update of a used program: tiers are locked, resending the same tiers is fine")
	void updateUsedProgramLocksTiers() {
		LoyaltyProgram existing = clientProgram();
		txCountByProgramId.put(existing.getId(), 3L);

		LoyaltyProgram sameReordered = patchOf(existing);
		sameReordered.setName("Renamed");
		sameReordered.setEarningTiers(new ArrayList<>(Arrays.asList(tier(500, 2), tier(200, 1.5))));
		service.updateProgram(existing.getId(), sameReordered);
		assertEquals("Renamed", existing.getName());

		LoyaltyProgram changed = patchOf(existing);
		changed.setEarningTiers(new ArrayList<>(Arrays.asList(tier(200, 1.5), tier(500, 2.5))));
		assertThrows(IllegalStateException.class, () -> service.updateProgram(existing.getId(), changed));
		assertEquals(Arrays.asList(tier(200, 1.5), tier(500, 2)), LoyaltyService.sortedTierCopies(existing));

		LoyaltyProgram notSent = patchOf(existing);
		notSent.setEarningTiers(null);
		service.updateProgram(existing.getId(), notSent);
		assertEquals(Arrays.asList(tier(200, 1.5), tier(500, 2)), LoyaltyService.sortedTierCopies(existing));
	}

	@Test
	@DisplayName("Program list exposes the tiers sorted")
	void programListShowsSortedTiers() {
		program(1.0, tier(500, 2), tier(200, 1.5));
		List<LoyaltyProgramDTO> dtos = service.getAllPrograms();
		assertEquals(Arrays.asList(tier(200, 1.5), tier(500, 2)), dtos.get(0).getEarningTiers());
	}

	// ─── Redemption ──────────────────────────────────────────────────

	@Test
	@DisplayName("Redeem without fiscal stamp: the limit applies to the total plus the deduction")
	void redeemWithoutStampOverload() {
		LoyaltyProgram p = program(1.0);
		p.setPointValueMillimes(50);
		p.setMinimumRedemptionPoints(600);
		p.setMaximumRedemptionPercentage(100.0);
		currentProgram = p;
		LoyaltyMember m = member(600);
		SalesHeader s = sale(20); // 50 DT of goods, 30 DT paid with points
		assertEquals(30.0, service.redeemPoints(m.getId(), 600, s, null));
		assertEquals(0, m.getLoyaltyPoints().intValue());
		assertEquals(LoyaltyTransactionType.REDEEMED, lastTx().getType());
		assertEquals(600, lastTx().getPoints().intValue());
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private static LoyaltyEarningTier tier(double above, double rate) {
		return new LoyaltyEarningTier(above, rate);
	}

	/** An active, already-saved program; becomes the current program when passed to earn(). */
	private LoyaltyProgram program(double baseRate, LoyaltyEarningTier... tiers) {
		LoyaltyProgram p = newProgramRequest();
		p.setId(nextId++);
		p.setPointsPerDinar(baseRate);
		p.setEarningTiers(new ArrayList<>(Arrays.asList(tiers)));
		p.setActive(true);
		programs.add(p);
		return p;
	}

	/** The client's grid: 1 pt/DT, above 200 DT 1.5, above 500 DT 2. */
	private LoyaltyProgram clientProgram() {
		return program(1.0, tier(200, 1.5), tier(500, 2));
	}

	private LoyaltyProgram newProgramRequest() {
		LoyaltyProgram p = new LoyaltyProgram();
		p.setProgramCode("P-" + nextId);
		p.setName("Program " + nextId);
		p.setStartDate(LocalDate.now().minusDays(1));
		p.setPointsPerDinar(1.0);
		return p;
	}

	/** What the admin form sends back when editing: every field as loaded. */
	private static LoyaltyProgram patchOf(LoyaltyProgram existing) {
		LoyaltyProgram patch = new LoyaltyProgram();
		patch.setProgramCode(existing.getProgramCode());
		patch.setName(existing.getName());
		patch.setDescription(existing.getDescription());
		patch.setStartDate(existing.getStartDate());
		patch.setEndDate(existing.getEndDate());
		patch.setPointsPerDinar(existing.getPointsPerDinar());
		patch.setPointValueMillimes(existing.getPointValueMillimes());
		patch.setMinimumRedemptionPoints(existing.getMinimumRedemptionPoints());
		patch.setMaximumRedemptionPercentage(existing.getMaximumRedemptionPercentage());
		patch.setPointsExpiryDays(existing.getPointsExpiryDays());
		patch.setEarningTiers(LoyaltyService.sortedTierCopies(existing));
		return patch;
	}

	private LoyaltyMember member(int points) {
		LoyaltyMember m = new LoyaltyMember();
		m.setId(nextId++);
		m.setCardNumber("LYL-" + m.getId());
		m.setLoyaltyPoints(points);
		m.setTotalPointsEarned(0);
		m.setActive(true);
		members.put(m.getId(), m);
		return m;
	}

	private static SalesHeader sale(double total) {
		SalesHeader s = new SalesHeader();
		s.setSalesNumber("T-1");
		s.setTotalAmount(total);
		return s;
	}

	/** Earn on a fresh member with {@code active} as the current program. */
	private int earn(LoyaltyProgram active, double total, double stamp) {
		currentProgram = active;
		LoyaltyMember m = member(0);
		return service.earnPoints(m.getId(), sale(total), null, stamp);
	}

	private LoyaltyTransaction lastTx() {
		return transactions.get(transactions.size() - 1);
	}

	// ─── Stub plumbing ───────────────────────────────────────────────

	private interface Handler {
		Object handle(String method, Object[] args);
	}

	private static final Object UNHANDLED = new Object();

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				case "toString": return type.getSimpleName() + "Stub";
				default: break;
			}
			Object result = handler.handle(method.getName(), args == null ? new Object[0] : args);
			if (result == UNHANDLED) {
				throw new UnsupportedOperationException("Unexpected call: " + type.getSimpleName() + "." + method.getName());
			}
			return result;
		});
	}

	private void inject(String fieldName, Object value) throws Exception {
		Field field = LoyaltyService.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(service, value);
	}
}
