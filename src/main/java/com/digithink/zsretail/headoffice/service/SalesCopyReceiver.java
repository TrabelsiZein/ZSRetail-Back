package com.digithink.zsretail.headoffice.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.dto.PaymentCopyDTO;
import com.digithink.zsretail.headoffice.dto.ReturnCopyDTO;
import com.digithink.zsretail.headoffice.dto.ReturnLineCopyDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.headoffice.dto.SessionCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketLineCopyDTO;
import com.digithink.zsretail.headoffice.model.HoReturn;
import com.digithink.zsretail.headoffice.model.HoSession;
import com.digithink.zsretail.headoffice.model.HoTicket;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoReturnRepository;
import com.digithink.zsretail.headoffice.repository.HoSessionRepository;
import com.digithink.zsretail.headoffice.repository.HoTicketRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 2.3: saves the copies a store sends, by store + document number. Head office only.
 * <p>
 * The store is the authenticated principal; it is never saved, only referenced by id. Each document is saved in its
 * own transaction and gets its own result, so one bad document does not block the batch. A document received again
 * replaces its row's content, lines included: a repeated push leaves one row, a changed document is not duplicated.
 */
@Service
@ConditionalOnHeadOffice
@Log4j2
public class SalesCopyReceiver {

	/** Longest rejection reason returned to the store. */
	static final int MESSAGE_LENGTH = 500;

	private final HoTicketRepository tickets;
	private final HoReturnRepository returns;
	private final HoSessionRepository sessions;
	private final StoreRepository stores;
	private final TransactionOperations transactions;

	@Autowired
	public SalesCopyReceiver(HoTicketRepository tickets, HoReturnRepository returns, HoSessionRepository sessions,
			StoreRepository stores, PlatformTransactionManager transactionManager) {
		this(tickets, returns, sessions, stores, new TransactionTemplate(transactionManager));
	}

	/** With given transactions: used by the tests. */
	public SalesCopyReceiver(HoTicketRepository tickets, HoReturnRepository returns, HoSessionRepository sessions,
			StoreRepository stores, TransactionOperations transactions) {
		this.tickets = tickets;
		this.returns = returns;
		this.sessions = sessions;
		this.stores = stores;
		this.transactions = transactions;
	}

	public List<SalesCopyResultDTO> receiveTickets(Store store, List<TicketCopyDTO> copies) {
		return receive(store, "tickets", copies, TicketCopyDTO::getSalesNumber, copy -> saveTicket(store, copy));
	}

	public List<SalesCopyResultDTO> receiveReturns(Store store, List<ReturnCopyDTO> copies) {
		return receive(store, "returns", copies, ReturnCopyDTO::getReturnNumber, copy -> saveReturn(store, copy));
	}

	public List<SalesCopyResultDTO> receiveSessions(Store store, List<SessionCopyDTO> copies) {
		return receive(store, "sessions", copies, SessionCopyDTO::getSessionNumber, copy -> saveSession(store, copy));
	}

	/** One transaction and one result per document, in batch order. */
	private <T> List<SalesCopyResultDTO> receive(Store store, String kind, List<T> copies, Function<T, String> number,
			Consumer<T> save) {
		List<SalesCopyResultDTO> results = new ArrayList<>();
		int rejected = 0;
		for (T copy : copies == null ? Collections.<T>emptyList() : copies) {
			String documentNumber = copy == null ? null : number.apply(copy);
			try {
				if (copy == null) {
					throw new IllegalArgumentException("empty document");
				}
				transactions.executeWithoutResult(status -> save.accept(copy));
				results.add(SalesCopyResultDTO.accepted(documentNumber));
			} catch (RuntimeException e) {
				String reason = reason(e);
				results.add(SalesCopyResultDTO.rejected(documentNumber, reason));
				rejected++;
				log.warn("Head office: {} copy '{}' from store '{}' rejected: {}", kind, documentNumber, store.getCode(),
						reason);
			}
		}
		log.info("Head office: {} {} received from store '{}' ({} accepted, {} rejected)", results.size(), kind,
				store.getCode(), results.size() - rejected, rejected);
		return results;
	}

	private void saveTicket(Store store, TicketCopyDTO copy) {
		required(copy.getSalesNumber(), "salesNumber");
		required(copy.getSalesDate(), "salesDate");
		required(copy.getStatus(), "status");
		List<TicketLineCopyDTO> lines = orEmpty(copy.getLines());
		for (int i = 0; i < lines.size(); i++) {
			required(lines.get(i), "line " + (i + 1));
			required(lines.get(i).getItemCode(), "line " + (i + 1) + ": itemCode");
			required(lines.get(i).getQuantity(), "line " + (i + 1) + ": quantity");
		}
		List<PaymentCopyDTO> payments = orEmpty(copy.getPayments());
		for (int i = 0; i < payments.size(); i++) {
			required(payments.get(i), "payment " + (i + 1));
			required(payments.get(i).getPaymentMethodCode(), "payment " + (i + 1) + ": paymentMethodCode");
			required(payments.get(i).getAmount(), "payment " + (i + 1) + ": amount");
		}
		HoTicket ticket = tickets.findByStoreIdAndSalesNumber(store.getId(), copy.getSalesNumber()).orElseGet(() -> {
			HoTicket created = new HoTicket();
			created.setStore(stores.getOne(store.getId()));
			return created;
		});
		HoSalesCopyMapper.apply(ticket, copy);
		ticket.setUpdatedAt(LocalDateTime.now());
		tickets.save(ticket);
	}

	private void saveReturn(Store store, ReturnCopyDTO copy) {
		required(copy.getReturnNumber(), "returnNumber");
		required(copy.getReturnDate(), "returnDate");
		required(copy.getStatus(), "status");
		List<ReturnLineCopyDTO> lines = orEmpty(copy.getLines());
		for (int i = 0; i < lines.size(); i++) {
			required(lines.get(i), "line " + (i + 1));
			required(lines.get(i).getItemCode(), "line " + (i + 1) + ": itemCode");
			required(lines.get(i).getQuantity(), "line " + (i + 1) + ": quantity");
		}
		HoReturn row = returns.findByStoreIdAndReturnNumber(store.getId(), copy.getReturnNumber()).orElseGet(() -> {
			HoReturn created = new HoReturn();
			created.setStore(stores.getOne(store.getId()));
			return created;
		});
		HoSalesCopyMapper.apply(row, copy);
		row.setUpdatedAt(LocalDateTime.now());
		returns.save(row);
	}

	private void saveSession(Store store, SessionCopyDTO copy) {
		required(copy.getSessionNumber(), "sessionNumber");
		required(copy.getOpenedAt(), "openedAt");
		required(copy.getStatus(), "status");
		HoSession row = sessions.findByStoreIdAndSessionNumber(store.getId(), copy.getSessionNumber()).orElseGet(() -> {
			HoSession created = new HoSession();
			created.setStore(stores.getOne(store.getId()));
			return created;
		});
		HoSalesCopyMapper.apply(row, copy);
		row.setUpdatedAt(LocalDateTime.now());
		sessions.save(row);
	}

	private static void required(Object value, String field) {
		if (value == null || value instanceof String && ((String) value).trim().isEmpty()) {
			throw new IllegalArgumentException(field + " is required");
		}
	}

	private static <T> List<T> orEmpty(List<T> list) {
		return list == null ? Collections.emptyList() : list;
	}

	/** A validation message as it is; otherwise the most specific cause. Cut to {@value #MESSAGE_LENGTH} characters. */
	static String reason(RuntimeException e) {
		String reason;
		if (e instanceof IllegalArgumentException && e.getMessage() != null) {
			reason = e.getMessage();
		} else {
			Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
			reason = cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
		}
		return reason.length() > MESSAGE_LENGTH ? reason.substring(0, MESSAGE_LENGTH) : reason;
	}
}
