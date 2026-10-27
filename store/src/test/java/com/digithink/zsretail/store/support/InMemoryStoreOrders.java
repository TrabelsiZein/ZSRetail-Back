package com.digithink.zsretail.store.support;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.store.holink.enumeration.StoreOrderStatus;
import com.digithink.zsretail.store.holink.model.StoreOrder;
import com.digithink.zsretail.store.holink.model.StoreOrderLine;
import com.digithink.zsretail.store.holink.repository.StoreOrderRepository;

/**
 * Test support (3.1.1): hol_order of one store in memory (its lines saved with it, as the cascade does), applying the
 * rules of the JPQL queries of {@link StoreOrderRepository}, the unique number (uk_hol_order_number) and one DRAFT at a
 * time (ux_hol_order_draft). The locks are not simulated: findForUpdate reads as findById.
 */
public final class InMemoryStoreOrders {

	public final Map<Long, StoreOrder> orders = new LinkedHashMap<>();
	public int saves;
	private long nextId;

	public InMemoryStoreOrders(long firstId) {
		this.nextId = firstId;
	}

	public StoreOrder byNumber(String number) {
		return orders.values().stream().filter(o -> o.getNumber().equals(number)).findFirst().orElse(null);
	}

	public List<StoreOrder> drafts() {
		return orders.values().stream().filter(o -> o.getStatus() == StoreOrderStatus.DRAFT).collect(Collectors.toList());
	}

	/** An order already in the table, e.g. of an earlier day. */
	public StoreOrder add(String number, StoreOrderStatus status) {
		StoreOrder order = new StoreOrder();
		order.setNumber(number);
		order.setStatus(status);
		return save(order);
	}

	private StoreOrder save(StoreOrder order) {
		boolean numberTaken = orders.values().stream()
				.anyMatch(o -> o != order && o.getNumber().equals(order.getNumber()));
		boolean secondDraft = order.getStatus() == StoreOrderStatus.DRAFT
				&& orders.values().stream().anyMatch(o -> o != order && o.getStatus() == StoreOrderStatus.DRAFT);
		if (numberTaken || secondDraft) {
			throw new DataIntegrityViolationException(numberTaken ? "uk_hol_order_number" : "ux_hol_order_draft");
		}
		if (order.getId() == null) {
			order.setId(nextId++);
		}
		for (StoreOrderLine line : order.getLines()) {
			if (line.getId() == null) {
				line.setId(nextId++);
			}
		}
		orders.put(order.getId(), order);
		saves++;
		return order;
	}

	public StoreOrderRepository repository() {
		return proxy(StoreOrderRepository.class, (method, args) -> {
			switch (method) {
				case "findById":
				case "findForUpdate":
					return Optional.ofNullable(orders.get(args[0]));
				case "findByNumber":
					return Optional.ofNullable(byNumber((String) args[0]));
				case "findByStatusOrderByIdAsc":
					return orders.values().stream().filter(o -> o.getStatus() == args[0])
							.sorted(Comparator.comparing(StoreOrder::getId)).collect(Collectors.toList());
				case "findLastNumber": {
					String prefix = ((String) args[0]).replace("[%]", "%").replace("[_]", "_").replace("[[]", "[");
					String start = prefix.substring(0, prefix.length() - 1); // the trailing % of the pattern
					return orders.values().stream().map(StoreOrder::getNumber).filter(n -> n.startsWith(start))
							.max(Comparator.naturalOrder()).orElse(null);
				}
				case "save":
					return save((StoreOrder) args[0]);
				case "findPage": {
					boolean any = (Long) args[0] == 1L;
					LocalDateTime from = (LocalDateTime) args[2];
					LocalDateTime to = (LocalDateTime) args[3];
					String text = (Long) args[4] == 1L ? null : textOf((String) args[5]);
					List<StoreOrder> rows = orders.values().stream().filter(o -> any || o.getStatus() == args[1])
							.filter(o -> from == null || !o.getCreatedAt().isBefore(from))
							.filter(o -> to == null || o.getCreatedAt().isBefore(to))
							.filter(o -> text == null || matches(text, o.getNumber(), o.getNavOrderNo())
									|| o.getLines().stream().anyMatch(l -> matches(text, l.getItemCode(), l.getNavItemNo(),
											l.getDescription())))
							.sorted(Comparator.comparing(StoreOrder::getId).reversed()).collect(Collectors.toList());
					Pageable page = (Pageable) args[6];
					int startRow = (int) Math.min(rows.size(), page.getOffset());
					int end = Math.min(rows.size(), startRow + page.getPageSize());
					return new PageImpl<>(new ArrayList<>(rows.subList(startRow, end)), page, rows.size());
				}
				default:
					return UNHANDLED;
			}
		});
	}

	/** The searched text of a LIKE pattern '%text%' (lower case, its escapes undone). */
	static String textOf(String pattern) {
		String text = pattern.substring(1, pattern.length() - 1);
		return text.replace("\\%", "%").replace("\\_", "_").replace("\\[", "[").replace("\\\\", "\\");
	}

	/** One of the values contains the text, ignoring case. */
	static boolean matches(String text, String... values) {
		for (String value : values) {
			if (value != null && value.toLowerCase(Locale.ROOT).contains(text)) {
				return true;
			}
		}
		return false;
	}
}
