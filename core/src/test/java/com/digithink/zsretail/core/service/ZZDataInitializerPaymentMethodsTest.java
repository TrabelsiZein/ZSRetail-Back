package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;

/**
 * 3.1.0, payment method "Retenue à la source": on a new database the seed adds it inactive, with the neutral code
 * RETENUE_SOURCE, after Virement Bancaire; the nine methods of before are seeded exactly as before. Calls the private
 * initPaymentMethods() with an in-memory repository (no Spring context). init() runs it only when the table is empty;
 * that guard is unchanged and not covered here.
 */
class ZZDataInitializerPaymentMethodsTest {

	private static List<PaymentMethod> seedPaymentMethods() throws Exception {
		List<PaymentMethod> saved = new ArrayList<>();
		PaymentMethodRepository repository = (PaymentMethodRepository) Proxy.newProxyInstance(
				PaymentMethodRepository.class.getClassLoader(), new Class<?>[] { PaymentMethodRepository.class },
				(proxy, method, args) -> {
					if (!"save".equals(method.getName())) {
						throw new UnsupportedOperationException("Unexpected call: PaymentMethodRepository." + method.getName());
					}
					saved.add((PaymentMethod) args[0]);
					return args[0];
				});
		ZZDataInitializer initializer = new ZZDataInitializer();
		Field field = ZZDataInitializer.class.getDeclaredField("paymentMethodRepository");
		field.setAccessible(true);
		field.set(initializer, repository);
		Method initPaymentMethods = ZZDataInitializer.class.getDeclaredMethod("initPaymentMethods");
		initPaymentMethods.setAccessible(true);
		initPaymentMethods.invoke(initializer);
		return saved;
	}

	/** code | name | type | active | order | title number, due date, drawer name, issuing bank, confirmation */
	private static String row(PaymentMethod m) {
		return m.getCode() + " | " + m.getName() + " | " + m.getType() + " | " + m.getActive() + " | " + m.getDisplayOrder()
				+ " | " + m.getRequireTitleNumber() + "," + m.getRequireDueDate() + "," + m.getRequireDrawerName() + ","
				+ m.getRequireIssuingBank() + "," + m.getRequiresConfirmation();
	}

	@Test
	@DisplayName("The nine methods of before are seeded unchanged, then Retenue à la source, inactive, order 10")
	void seedsRetenueSourceInactiveAfterTheOthers() throws Exception {
		List<String> rows = new ArrayList<>();
		for (PaymentMethod method : seedPaymentMethods()) {
			rows.add(row(method));
		}
		assertEquals(Arrays.asList(
				"CLIENT_ESPECES | Client Espèce | CLIENT_ESPECES | true | 1 | false,false,false,false,false",
				"CLIENT_TPE | Client TPE | CLIENT_TPE | true | 2 | false,false,false,false,false",
				"TICKET_RESTAURANT | Ticket Restaurant | TICKET_RESTAURANT | true | 3 | true,false,false,false,false",
				"CHEQUE_CADEAU | Chèque Cadeau | CHEQUE_CADEAU | true | 4 | true,false,false,false,false",
				"CLIENT_CHEQUE | Client Chèque | CLIENT_CHEQUE | true | 5 | true,false,true,true,false",
				"CLIENT_TRAITE | Client Traite | CLIENT_TRAITE | true | 6 | true,true,true,true,false",
				"DEPOT_BANQUE | Dépôt en banque | DEPOT_BANQUE | true | 7 | false,false,false,false,false",
				"RETURN_VOUCHER | Bon de Retour | RETURN_VOUCHER | true | 8 | false,false,false,false,false",
				"VIREMENT_BANCAIRE | Virement Bancaire | VIREMENT_BANCAIRE | true | 9 | false,false,false,false,false",
				"RETENUE_SOURCE | Retenue à la source | RETENUE_SOURCE | false | 10 | false,false,false,false,false"),
				rows);
	}

	@Test
	@DisplayName("No seeded code is a customer's ERP code: each code is its type's name")
	void seededCodesAreTheTypeNames() throws Exception {
		for (PaymentMethod method : seedPaymentMethods()) {
			assertEquals(method.getType().name(), method.getCode());
			assertFalse(method.getCode().startsWith("ENCAISS"), method.getCode());
		}
	}
}
