package com.digithink.zsretail.support;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.holink.enumeration.ReceivedDeliveryStatus;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.model.ReceivedDelivery;
import com.digithink.zsretail.holink.model.ReceivedDeliveryLine;
import com.digithink.zsretail.holink.repository.ReceivedDeliveryRepository;

/**
 * Test support (step 7A): hol_delivery of one store in memory (its lines saved with it, as the cascade does), applying
 * the rules of the JPQL queries of {@link ReceivedDeliveryRepository}.
 */
public final class InMemoryReceivedDeliveries {

	public final Map<Long, ReceivedDelivery> deliveries = new LinkedHashMap<>();
	private long nextId;

	public InMemoryReceivedDeliveries(long firstId) {
		this.nextId = firstId;
	}

	public ReceivedDelivery byNumber(String number) {
		return deliveries.values().stream().filter(d -> d.getNumber().equals(number)).findFirst().orElse(null);
	}

	public ReceivedDeliveryRepository repository() {
		return proxy(ReceivedDeliveryRepository.class, (method, args) -> {
			switch (method) {
				case "findById":
				case "findForUpdate":
					return Optional.ofNullable(deliveries.get(args[0]));
				case "findByNumber":
					return deliveries.values().stream().filter(d -> d.getNumber().equals(args[0])).findFirst();
				case "save": {
					ReceivedDelivery delivery = (ReceivedDelivery) args[0];
					if (delivery.getId() == null) {
						if (deliveries.values().stream().anyMatch(d -> d.getNumber().equals(delivery.getNumber()))) {
							throw new IllegalStateException("uk_hol_delivery_number");
						}
						delivery.setId(nextId++);
					}
					for (ReceivedDeliveryLine line : delivery.getLines()) {
						if (line.getId() == null) {
							line.setId(nextId++);
						}
					}
					deliveries.put(delivery.getId(), delivery);
					return delivery;
				}
				case "findPage": {
					boolean any = (Long) args[0] == 1L;
					List<ReceivedDelivery> rows = deliveries.values().stream()
							.filter(d -> any || d.getStatus() == args[1])
							.sorted(Comparator.comparing(ReceivedDelivery::getId).reversed()).collect(Collectors.toList());
					Pageable page = (Pageable) args[2];
					int start = (int) Math.min(rows.size(), page.getOffset());
					int end = Math.min(rows.size(), start + page.getPageSize());
					return new PageImpl<>(new ArrayList<>(rows.subList(start, end)), page, rows.size());
				}
				case "findPushQueue": {
					Collection<?> statuses = (Collection<?>) args[0];
					return deliveries.values().stream().filter(d -> statuses.contains(d.getPushStatus()))
							.sorted(Comparator.comparing(ReceivedDelivery::getAttempts)
									.thenComparing(ReceivedDelivery::getId))
							.limit(((Pageable) args[1]).getPageSize()).collect(Collectors.toList());
				}
				case "countByPushStatus": {
					Map<SalesCopyStatus, Long> counts = new EnumMap<>(SalesCopyStatus.class);
					deliveries.values().stream().filter(d -> d.getPushStatus() != null)
							.forEach(d -> counts.merge(d.getPushStatus(), 1L, Long::sum));
					return counts.entrySet().stream().map(e -> new Object[] { e.getKey(), e.getValue() })
							.collect(Collectors.toList());
				}
				case "countByStatus": {
					Map<ReceivedDeliveryStatus, Long> counts = new EnumMap<>(ReceivedDeliveryStatus.class);
					deliveries.values().forEach(d -> counts.merge(d.getStatus(), 1L, Long::sum));
					return counts.entrySet().stream().map(e -> new Object[] { e.getKey(), e.getValue() })
							.collect(Collectors.toList());
				}
				case "findIdsWithMissingItem": // an OTHER line (args[1]) never waits for an item
					return deliveries.values().stream().filter(d -> d.getStatus() == args[0] && d.getLines().stream()
							.anyMatch(l -> l.getItemId() == null && l.getLineType() != args[1]))
							.map(ReceivedDelivery::getId).collect(Collectors.toList());
				case "findIdsWithStock":
					return deliveries.values().stream()
							.filter(d -> d.getLines().stream().anyMatch(l -> Objects.equals(l.getStockApplied(), args[0])))
							.map(ReceivedDelivery::getId).collect(Collectors.toList());
				case "countLinesWithStock":
					return deliveries.values().stream().flatMap(d -> d.getLines().stream())
							.filter(l -> Objects.equals(l.getStockApplied(), args[0])).count();
				default:
					return UNHANDLED;
			}
		});
	}
}
