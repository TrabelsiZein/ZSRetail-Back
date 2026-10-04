package com.digithink.zsretail.holink.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSupply;
import com.digithink.zsretail.headoffice.dto.DeliveryCopyDTO;
import com.digithink.zsretail.headoffice.dto.SupplyInvoiceCopyDTO;
import com.digithink.zsretail.holink.dto.DownApplyResult;
import com.digithink.zsretail.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.holink.model.DownRecord;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 7A.3: the store side of the SUPPLY domain on the copies down, on a store whose goods come from
 * the head office. Each BL (record BL:&lt;number&gt;) is saved TO_RECEIVE by {@link DeliveryReceptionService}, in its
 * own transaction, and tracked in hol_down_record (APPLIED; the information names the items not in this store yet). A
 * BL already here is never changed. A removed code (a BL the head office no longer has, e.g. its database restored)
 * only drops the tracking row: a BL the store has is a document, never deleted. Every cycle ({@link #retry}), also when
 * the head office is unreachable, the lines waiting for their item get it, and the confirmed lines waiting for their
 * item get their stock once.
 */
@Component
@ConditionalOnHeadOfficeSupply
@Log4j2
public class SupplyDownHandler implements DownHandler {

	static final int TIMEOUT_SECONDS = 15;
	static final String NO_CODE = "?";

	static final ObjectMapper COPY_MAPPER = new ObjectMapper()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	private final DeliveryReceptionService reception;
	private final DownRecordLog records;
	private final TransactionOperations transactions;

	/** Step 7B: writes the invoices (records INV:, kind INVOICE). */
	private final SupplyInvoiceWriter invoices;

	@Autowired
	public SupplyDownHandler(DeliveryReceptionService reception, DownRecordLog records, SupplyInvoiceWriter invoices,
			PlatformTransactionManager transactionManager) {
		this(reception, records, timed(transactionManager), invoices);
	}

	/** With given transactions, without invoices (7A): used by the tests. */
	public SupplyDownHandler(DeliveryReceptionService reception, DownRecordLog records,
			TransactionOperations transactions) {
		this(reception, records, transactions, null);
	}

	/** With given transactions and invoices: used by the tests. */
	public SupplyDownHandler(DeliveryReceptionService reception, DownRecordLog records,
			TransactionOperations transactions, SupplyInvoiceWriter invoices) {
		this.invoices = invoices;
		this.reception = reception;
		this.records = records;
		this.transactions = transactions;
	}

	private static TransactionTemplate timed(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	@Override
	public DataDomain getDomain() {
		return DataDomain.SUPPLY;
	}

	@Override
	public DownApplyResult apply(List<JsonNode> page, List<String> removed) {
		DownApplyResult result = DownApplyResult.none();
		for (JsonNode record : page) {
			JsonNode kind = record == null ? null : record.get("kind");
			if (kind != null && SupplyInvoiceCopyDTO.KIND.equals(kind.asText())) {
				applyInvoice(record, result); // step 7B
				continue;
			}
			DeliveryCopyDTO copy;
			try {
				copy = COPY_MAPPER.treeToValue(record, DeliveryCopyDTO.class);
			} catch (Exception e) {
				result.addError(NO_CODE, "unreadable record (" + SalesCopyFinder.cause(e) + ")");
				continue;
			}
			if (copy.getNumber() == null || copy.getNumber().trim().isEmpty()) {
				result.addError(NO_CODE, "record without a BL number");
				continue;
			}
			String code = DeliveryCopyDTO.recordCode(copy.getNumber());
			String payload = record.toString();
			try {
				DeliveryReceptionService.Outcome outcome = transactions.execute(status -> {
					DeliveryReceptionService.Outcome saved = reception.saveReceived(copy);
					String info = saved.getMissingItems().isEmpty() ? null
							: "items not in this store yet: " + String.join(", ", saved.getMissingItems());
					records.track(DataDomain.SUPPLY, code, copy.getNumber(), DownRecordStatus.APPLIED, null, info,
							payload);
					return saved;
				});
				if (outcome.isWritten()) {
					result.addApplied();
				} else {
					result.addUnchanged();
				}
			} catch (RuntimeException e) {
				String reason = "not saved (" + SalesCopyFinder.cause(e) + ")";
				result.addError(code, reason);
				try {
					transactions.executeWithoutResult(status -> records.track(DataDomain.SUPPLY, code, copy.getNumber(),
							DownRecordStatus.ERROR, reason, null, payload));
				} catch (RuntimeException ignored) {
					// the error is counted; the record comes again with a later change or is retried below
				}
			}
		}
		for (String code : removed) {
			try {
				if (Boolean.TRUE.equals(transactions.execute(status -> records.remove(DataDomain.SUPPLY, code)))) {
					result.addRemoved();
				}
			} catch (RuntimeException e) {
				result.addError(code, "not removed (" + SalesCopyFinder.cause(e) + ")");
			}
		}
		return result;
	}

	/**
	 * Step 7B: an invoice (record INV:&lt;number&gt;) saved once as a purchase invoice by {@link SupplyInvoiceWriter}, in
	 * its own transaction, tracked APPLIED (the information names the items not in this store) or ERROR (retried).
	 */
	private void applyInvoice(JsonNode record, DownApplyResult result) {
		SupplyInvoiceCopyDTO copy;
		try {
			copy = COPY_MAPPER.treeToValue(record, SupplyInvoiceCopyDTO.class);
		} catch (Exception e) {
			result.addError(NO_CODE, "unreadable record (" + SalesCopyFinder.cause(e) + ")");
			return;
		}
		if (copy.getInvoiceNumber() == null || copy.getInvoiceNumber().trim().isEmpty()) {
			result.addError(NO_CODE, "record without an invoice number");
			return;
		}
		String code = SupplyInvoiceCopyDTO.recordCode(copy.getInvoiceNumber());
		String payload = record.toString();
		try {
			if (invoices == null) {
				throw new IllegalStateException("no invoice writer on this store");
			}
			SupplyInvoiceWriter.Outcome outcome = transactions.execute(status -> {
				SupplyInvoiceWriter.Outcome saved = invoices.save(copy);
				String info = saved.getMissingItems().isEmpty() ? null
						: "items not in this store: " + String.join(", ", saved.getMissingItems());
				records.track(DataDomain.SUPPLY, code, copy.getInvoiceNumber(), DownRecordStatus.APPLIED, null, info,
						payload);
				return saved;
			});
			if (outcome.isWritten()) {
				result.addApplied();
			} else {
				result.addUnchanged();
			}
		} catch (RuntimeException e) {
			String reason = "not saved (" + SalesCopyFinder.cause(e) + ")";
			result.addError(code, reason);
			try {
				transactions.executeWithoutResult(status -> records.track(DataDomain.SUPPLY, code,
						copy.getInvoiceNumber(), DownRecordStatus.ERROR, reason, null, payload));
			} catch (RuntimeException ignored) {
				// counted; retried below at the next cycle
			}
		}
	}

	/**
	 * Every cycle: BLs and invoices in ERROR are saved again from their copy; the lines waiting for their item are completed and their
	 * stock applied once (see {@link DeliveryReceptionService#applyWaitingStock}). A BL whose stock still waits counts
	 * as waiting (the job result is WARNING).
	 */
	@Override
	public DownApplyResult retry() {
		DownApplyResult result = DownApplyResult.none();
		try {
			List<DownRecord> errors = transactions
					.execute(status -> records.toRetry(DataDomain.SUPPLY));
			if (errors != null) {
				for (DownRecord row : errors) {
					try {
						result.add(apply(Collections.singletonList(COPY_MAPPER.readTree(row.getPayload())),
								Collections.emptyList()));
					} catch (Exception e) {
						result.addError(row.getRecordCode(), row.getReason());
					}
				}
			}
			Map<String, List<String>> waiting = reception.applyWaitingStock();
			for (Map.Entry<String, List<String>> entry : waiting.entrySet()) {
				result.addWaiting(DeliveryCopyDTO.recordCode(entry.getKey()),
						"stock in waits: not in this store: item " + String.join(", ", entry.getValue()));
			}
		} catch (RuntimeException e) {
			result.addError(NO_CODE, "retry failed (" + SalesCopyFinder.cause(e) + ")");
		}
		return result;
	}
}
