package com.digithink.zsretail.store.support;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.support.InMemoryItems;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.rfid.model.PosMachine;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.rfid.model.RfidSaleTag;
import com.digithink.zsretail.store.rfid.model.RfidTag;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;
import com.digithink.zsretail.store.rfid.repository.PosMachineRepository;
import com.digithink.zsretail.store.rfid.repository.RfidReaderRepository;
import com.digithink.zsretail.store.rfid.repository.RfidSaleTagRepository;
import com.digithink.zsretail.store.rfid.repository.RfidTagRepository;
import com.digithink.zsretail.store.rfid.service.PosMachineService;
import com.digithink.zsretail.store.rfid.service.RfidReaderService;
import com.digithink.zsretail.store.rfid.service.RfidTagService;

/**
 * Test support, RFID step 3: the RFID tables of one store in memory (the repositories the services call) and the
 * real services over them, the logged user "tester". No Spring context, no transaction. RFID tags step: the tags table
 * and the store's items (InMemoryItems, ids from 1000).
 */
public final class InMemoryRfid {

	public final Map<Long, RfidReader> readers = new LinkedHashMap<>();
	public final Map<Long, PosMachine> machines = new LinkedHashMap<>();
	public final Map<Long, RfidTag> tags = new LinkedHashMap<>();
	/** RFID POS step: the tags of the tickets' lines. */
	public final Map<Long, RfidSaleTag> saleTags = new LinkedHashMap<>();
	public final InMemoryItems items = new InMemoryItems(1000);
	public LocalDateTime now = LocalDateTime.of(2026, 10, 12, 10, 0);
	private long nextId = 1;

	public final RfidReaderRepository readerRepository = proxy(RfidReaderRepository.class, (method, args) -> {
		switch (method) {
		case "save":
			return save(readers, (RfidReader) args[0]);
		case "findById":
			return Optional.ofNullable(readers.get(args[0]));
		case "findAll":
		case "findAllByOrderByUpdatedAtDesc":
			return new ArrayList<>(readers.values());
		case "deleteById":
			readers.remove(args[0]);
			return null;
		case "findFirstByNameIgnoreCase":
			return readers.values().stream().filter(r -> r.getName().equalsIgnoreCase((String) args[0])).findFirst();
		case "findFirstByIpAddressAndPort":
			return readers.values().stream()
					.filter(r -> r.getIpAddress().equals(args[0]) && Objects.equals(r.getPort(), args[1])).findFirst();
		default:
			throw new UnsupportedOperationException(method);
		}
	});

	public final PosMachineRepository machineRepository = proxy(PosMachineRepository.class, (method, args) -> {
		switch (method) {
		case "save":
			return save(machines, (PosMachine) args[0]);
		case "findById":
			return Optional.ofNullable(machines.get(args[0]));
		case "findAll":
		case "findAllByOrderByUpdatedAtDesc":
			return new ArrayList<>(machines.values());
		case "deleteById":
			machines.remove(args[0]);
			return null;
		case "findFirstByNameIgnoreCase":
			return machines.values().stream().filter(m -> m.getName().equalsIgnoreCase((String) args[0])).findFirst();
		case "findFirstByIpAddress":
			return machines.values().stream().filter(m -> m.getIpAddress().equals(args[0])).findFirst();
		case "findByReader_Id":
			return machines.values().stream()
					.filter(m -> m.getReader() != null && m.getReader().getId().equals(args[0]))
					.collect(Collectors.toList());
		default:
			throw new UnsupportedOperationException(method);
		}
	});

	public final RfidTagRepository tagRepository = proxy(RfidTagRepository.class, (method, args) -> {
		switch (method) {
		case "save":
			return save(tags, (RfidTag) args[0]);
		case "findById":
			return Optional.ofNullable(tags.get(args[0]));
		case "deleteById":
			tags.remove(args[0]);
			return null;
		case "findByEpc":
			return tags.values().stream().filter(t -> t.getEpc().equals(args[0])).findFirst();
		case "findByEpcIn":
			return tags.values().stream().filter(t -> ((Collection<?>) args[0]).contains(t.getEpc()))
					.collect(Collectors.toList());
		case "countByItem_Id":
			return tags.values().stream().filter(t -> t.getItem().getId().equals(args[0])).count();
		case "search": {
			// (itemId, status, epcPattern, page); the pattern ready-made ('%E200%'), as the query takes it (no concat)
			String pattern = (String) args[2];
			if (pattern != null && (pattern.length() < 3 || !pattern.startsWith("%") || !pattern.endsWith("%"))) {
				throw new IllegalArgumentException("not a contains pattern: " + pattern);
			}
			String part = pattern == null ? "" : pattern.substring(1, pattern.length() - 1);
			Pageable page = (Pageable) args[3];
			List<RfidTag> found = tags.values().stream()
					.filter(t -> args[0] == null || t.getItem().getId().equals(args[0]))
					.filter(t -> args[1] == null || t.getStatus() == (RfidTagStatus) args[1])
					.filter(t -> t.getEpc().contains(part))
					.sorted(Comparator.comparing(RfidTag::getId).reversed()).collect(Collectors.toList());
			int from = (int) Math.min(page.getOffset(), found.size());
			int to = Math.min(from + page.getPageSize(), found.size());
			return new PageImpl<>(found.subList(from, to), page, found.size());
		}
		default:
			throw new UnsupportedOperationException(method);
		}
	});

	public final RfidSaleTagRepository saleTagRepository = proxy(RfidSaleTagRepository.class, (method, args) -> {
		switch (method) {
		case "save":
			return save(saleTags, (RfidSaleTag) args[0]);
		case "delete":
			saleTags.remove(((RfidSaleTag) args[0]).getId());
			return null;
		case "deleteAll":
			for (Object row : (Iterable<?>) args[0]) {
				saleTags.remove(((RfidSaleTag) row).getId());
			}
			return null;
		case "findBySalesHeader_Id":
			return saleTags.values().stream().filter(r -> r.getSalesHeader().getId().equals(args[0]))
					.collect(Collectors.toList());
		case "findBySalesLine_IdAndReturnLineIsNull":
			return saleTags.values().stream()
					.filter(r -> r.getSalesLine() != null && r.getSalesLine().getId().equals(args[0]) && r.getReturnLine() == null)
					.collect(Collectors.toList());
		case "heldBy":
			return saleTags.values().stream()
					.filter(r -> ((Collection<?>) args[0]).contains(r.getEpc()) && r.getSalesHeader().getStatus() == args[1]
							&& (args[2] == null || !r.getSalesHeader().getId().equals(args[2])))
					.map(RfidSaleTag::getEpc).collect(Collectors.toList());
		default:
			throw new UnsupportedOperationException(method);
		}
	});

	public final RfidTagService tagService = new RfidTagService(tagRepository, items.itemRepository(), () -> "tester",
			() -> now);

	public final RfidReaderService readerService = user(new RfidReaderService(readerRepository, machineRepository));
	public final PosMachineService machineService = user(new PosMachineService(machineRepository, readerRepository));

	/** A reader saved through the service: the given name, address and port, the defaults for the rest. */
	public RfidReader reader(String name, String ipAddress, int port) {
		RfidReader reader = new RfidReader();
		reader.setName(name);
		reader.setIpAddress(ipAddress);
		reader.setPort(port);
		try {
			return readerService.save(reader);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** A tag in the table: this EPC (as kept) on this item, with this status. */
	public RfidTag tag(String epc, com.digithink.zsretail.core.model.Item item, RfidTagStatus status) {
		RfidTag tag = new RfidTag();
		tag.setEpc(epc);
		tag.setItem(item);
		tag.setStatus(status);
		return save(tags, tag);
	}

	/** A machine saved through the service, with this reader (null: none). */
	public PosMachine machine(String name, String ipAddress, RfidReader reader) {
		PosMachine machine = new PosMachine();
		machine.setName(name);
		machine.setIpAddress(ipAddress);
		machine.setReader(reader);
		try {
			return machineService.save(machine);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private <T extends com.digithink.zsretail.core.model._BaseEntity> T save(Map<Long, T> table, T row) {
		if (row.getId() == null) {
			row.setId(nextId++);
		}
		table.put(row.getId(), row);
		return row;
	}

	/** The services' logged user: "tester". */
	private static <S extends _BaseService<?, ?>> S user(S service) {
		try {
			Field field = _BaseService.class.getDeclaredField("currentUserProvider");
			field.setAccessible(true);
			field.set(service, new CurrentUserProvider() {
				@Override
				public String getCurrentUserName() {
					return "tester";
				}
			});
			return service;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static <R> R proxy(Class<R> type, BiFunction<String, Object[], Object> answer) {
		return (R) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (self, method, args) -> {
			switch (method.getName()) {
			case "toString":
				return "in-memory " + type.getSimpleName();
			case "hashCode":
				return System.identityHashCode(self);
			case "equals":
				return self == args[0];
			default:
				return answer.apply(method.getName(), args == null ? new Object[0] : args);
			}
		});
	}
}
