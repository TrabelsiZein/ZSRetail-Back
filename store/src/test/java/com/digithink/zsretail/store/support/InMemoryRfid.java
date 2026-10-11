package com.digithink.zsretail.store.support;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.rfid.model.PosMachine;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.rfid.repository.PosMachineRepository;
import com.digithink.zsretail.store.rfid.repository.RfidReaderRepository;
import com.digithink.zsretail.store.rfid.service.PosMachineService;
import com.digithink.zsretail.store.rfid.service.RfidReaderService;

/**
 * Test support, RFID step 3: the two RFID tables of one store in memory (the repositories the services call) and the
 * real services over them, the logged user "tester". No Spring context, no transaction.
 */
public final class InMemoryRfid {

	public final Map<Long, RfidReader> readers = new LinkedHashMap<>();
	public final Map<Long, PosMachine> machines = new LinkedHashMap<>();
	private long nextId = 1;

	public final RfidReaderRepository readerRepository = proxy(RfidReaderRepository.class, (method, args) -> {
		switch (method) {
		case "save":
			return save(readers, (RfidReader) args[0]);
		case "findById":
			return Optional.ofNullable(readers.get(args[0]));
		case "findAll":
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
