package com.digithink.zsretail.store.erp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.erp.service.ErpSynchronizationManager;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.PaymentRepository;
import com.digithink.zsretail.core.repository.ReturnHeaderRepository;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.store.erp.model.PaymentHeader;
import com.digithink.zsretail.store.erp.model.PaymentLine;
import com.digithink.zsretail.store.erp.repository.PaymentHeaderRepository;
import com.digithink.zsretail.store.erp.repository.PaymentLineRepository;

/**
 * 3.2.0, step 2: a method the admin created (no type) goes to the ERP as any non-cash method: a payment header whose
 * class is the method's code, one payment line per payment with its N° titre and amount (the cash of the same ticket
 * stays one line per customer). The repositories in memory.
 */
class SessionExportMethodWithoutTypeTest {

	private final List<PaymentHeader> headers = new ArrayList<>();
	private final List<PaymentLine> lines = new ArrayList<>();

	private static PaymentMethod method(String code, PaymentMethodType type) {
		PaymentMethod method = new PaymentMethod();
		method.setCode(code);
		method.setName(code);
		method.setType(type);
		return method;
	}

	private static Payment payment(long id, SalesHeader sale, PaymentMethod method, double amount, String title) {
		Payment payment = new Payment();
		payment.setId(id);
		payment.setSalesHeader(sale);
		payment.setPaymentMethod(method);
		payment.setTotalAmount(amount);
		payment.setTitleNumber(title);
		return payment;
	}

	@Test
	@DisplayName("Its code is the payment class, one line per payment with the N° titre; cash apart")
	void exportedAsANonCashMethod() {
		CashierSession session = new CashierSession();
		session.setId(7L);
		session.setSessionNumber("SESSION261112001");
		Customer passenger = new Customer();
		passenger.setCustomerCode("PASSAGER");
		SalesHeader sale = new SalesHeader();
		sale.setSalesNumber("T-1");
		sale.setCashierSession(session);
		sale.setCustomer(passenger);
		sale.setChangeAmount(0.0);

		CashierSessionRepository sessions = mock(CashierSessionRepository.class);
		when(sessions.findByIdForUpdate(7L)).thenReturn(Optional.of(session));
		GeneralSetupService setup = mock(GeneralSetupService.class);
		PaymentHeaderRepository headerRepository = mock(PaymentHeaderRepository.class);
		when(headerRepository.findByCashierSession(session)).thenAnswer(i -> new ArrayList<>(headers));
		when(headerRepository.save(any(PaymentHeader.class))).thenAnswer(i -> {
			PaymentHeader header = i.getArgument(0);
			if (!headers.contains(header)) {
				headers.add(header);
			}
			return header;
		});
		PaymentLineRepository lineRepository = mock(PaymentLineRepository.class);
		when(lineRepository.findByPaymentHeader(any(PaymentHeader.class))).thenAnswer(i -> lines.stream()
				.filter(l -> l.getPaymentHeader() == i.getArgument(0)).collect(Collectors.toList()));
		when(lineRepository.save(any(PaymentLine.class))).thenAnswer(i -> {
			lines.add(i.getArgument(0));
			return i.getArgument(0);
		});
		SessionExportService service = new SessionExportService(mock(ErpSynchronizationManager.class), sessions, setup,
				mock(SalesHeaderRepository.class), mock(ReturnHeaderRepository.class), mock(PaymentRepository.class),
				headerRepository, lineRepository);

		PaymentMethod admins = method("TR-SERVIMAX", null);
		PaymentMethod cash = method("CLIENT_ESPECES", PaymentMethodType.CLIENT_ESPECES);
		service.createPaymentHeadersAndLinesAsync(Arrays.asList(payment(1L, sale, admins, 6.3, "0310070002500002897009796069"),
				payment(2L, sale, admins, 7.0, "0310070002500002897009796070"), payment(3L, sale, cash, 10.0, null)), sale);

		assertEquals(Arrays.asList("TR-SERVIMAX", "CLIENT_ESPECES"),
				headers.stream().map(PaymentHeader::getPaymentClass).sorted((a, b) -> b.compareTo(a))
						.collect(Collectors.toList()));
		List<PaymentLine> mealLines = lines.stream().filter(l -> "TR-SERVIMAX".equals(l.getPaymentHeader().getPaymentClass()))
				.collect(Collectors.toList());
		assertEquals(2, mealLines.size(), "one line per payment");
		assertEquals(Arrays.asList("0310070002500002897009796069", "0310070002500002897009796070"),
				mealLines.stream().map(PaymentLine::getTitleNumber).collect(Collectors.toList()));
		assertEquals(Arrays.asList(6.3, 7.0), mealLines.stream().map(PaymentLine::getAmount).collect(Collectors.toList()));
		assertEquals("PASSAGER", mealLines.get(0).getCustNo());
		assertEquals("T-1", mealLines.get(0).getTicketNo());
		PaymentLine cashLine = lines.stream().filter(l -> "CLIENT_ESPECES".equals(l.getPaymentHeader().getPaymentClass()))
				.findFirst().get();
		assertEquals(10.0, cashLine.getAmount(), 1e-9);
		assertNull(cashLine.getTitleNumber());
	}
}
