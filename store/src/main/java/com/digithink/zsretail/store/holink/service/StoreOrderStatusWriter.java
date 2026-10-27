package com.digithink.zsretail.store.holink.service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.link.dto.StoreOrderStatusCopyDTO;
import com.digithink.zsretail.store.holink.enumeration.StoreOrderStatus;
import com.digithink.zsretail.store.holink.model.StoreOrder;
import com.digithink.zsretail.store.holink.repository.StoreOrderRepository;

/**
 * 3.1.1: what the head office did with an order of this store (record ORD:&lt;number&gt; of the SUPPLY copies down, kind
 * STORE_ORDER), written on the order by {@link SupplyDownHandler} inside its transaction: SENT, POSTED with its NAV
 * number, or ERROR with the reason. An order this store never sent (a draft, a cancelled one) or does not have is left as
 * it is. See docs/modules/store-orders.md.
 */
@Component
@ConditionalOnInstallation(Installation.SUPPLY_FROM_HEAD_OFFICE)
public class StoreOrderStatusWriter {

	/** The statuses the head office gives. */
	static final List<StoreOrderStatus> FROM_HEAD_OFFICE = Arrays.asList(StoreOrderStatus.SENT, StoreOrderStatus.POSTED,
			StoreOrderStatus.ERROR);

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
		if (order.getStatus() == status && Objects.equals(order.getNavOrderNo(), navOrderNo)
				&& Objects.equals(order.getErrorMessage(), error)) {
			return Outcome.UNCHANGED;
		}
		order.setStatus(status);
		order.setNavOrderNo(navOrderNo);
		order.setErrorMessage(error);
		order.setUpdatedBy("Head office");
		orders.save(order);
		return Outcome.WRITTEN;
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
		throw new IllegalArgumentException("status " + status + " is not one the head office gives (SENT, POSTED, ERROR)");
	}

	private static String blankToNull(String value) {
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}
}
