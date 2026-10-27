package com.digithink.zsretail.headoffice.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.headoffice.model.HoStoreOrder;
import com.digithink.zsretail.headoffice.model.HoStoreOrderLine;
import com.digithink.zsretail.headoffice.repository.HoStoreOrderRepository;

/**
 * Test support (3.1.1): ho_store_order in memory (its lines saved with it, as the cascade does), applying the rules of
 * the JPQL queries of {@link HoStoreOrderRepository} and the unique store and number. The lock is not simulated. The
 * list's sort is by id, newest first (the order of the sort is the database's).
 */
final class InMemoryHoStoreOrders {

	final Map<Long, HoStoreOrder> orders = new LinkedHashMap<>();
	int saves;
	private long nextId;

	InMemoryHoStoreOrders(long firstId) {
		this.nextId = firstId;
	}

	HoStoreOrder byNumber(String number) {
		return orders.values().stream().filter(o -> o.getNumber().equals(number)).findFirst().orElse(null);
	}

	private HoStoreOrder save(HoStoreOrder order) {
		if (orders.values().stream().anyMatch(o -> o != order && o.getStoreId().equals(order.getStoreId())
				&& o.getNumber().equals(order.getNumber()))) {
			throw new DataIntegrityViolationException("uk_ho_store_order_number");
		}
		if (order.getId() == null) {
			order.setId(nextId++);
		}
		for (HoStoreOrderLine line : order.getLines()) {
			if (line.getId() == null) {
				line.setId(nextId++);
			}
		}
		order.setUpdatedAt(LocalDateTime.of(2026, 10, 28, 9, 30));
		orders.put(order.getId(), order);
		saves++;
		return order;
	}

	HoStoreOrderRepository repository() {
		return proxy(HoStoreOrderRepository.class, (method, args) -> {
			switch (method) {
				case "findById":
				case "findForUpdate":
					return Optional.ofNullable(orders.get(args[0]));
				case "findByStoreIdAndNumber":
					return orders.values().stream()
							.filter(o -> o.getStoreId().equals(args[0]) && o.getNumber().equals(args[1])).findFirst();
				case "save":
					return save((HoStoreOrder) args[0]);
				case "findIdsByStatusIn":
					return orders.values().stream().filter(o -> ((Collection<?>) args[0]).contains(o.getStatus()))
							.sorted(Comparator.comparing(HoStoreOrder::getId)).limit(((Pageable) args[1]).getPageSize())
							.map(HoStoreOrder::getId).collect(Collectors.toList());
				case "findForStore":
					return orders.values().stream().filter(o -> o.getStoreId().equals(args[0])
							&& ((Collection<?>) args[1]).contains(o.getNumber())
							&& ((Collection<?>) args[2]).contains(o.getStatus())).collect(Collectors.toList());
				case "findTargets":
					return orders.values().stream().filter(o -> ((Collection<?>) args[0]).contains(o.getStatus()))
							.map(o -> new Object[] { o.getNumber(), o.getStoreId() }).collect(Collectors.toList());
				case "findPage": {
					boolean anyStore = (Long) args[0] == 1L;
					boolean anyStatus = (Long) args[2] == 1L;
					LocalDateTime from = (LocalDateTime) args[4];
					LocalDateTime to = (LocalDateTime) args[5];
					List<HoStoreOrder> rows = orders.values().stream()
							.filter(o -> anyStore || o.getStoreId().equals(args[1]))
							.filter(o -> anyStatus || o.getStatus() == args[3])
							.filter(o -> from == null || !o.getReceivedAt().isBefore(from))
							.filter(o -> to == null || o.getReceivedAt().isBefore(to))
							.sorted(Comparator.comparing(HoStoreOrder::getId).reversed()).collect(Collectors.toList());
					Pageable page = (Pageable) args[6];
					int start = (int) Math.min(rows.size(), page.getOffset());
					int end = Math.min(rows.size(), start + page.getPageSize());
					return new PageImpl<>(new ArrayList<>(rows.subList(start, end)), page, rows.size());
				}
				default:
					return UNHANDLED;
			}
		});
	}
}
