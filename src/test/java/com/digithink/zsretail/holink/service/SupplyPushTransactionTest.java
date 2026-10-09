package com.digithink.zsretail.holink.service;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.headoffice.dto.DeliveryConfirmationDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.SalesPushAnswer;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.model.LinkExchange;
import com.digithink.zsretail.holink.model.ReceivedDelivery;
import com.digithink.zsretail.holink.model.ReceivedDeliveryLine;
import com.digithink.zsretail.holink.repository.LinkExchangeRepository;
import com.digithink.zsretail.holink.repository.ReceivedDeliveryRepository;
import com.digithink.zsretail.holink.repository.StockCopyRepository;
import com.digithink.zsretail.utils.Quantities;

/**
 * L2 of step 7A: the lines of a received BL are a lazy collection. The confirmation copies must be built inside the read
 * transaction; built after it, every push failed with a LazyInitializationException and no BL ever became Received at
 * the head office. The BL of this test throws like Hibernate when its lines are read outside a transaction.
 */
class SupplyPushTransactionTest {

	private boolean inTransaction;

	/** Runs the callback with the flag set, like a transaction template. */
	private final TransactionOperations transactions = new TransactionOperations() {
		@Override
		public <T> T execute(TransactionCallback<T> action) {
			inTransaction = true;
			try {
				return action.doInTransaction(null);
			} finally {
				inTransaction = false;
			}
		}
	};

	/** A received BL whose lines, like a lazy collection, can only be read inside a transaction. */
	private final class LazyDelivery extends ReceivedDelivery {
		private static final long serialVersionUID = 1L;

		@Override
		public List<ReceivedDeliveryLine> getLines() {
			if (!inTransaction) {
				throw new LazyInitializationException("failed to lazily initialize a collection: no Session");
			}
			return super.getLines();
		}
	}

	@Test
	@DisplayName("A confirmation is built inside the read transaction, sent and marked SENT")
	void confirmationBuiltInsideTheTransaction() {
		LazyDelivery delivery = new LazyDelivery();
		delivery.setId(7L);
		delivery.setNumber("BL-000001");
		delivery.setReceivedAt(LocalDateTime.of(2026, 10, 4, 10, 0));
		delivery.setReceivedBy("admin");
		delivery.setPushStatus(SalesCopyStatus.PENDING);
		delivery.setAttempts(0);
		ReceivedDeliveryLine line = new ReceivedDeliveryLine();
		line.setLineNo(1);
		line.setItemCode("B001");
		line.setQuantitySent(Quantities.of(50));
		line.setQuantityReceived(Quantities.of(48));
		inTransaction = true;
		delivery.getLines().add(line);
		inTransaction = false;

		boolean[] queued = { false };
		ReceivedDeliveryRepository deliveries = proxy(ReceivedDeliveryRepository.class, (method, args) -> {
			switch (method) {
				case "findPushQueue":
					if (queued[0] || delivery.getPushStatus() == SalesCopyStatus.SENT) {
						return new ArrayList<>();
					}
					queued[0] = true;
					return new ArrayList<>(Collections.singletonList(delivery));
				case "findById":
					return Optional.of(delivery);
				case "save":
					return args[0];
				default:
					return UNHANDLED;
			}
		});
		StockCopyRepository stockCopies = proxy(StockCopyRepository.class,
				(method, args) -> "findToSend".equals(method) || "findRemoved".equals(method) ? new ArrayList<>() : UNHANDLED);
		LinkExchangeLog exchangeLog = new LinkExchangeLog(proxy(LinkExchangeRepository.class,
				(method, args) -> "save".equals(method) ? (LinkExchange) args[0] : UNHANDLED), transactions, 30);
		List<DeliveryConfirmationDTO> sent = new ArrayList<>();
		HeadOfficeClient client = new HeadOfficeClient(new RestTemplate(), null, "http://localhost:888/zsretail/api", "key",
				"2.1.0") {
			@Override
			public SalesPushAnswer pushDeliveryConfirmations(List<DeliveryConfirmationDTO> confirmations) {
				sent.addAll(confirmations);
				return SalesPushAnswer.delivered(Collections.singletonList(SalesCopyResultDTO.accepted("BL-000001")));
			}
		};
		SupplyPushService push = new SupplyPushService(client, deliveries, stockCopies, exchangeLog, transactions,
				() -> LocalDateTime.of(2026, 10, 4, 10, 1), 60);

		SupplyPushService.Cycle cycle = push.runCycle();

		assertEquals(1, cycle.getConfirmationsSent());
		assertEquals(1, sent.size());
		assertEquals("BL-000001", sent.get(0).getNumber());
		assertEquals(BigDecimal.valueOf(48), sent.get(0).getLines().get(0).getQuantityReceived());
		assertEquals(SalesCopyStatus.SENT, delivery.getPushStatus());
	}
}
