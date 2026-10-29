package com.digithink.zsretail.store.holink.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.link.dto.StoreOrderStatusCopyDTO;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.store.holink.enumeration.StoreOrderStatus;
import com.digithink.zsretail.store.holink.model.StoreOrder;
import com.digithink.zsretail.store.holink.model.StoreOrderColis;
import com.digithink.zsretail.store.holink.model.StoreOrderLine;
import com.digithink.zsretail.store.holink.repository.StoreOrderRepository;

/**
 * 3.1.1: what the head office did with an order of this store (record ORD:&lt;number&gt; of the SUPPLY copies down, kind
 * STORE_ORDER), written on the order by {@link SupplyDownHandler} inside its transaction: SENT, POSTED with its NAV
 * number and the answer of NAV (its message, per line by item code the quantity accepted and its message), ERROR
 * with the reason, or DELIVERED with NAV's invoice number and its posting date. An order this store never sent (a draft,
 * a cancelled one) or does not have is left as it is. See docs/modules/store-orders.md.
 */
@Component
@ConditionalOnInstallation(Installation.SUPPLY_FROM_HEAD_OFFICE)
public class StoreOrderStatusWriter {

	/** The statuses the head office gives. */
	static final List<StoreOrderStatus> FROM_HEAD_OFFICE = Arrays.asList(StoreOrderStatus.SENT, StoreOrderStatus.POSTED,
			StoreOrderStatus.ERROR, StoreOrderStatus.DELIVERED);

	/** What one record did. */
	public enum Outcome {
		/** The order changed. */
		WRITTEN,
		/** The order says this already. */
		UNCHANGED,
		/** No order of this number here (e.g. the store's database restored). */
		UNKNOWN_ORDER,
		/** The order is a draft or cancelled here: never sent from this store. */
		NOT_SENT
	}

	private final StoreOrderRepository orders;

	public StoreOrderStatusWriter(StoreOrderRepository orders) {
		this.orders = orders;
	}

	/** Applies one record; IllegalArgumentException for a record without a number or with another status. */
	public Outcome apply(StoreOrderStatusCopyDTO copy) {
		StoreOrderStatus status = statusOf(copy.getStatus());
		StoreOrder order = orders.findByNumber(copy.getOrderNumber().trim()).orElse(null);
		if (order == null) {
			return Outcome.UNKNOWN_ORDER;
		}
		if (!FROM_HEAD_OFFICE.contains(order.getStatus())) {
			return Outcome.NOT_SENT;
		}
		String navOrderNo = blankToNull(copy.getNavOrderNo());
		String error = status == StoreOrderStatus.ERROR
				? DeliveryReceptionService.cut(blankToNull(copy.getErrorMessage()), StoreOrder.ERROR_LENGTH)
				: null;
		String navMessage = DeliveryReceptionService.cut(blankToNull(copy.getNavMessage()), StoreOrder.NAV_MESSAGE_LENGTH);
		boolean delivered = status == StoreOrderStatus.DELIVERED;
		String invoiceNo = delivered
				? DeliveryReceptionService.cut(blankToNull(copy.getInvoiceNo()), StoreOrder.INVOICE_NO_LENGTH)
				: null;
		LocalDate deliveredAt = delivered ? dayOf(copy.getDeliveredAt()) : null;
		Map<String, StoreOrderStatusCopyDTO.Line> answers = new HashMap<>();
		if (copy.getLines() != null) {
			for (StoreOrderStatusCopyDTO.Line line : copy.getLines()) {
				if (line != null && line.getItemCode() != null) {
					answers.put(line.getItemCode().trim(), line);
				}
			}
		}
		boolean linesChanged = false;
		for (StoreOrderLine line : order.getLines()) {
			StoreOrderStatusCopyDTO.Line answer = answers.get(line.getItemCode() == null ? "" : line.getItemCode().trim());
			BigDecimal accepted = answer == null || answer.getAcceptedQty() == null ? null
					: answer.getAcceptedQty().setScale(Quantities.SCALE, RoundingMode.HALF_UP);
			String message = answer == null ? null
					: DeliveryReceptionService.cut(blankToNull(answer.getMessage()), StoreOrder.NAV_MESSAGE_LENGTH);
			if (!sameQuantity(line.getNavAcceptedQty(), accepted) || !Objects.equals(line.getNavLineMessage(), message)) {
				line.setNavAcceptedQty(accepted);
				line.setNavLineMessage(message);
				linesChanged = true;
			}
		}
		// 3.1.1: NAV's answer on the colis, by ERP item number (a row not in the copy keeps none)
		Map<String, StoreOrderStatusCopyDTO.Colis> colisAnswers = new HashMap<>();
		if (copy.getColis() != null) {
			for (StoreOrderStatusCopyDTO.Colis answer : copy.getColis()) {
				if (answer != null && answer.getNavItemNo() != null) {
					colisAnswers.put(answer.getNavItemNo().trim(), answer);
				}
			}
		}
		for (StoreOrderColis colis : order.getColis()) {
			StoreOrderStatusCopyDTO.Colis answer = colisAnswers.get(colis.getNavItemNo().trim());
			Integer accepted = answer == null ? null : answer.getAcceptedColis();
			BigDecimal pieces = answer == null || answer.getAcceptedQty() == null ? null
					: answer.getAcceptedQty().setScale(Quantities.SCALE, RoundingMode.HALF_UP);
			String number = answer == null ? null
					: DeliveryReceptionService.cut(blankToNull(answer.getColisNumber()), StoreOrderColis.COLIS_NUMBER_LENGTH);
			String message = answer == null ? null
					: DeliveryReceptionService.cut(blankToNull(answer.getMessage()), StoreOrder.NAV_MESSAGE_LENGTH);
			if (!Objects.equals(colis.getNavAcceptedColis(), accepted) || !sameQuantity(colis.getNavAcceptedQty(), pieces)
					|| !Objects.equals(colis.getNavColisNumber(), number) || !Objects.equals(colis.getNavMessage(), message)) {
				colis.setNavAcceptedColis(accepted);
				colis.setNavAcceptedQty(pieces);
				colis.setNavColisNumber(number);
				colis.setNavMessage(message);
				linesChanged = true;
			}
		}
		if (order.getStatus() == status && Objects.equals(order.getNavOrderNo(), navOrderNo)
				&& Objects.equals(order.getErrorMessage(), error) && Objects.equals(order.getNavMessage(), navMessage)
				&& Objects.equals(order.getInvoiceNo(), invoiceNo) && Objects.equals(order.getDeliveredAt(), deliveredAt)
				&& !linesChanged) {
			return Outcome.UNCHANGED;
		}
		order.setStatus(status);
		order.setNavOrderNo(navOrderNo);
		order.setErrorMessage(error);
		order.setNavMessage(navMessage);
		order.setInvoiceNo(invoiceNo);
		order.setDeliveredAt(deliveredAt);
		order.setUpdatedBy("Head office");
		orders.save(order);
		return Outcome.WRITTEN;
	}

	/** yyyy-MM-dd; null when absent or unreadable (information only). */
	static LocalDate dayOf(String text) {
		if (text == null || text.trim().isEmpty()) {
			return null;
		}
		try {
			return LocalDate.parse(text.trim());
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static boolean sameQuantity(BigDecimal first, BigDecimal second) {
		return first == null ? second == null : second != null && first.compareTo(second) == 0;
	}

	static StoreOrderStatus statusOf(String status) {
		try {
			StoreOrderStatus value = StoreOrderStatus.valueOf(status == null ? "" : status.trim().toUpperCase(Locale.ROOT));
			if (FROM_HEAD_OFFICE.contains(value)) {
				return value;
			}
		} catch (IllegalArgumentException e) {
			// below
		}
		throw new IllegalArgumentException(
				"status " + status + " is not one the head office gives (SENT, POSTED, ERROR, DELIVERED)");
	}

	private static String blankToNull(String value) {
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}
}
