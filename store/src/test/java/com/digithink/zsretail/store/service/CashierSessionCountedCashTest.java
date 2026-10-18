package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.store.dto.CloseSessionRequestDTO;
import com.digithink.zsretail.store.exception.CashDiscrepancyException;
import com.digithink.zsretail.store.support.InMemoryTillRights;

/**
 * 3.1.0: the cash check at closing compares cash with cash. The expected amount (calculateRealCash) is the cash only:
 * opening + cash sales - change - simple returns. The count compared with it is the cash rows only (the cash method's
 * and the float's, no method); the card slips, cheques and vouchers counted beside made a false difference before.
 */
class CashierSessionCountedCashTest {

	private static final long CASH = 1L;
	private static final long CARD = 2L;
	private static final long VOUCHER = 8L;

	private CashierSessionService service;
	private final CashierSession session = new CashierSession();

	@BeforeEach
	void setUp() throws Exception {
		session.setSessionNumber("SESSION261013001");
		PaymentMethod cash = new PaymentMethod();
		cash.setId(CASH);
		cash.setType(PaymentMethodType.CLIENT_ESPECES);
		service = new CashierSessionService();
		set(service, "tillRightsGuard", InMemoryTillRights.allowAll()); // step D: the rights are not what this tests
		set(service, "paymentMethodRepository", proxy(PaymentMethodRepository.class, (m, a) ->
				"findByType".equals(m) && a[0] == PaymentMethodType.CLIENT_ESPECES ? Optional.of(cash) : UNHANDLED));
		set(service, "generalSetupRepository", proxy(GeneralSetupRepository.class, (m, a) -> {
			if (!"findByCode".equals(m)) return UNHANDLED;
			GeneralSetup on = new GeneralSetup();
			on.setCode((String) a[0]);
			on.setValeur("true");
			return "ENABLE_CASH_DISCREPANCY_CHECK".equals(a[0]) ? Optional.of(on) : Optional.empty();
		}));
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = CashierSessionService.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static CloseSessionRequestDTO.CashCountLineDTO line(Long methodId, double amount) {
		CloseSessionRequestDTO.CashCountLineDTO line = new CloseSessionRequestDTO.CashCountLineDTO();
		line.setPaymentMethodId(methodId);
		line.setDenominationValue(amount);
		line.setQuantity(1);
		return line;
	}

	@Test
	@DisplayName("The cash counted: the cash method's rows and the float's (no method); card, voucher rows left out")
	void cashRowsOnly() {
		List<CloseSessionRequestDTO.CashCountLineDTO> lines = Arrays.asList(line(CASH, 20), line(CARD, 20),
				line(VOUCHER, 30), line(null, 10));
		assertEquals(30.0, service.countedCash(lines), 1e-9);
		assertEquals(0.0, service.countedCash(Collections.singletonList(line(CARD, 72.9))), 1e-9, "no cash counted: 0");
		assertNull(service.countedCash(Collections.emptyList()));
		assertNull(service.countedCash(null));
	}

	@Test
	@DisplayName("30 TND of cash expected, 30 counted in cash beside 50 in card and voucher: no difference")
	void noFalseDifference() {
		List<CloseSessionRequestDTO.CashCountLineDTO> lines = Arrays.asList(line(CASH, 20), line(CARD, 20),
				line(VOUCHER, 30), line(null, 10));
		CloseSessionRequestDTO request = new CloseSessionRequestDTO();
		// the total of every row (80) against the cash expected (30): the false difference before 3.1.0
		assertThrows(CashDiscrepancyException.class, () -> service.checkCashDiscrepancy(session, 30.0, 80.0, request));
		// the cash rows only (30): no difference
		assertDoesNotThrow(() -> service.checkCashDiscrepancy(session, 30.0, service.countedCash(lines), request));
		// a real difference in the cash still stops the closing
		assertThrows(CashDiscrepancyException.class,
				() -> service.checkCashDiscrepancy(session, 35.0, service.countedCash(lines), request));
	}
}
