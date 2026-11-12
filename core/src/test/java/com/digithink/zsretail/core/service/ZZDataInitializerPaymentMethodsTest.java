package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.BarcodeLayout;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.repository.AcceptedTicketRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;

/**
 * 3.1.0, payment method "Retenue à la source": on a new database the seed adds it inactive, with the neutral code
 * RETENUE_SOURCE, after Virement Bancaire. 3.2.0: the eight fixed methods, every row behaviour AMOUNT, the N° titre
 * length 7 on Client Chèque and Client Traite (the default of the settings of before); step 3, on a store only: two
 * inactive scanned-tickets methods without type, Ticket Restaurant (the six repas issuers) and Chèque Cadeau (the six
 * cadeau issuers), their lines inactive. Calls the private initPaymentMethods() with in-memory repositories (no Spring
 * context). init() runs it only when the table is empty, so a converted row is never seeded again; that guard is
 * unchanged and not covered here.
 */
class ZZDataInitializerPaymentMethodsTest {

	private final List<PaymentMethod> methods = new ArrayList<>();
	private final List<AcceptedTicket> lines = new ArrayList<>();

	@SuppressWarnings("unchecked")
	private static <T> T recorder(Class<T> type, List<?> saved) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			if (!"save".equals(method.getName())) {
				throw new UnsupportedOperationException("Unexpected call: " + type.getSimpleName() + "." + method.getName());
			}
			((List<Object>) saved).add(args[0]);
			return args[0];
		});
	}

	private void seed(boolean headOffice) throws Exception {
		ZZDataInitializer initializer = new ZZDataInitializer();
		set(initializer, "paymentMethodRepository", recorder(PaymentMethodRepository.class, methods));
		set(initializer, "acceptedTicketRepository", recorder(AcceptedTicketRepository.class, lines));
		ApplicationModeService mode = mock(ApplicationModeService.class);
		when(mode.isHeadOffice()).thenReturn(headOffice);
		set(initializer, "applicationModeService", mode);
		Method initPaymentMethods = ZZDataInitializer.class.getDeclaredMethod("initPaymentMethods");
		initPaymentMethods.setAccessible(true);
		initPaymentMethods.invoke(initializer);
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = ZZDataInitializer.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	/**
	 * code | name | type | active | order | title number, due date, drawer name, issuing bank, confirmation | behaviour
	 * | title number length
	 */
	private static String row(PaymentMethod m) {
		return m.getCode() + " | " + m.getName() + " | " + m.getType() + " | " + m.getActive() + " | " + m.getDisplayOrder()
				+ " | " + m.getRequireTitleNumber() + "," + m.getRequireDueDate() + "," + m.getRequireDrawerName() + ","
				+ m.getRequireIssuingBank() + "," + m.getRequiresConfirmation() + " | " + m.getBehaviour() + " | "
				+ m.getTitleNumberLength();
	}

	private static final List<String> FIXED = Arrays.asList(
			"CLIENT_ESPECES | Client Espèce | CLIENT_ESPECES | true | 1 | false,false,false,false,false | AMOUNT | null",
			"CLIENT_TPE | Client TPE | CLIENT_TPE | true | 2 | false,false,false,false,false | AMOUNT | null",
			"CLIENT_CHEQUE | Client Chèque | CLIENT_CHEQUE | true | 5 | true,false,true,true,false | AMOUNT | 7",
			"CLIENT_TRAITE | Client Traite | CLIENT_TRAITE | true | 6 | true,true,true,true,false | AMOUNT | 7",
			"DEPOT_BANQUE | Dépôt en banque | DEPOT_BANQUE | true | 7 | false,false,false,false,false | AMOUNT | null",
			"RETURN_VOUCHER | Bon de Retour | RETURN_VOUCHER | true | 8 | false,false,false,false,false | AMOUNT | null",
			"VIREMENT_BANCAIRE | Virement Bancaire | VIREMENT_BANCAIRE | true | 9 | false,false,false,false,false | AMOUNT"
					+ " | null",
			"RETENUE_SOURCE | Retenue à la source | RETENUE_SOURCE | false | 10 | false,false,false,false,false | AMOUNT"
					+ " | null");

	@Test
	@DisplayName("A store: the eight fixed methods, then Ticket Restaurant and Chèque Cadeau, scanned tickets, inactive")
	void store() throws Exception {
		seed(false);
		List<String> expected = new ArrayList<>(FIXED);
		expected.add("TICKET_RESTAURANT | Ticket Restaurant | null | false | 3 | false,false,false,false,false"
				+ " | SCANNED_TICKETS | null");
		expected.add("CHEQUE_CADEAU | Chèque Cadeau | null | false | 4 | false,false,false,false,false"
				+ " | SCANNED_TICKETS | null");
		assertEquals(expected, methods.stream().map(ZZDataInitializerPaymentMethodsTest::row).collect(Collectors.toList()));
	}

	@Test
	@DisplayName("A store: the six repas lines under Ticket Restaurant, the six cadeau under Chèque Cadeau, all inactive")
	void storeLines() throws Exception {
		seed(false);
		PaymentMethod meal = methods.get(8);
		PaymentMethod gift = methods.get(9);
		assertEquals(12, lines.size());
		assertEquals(Arrays.asList("011", "021", "031", "041", "051", "081"), lines.stream()
				.filter(l -> l.getPaymentMethod() == meal).map(AcceptedTicket::getTicketCode).collect(Collectors.toList()));
		assertEquals(Arrays.asList("012", "022", "032", "042", "052", "082"), lines.stream()
				.filter(l -> l.getPaymentMethod() == gift).map(AcceptedTicket::getTicketCode).collect(Collectors.toList()));
		for (AcceptedTicket line : lines) {
			assertFalse(line.getActive(), line.getTicketCode());
			assertEquals(BarcodeLayout.standard(), line.getLayout());
			assertEquals(1, line.getValidityMonths());
		}
	}

	@Test
	@DisplayName("Step 5: the card on a terminal asks its slip number, optional; no other seeded method asks an optional one")
	void terminalSlipNumber() throws Exception {
		seed(false);
		for (PaymentMethod method : methods) {
			assertEquals("CLIENT_TPE".equals(method.getCode()), Boolean.TRUE.equals(method.getAskTitleNumber()),
					method.getCode());
		}
	}

	@Test
	@DisplayName("Step 3b: every seeded method goes to the ERP but the return voucher")
	void sendToErp() throws Exception {
		seed(false);
		for (PaymentMethod method : methods) {
			assertEquals(!"RETURN_VOUCHER".equals(method.getCode()), method.getSendToErp(), method.getCode());
		}
	}

	@Test
	@DisplayName("A head office: the eight fixed methods only, no scanned tickets (it never sells)")
	void headOffice() throws Exception {
		seed(true);
		assertEquals(FIXED, methods.stream().map(ZZDataInitializerPaymentMethodsTest::row).collect(Collectors.toList()));
		assertTrue(lines.isEmpty());
	}

	@Test
	@DisplayName("No seeded code is a customer's ERP code: each fixed method's code is its type's name")
	void seededCodesAreTheTypeNames() throws Exception {
		seed(false);
		for (PaymentMethod method : methods) {
			if (method.getType() != null) {
				assertEquals(method.getType().name(), method.getCode());
			}
			assertFalse(method.getCode().startsWith("ENCAISS"), method.getCode());
		}
	}
}
