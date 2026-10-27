package com.digithink.zsretail.headoffice.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.erp.service.ErpSynchronizationManager;
import com.digithink.zsretail.core.utils.ErrorTexts;
import com.digithink.zsretail.erp.api.ErpStoreOrderPosting;
import com.digithink.zsretail.erp.api.ErpSyncWarningException;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderDTO;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderResult;
import com.digithink.zsretail.headoffice.dto.HoStoreOrderDTO;
import com.digithink.zsretail.headoffice.enumeration.HoStoreOrderStatus;
import com.digithink.zsretail.headoffice.repository.HoStoreOrderRepository;

import lombok.extern.log4j.Log4j2;

/**
 * 3.1.1: the job POST_STORE_ORDERS and "retry now", on a head office with headoffice.storeorders.post=ERP. Each run takes
 * up to {@value #BATCH} orders waiting (SENT, ERROR), oldest first, and posts each one as a sales order of the ERP
 * through the connector (ErpSynchronizationManager: one row of the communications log per order): the connector looks up
 * an order of the same external document number first, so an order posted again after a lost answer is never doubled.
 * Success: POSTED with the ERP's number and its answer (its message, the quantity accepted and the message per line);
 * failure: ERROR with the reason, retried at the next run (no limit). The ERP is called outside any transaction; an
 * order being posted is not posted a second time at once (the job and "retry now"). A REJECTED order is never taken.
 * See docs/modules/store-orders.md.
 */
@Service
@ConditionalOnInstallation(Installation.HEAD_OFFICE_STORE_ORDERS_ERP)
@Log4j2
public class HoStoreOrderPosting implements ErpStoreOrderPosting {

	static final int BATCH = 50;
	static final int TIMEOUT_SECONDS = 15;
	static final List<HoStoreOrderStatus> WAITING = Arrays.asList(HoStoreOrderStatus.SENT, HoStoreOrderStatus.ERROR);

	private final HoStoreOrderService service;
	private final HoStoreOrderRepository orders;
	/** One posting into the ERP, logged (ErpSynchronizationManager.postSalesOrder). */
	private final Function<ErpSalesOrderDTO, ErpSalesOrderResult> poster;
	private final TransactionOperations reads;

	/** The orders being posted now, by id. */
	private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

	@Autowired
	public HoStoreOrderPosting(HoStoreOrderService service, HoStoreOrderRepository orders,
			ErpSynchronizationManager erp, PlatformTransactionManager transactionManager) {
		this(service, orders, erp::postSalesOrder, readOnly(transactionManager));
	}

	/** With a given poster and transactions: used by the tests. */
	public HoStoreOrderPosting(HoStoreOrderService service, HoStoreOrderRepository orders,
			Function<ErpSalesOrderDTO, ErpSalesOrderResult> poster, TransactionOperations reads) {
		this.service = service;
		this.orders = orders;
		this.poster = poster;
		this.reads = reads;
	}

	private static TransactionTemplate readOnly(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setReadOnly(true);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	/**
	 * One run of the job. Throws ErpSyncWarningException naming the first failure when an order was not posted (the job
	 * shows WARNING; the orders stay in ERROR, retried at the next run).
	 */
	@Override
	public void postStoreOrders() {
		List<Long> ids = reads.execute(status -> orders.findIdsByStatusIn(WAITING, PageRequest.of(0, BATCH)));
		int posted = 0;
		int failed = 0;
		String firstFailure = null;
		for (Long id : ids == null ? Collections.<Long>emptyList() : ids) {
			Optional<HoStoreOrderDTO> outcome;
			try {
				outcome = post(id);
			} catch (IllegalStateException e) {
				continue; // posted by "retry now" at this moment, or no longer waiting
			}
			if (!outcome.isPresent()) {
				continue;
			}
			if (HoStoreOrderStatus.POSTED.name().equals(outcome.get().getStatus())) {
				posted++;
			} else {
				failed++;
				if (firstFailure == null) {
					firstFailure = outcome.get().getNumber() + ": " + outcome.get().getErrorMessage();
				}
			}
		}
		if (posted + failed > 0) {
			log.info("Store orders into the ERP: {} posted, {} in error", posted, failed);
		}
		if (failed > 0) {
			throw new ErpSyncWarningException(failed + " of " + (posted + failed) + " store orders not posted (they stay in"
					+ " ERROR and are posted again at the next run); first: " + firstFailure);
		}
	}

	/**
	 * Posts one order now (the job, or "retry now"): the order as it is after the posting. Empty for an unknown order;
	 * IllegalStateException for an order not waiting (POSTED, REJECTED) or being posted right now.
	 */
	public Optional<HoStoreOrderDTO> post(Long id) {
		if (!inFlight.add(id)) {
			throw new IllegalStateException("This order is being posted into the ERP right now");
		}
		try {
			Optional<ErpSalesOrderDTO> sales;
			try {
				sales = service.salesOrderToPost(id);
			} catch (IllegalStateException noCustomer) {
				return service.recordPosting(id, null, noCustomer.getMessage());
			}
			if (!sales.isPresent()) {
				Optional<HoStoreOrderDTO> order = service.get(id);
				if (order.isPresent()) {
					throw new IllegalStateException("Order " + order.get().getNumber() + " is " + order.get().getStatus()
							+ ": only an order waiting (SENT, ERROR) is posted");
				}
				return Optional.empty();
			}
			String navOrderNo = null;
			ErpSalesOrderResult answer = null;
			String failure;
			try {
				ErpSalesOrderResult result = poster.apply(sales.get());
				if (result != null && result.isSuccess()) {
					navOrderNo = result.getExternalReference();
					answer = result;
					failure = null;
				} else {
					failure = result == null || result.getMessage() == null ? "the ERP refused the order"
							: result.getMessage();
				}
			} catch (RuntimeException e) {
				failure = ErrorTexts.cause(e);
			}
			return service.recordPosting(id, navOrderNo, answer, failure);
		} finally {
			inFlight.remove(id);
		}
	}
}
