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
 * RETENUE_SOURCE, after Virement Bancaire. 3.2.0: no Ticket Restaurant or Chèque Cadeau (no longer fixed types), every
 * row behaviour AMOUNT, the N° titre length 7 on Client Chèque and Client Traite (the default of the settings of
 * before); the others as before. Calls the private initPaymentMethods() with an in-memory repository (no Spring
 * context). init() runs it only when the table is empty, so a converted row is never seeded again; that guard is
 * unchanged and not covered here.
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

	@Test
	@DisplayName("Eight methods: no Ticket Restaurant or Chèque Cadeau, length 7 on cheque and traite, Retenue inactive")
	void seedsTheEightFixedMethods() throws Exception {
		List<String> rows = new ArrayList<>();
		for (PaymentMethod method : seedPaymentMethods()) {
			rows.add(row(method));
		}
		assertEquals(Arrays.asList(
				"CLIENT_ESPECES | Client Espèce | CLIENT_ESPECES | true | 1 | false,false,false,false,false | AMOUNT | null",
				"CLIENT_TPE | Client TPE | CLIENT_TPE | true | 2 | false,false,false,false,false | AMOUNT | null",
				"CLIENT_CHEQUE | Client Chèque | CLIENT_CHEQUE | true | 5 | true,false,true,true,false | AMOUNT | 7",
				"CLIENT_TRAITE | Client Traite | CLIENT_TRAITE | true | 6 | true,true,true,true,false | AMOUNT | 7",
				"DEPOT_BANQUE | Dépôt en banque | DEPOT_BANQUE | true | 7 | false,false,false,false,false | AMOUNT | null",
				"RETURN_VOUCHER | Bon de Retour | RETURN_VOUCHER | true | 8 | false,false,false,false,false | AMOUNT | null",
				"VIREMENT_BANCAIRE | Virement Bancaire | VIREMENT_BANCAIRE | true | 9 | false,false,false,false,false | AMOUNT"
						+ " | null",
				"RETENUE_SOURCE | Retenue à la source | RETENUE_SOURCE | false | 10 | false,false,false,false,false | AMOUNT"
						+ " | null"),
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
