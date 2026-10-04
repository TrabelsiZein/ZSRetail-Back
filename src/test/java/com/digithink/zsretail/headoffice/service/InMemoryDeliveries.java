package com.digithink.zsretail.headoffice.service;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.enumeration.DeliveryStatus;
import com.digithink.zsretail.headoffice.model.HoDelivery;
import com.digithink.zsretail.headoffice.model.HoDeliveryLine;
import com.digithink.zsretail.headoffice.model.HoNumberSequence;
import com.digithink.zsretail.headoffice.repository.HoDeliveryRepository;
import com.digithink.zsretail.headoffice.repository.HoNumberSequenceRepository;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.InMemoryStock;

/**
 * Test support (step 7A): ho_delivery (with its lines, saved with the header as the cascade does) and
 * ho_number_sequence in memory, applying the rules of the JPQL queries, and a real {@link HoDeliveryService} over them
 * and the head office's {@link InMemoryStock}.
 */
public final class InMemoryDeliveries {

	public final Map<Long, HoDelivery> deliveries = new LinkedHashMap<>();
	public final Map<String, Long> sequences = new LinkedHashMap<>();
	private final InMemoryCatalogue ho;

	public InMemoryDeliveries(InMemoryCatalogue ho) {
		this.ho = ho;
	}

	public HoDeliveryService service(InMemoryStock stock, Supplier<CopiesDownFeed> feed,
			Supplier<java.time.LocalDateTime> clock) {
		return new HoDeliveryService(deliveryRepository(), ho.storeRepository(), stock.itemRepository(),
				sequenceRepository(), stock.stockService(), stock.stockMovementService(), feed,
				TransactionOperations.withoutTransaction(), clock);
	}

	public HoDelivery byNumber(String number) {
		return deliveries.values().stream().filter(d -> Objects.equals(d.getNumber(), number)).findFirst().orElse(null);
	}

	public HoDeliveryRepository deliveryRepository() {
		return proxy(HoDeliveryRepository.class, (method, args) -> {
			switch (method) {
				case "findById":
				case "findForUpdate":
					return Optional.ofNullable(deliveries.get(args[0]));
				case "findForUpdateByStoreAndNumber":
					return deliveries.values().stream()
							.filter(d -> d.getStoreId().equals(args[0]) && Objects.equals(d.getNumber(), args[1]))
							.findFirst();
				case "save": {
					HoDelivery delivery = (HoDelivery) args[0];
					if (delivery.getId() == null) {
						delivery.setId(ho.nextId());
					}
					for (HoDeliveryLine line : delivery.getLines()) {
						if (line.getId() == null) {
							line.setId(ho.nextId());
						}
					}
					deliveries.put(delivery.getId(), delivery);
					return delivery;
				}
				case "delete":
					deliveries.remove(((HoDelivery) args[0]).getId());
					return null;
				case "findSent": {
					Collection<?> numbers = (Collection<?>) args[1];
					return deliveries.values().stream().filter(d -> d.getStoreId().equals(args[0])
							&& numbers.contains(d.getNumber()) && d.getStatus() != args[2]).collect(Collectors.toList());
				}
				case "findToInvoice":
					return deliveries.values().stream().filter(d -> d.getStoreId().equals(args[0]) && d.getStatus() == args[1]
							&& d.getInvoiceId() == null).sorted(Comparator.comparing(HoDelivery::getId))
							.collect(Collectors.toList());
				case "findSentTargets":
					return deliveries.values().stream().filter(d -> d.getNumber() != null && d.getStatus() != args[0])
							.map(d -> new Object[] { d.getNumber(), d.getStoreId() }).collect(Collectors.toList());
				case "findPage": {
					long storeId = (Long) args[0];
					boolean anyStatus = (Long) args[1] == 1L;
					String search = (String) args[3];
					LocalDate from = (LocalDate) args[4];
					LocalDate to = (LocalDate) args[5];
					boolean difference = (Long) args[6] == 1L;
					List<HoDelivery> rows = deliveries.values().stream()
							.filter(d -> storeId == 0 || d.getStoreId() == storeId)
							.filter(d -> anyStatus || d.getStatus() == args[2])
							.filter(d -> search == null || like(d.getNumber(), search) || like(d.getNote(), search))
							.filter(d -> !d.getDocumentDate().isBefore(from) && !d.getDocumentDate().isAfter(to))
							.filter(d -> !difference || d.getLines().stream().anyMatch(l -> l.getQuantityReceived() != null
									&& !l.getQuantityReceived().equals(l.getQuantitySent())))
							.sorted(Comparator.comparing(HoDelivery::getId).reversed()).collect(Collectors.toList());
					Pageable page = (Pageable) args[7];
					int start = (int) Math.min(rows.size(), page.getOffset());
					int end = Math.min(rows.size(), start + page.getPageSize());
					return new PageImpl<>(new ArrayList<>(rows.subList(start, end)), page, rows.size());
				}
				default:
					return UNHANDLED;
			}
		});
	}

	public HoNumberSequenceRepository sequenceRepository() {
		return proxy(HoNumberSequenceRepository.class, (method, args) -> {
			switch (method) {
				case "increment":
					if (!sequences.containsKey(args[0])) {
						return 0;
					}
					sequences.merge((String) args[0], 1L, Long::sum);
					return 1;
				case "lastValue": {
					Long value = sequences.get(args[0]);
					return value == null ? new ArrayList<>() : new ArrayList<>(Collections.singletonList(value));
				}
				case "save": {
					HoNumberSequence sequence = (HoNumberSequence) args[0];
					sequences.put(sequence.getCode(), sequence.getLastValue());
					return sequence;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	/** lower(column) like '%text%' (the pattern is already lower case, with % around). */
	private static boolean like(String column, String pattern) {
		return column != null && column.toLowerCase().contains(pattern.substring(1, pattern.length() - 1));
	}

	public static DeliveryStatus status(String name) {
		return DeliveryStatus.valueOf(name);
	}
}
