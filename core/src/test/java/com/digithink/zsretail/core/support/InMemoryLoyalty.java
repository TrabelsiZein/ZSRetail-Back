package com.digithink.zsretail.core.support;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.model.LoyaltyProgram;
import com.digithink.zsretail.core.model.LoyaltyTransaction;
import com.digithink.zsretail.core.model.MemberFunction;
import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.digithink.zsretail.core.repository.CustomerRepository;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.core.repository.LoyaltyProgramRepository;
import com.digithink.zsretail.core.repository.LoyaltyTransactionRepository;
import com.digithink.zsretail.core.repository.MemberFunctionRepository;
import com.digithink.zsretail.core.service.LoyaltyNetworkHooks;
import com.digithink.zsretail.core.service.LoyaltyService;

/**
 * Test support (step 4): the loyalty tables of one installation in memory (members, programs, transactions, member
 * functions, customers), with repository stubs that apply the rules of their queries, and a real
 * {@link LoyaltyService} over them. The head office's alias and movement tables: headoffice.support.InMemoryHoLoyalty
 * (code cleanup step 6.7, so that core's tests need no head office class). One instance per installation: a head office and a store
 * are two instances. Plain Java, no Spring, no mocking library.
 */
public final class InMemoryLoyalty {

	public final Map<Long, LoyaltyMember> members = new LinkedHashMap<>();
	public final Map<Long, LoyaltyProgram> programs = new LinkedHashMap<>();
	public final List<LoyaltyTransaction> transactions = new ArrayList<>();
	public final Map<Long, MemberFunction> functions = new LinkedHashMap<>();
	public final Map<Long, Customer> customers = new LinkedHashMap<>();

	/** Ids are given by the in-memory "database"; each installation numbers its own rows. */
	private long nextId;

	/** Number of member saves, to prove that nothing was written. */
	public int memberSaves;

	public InMemoryLoyalty(long firstId) {
		this.nextId = firstId;
	}

	public long nextId() {
		return nextId++;
	}

	// ─── Rows ────────────────────────────────────────────────────

	public MemberFunction function(String code, String name) {
		MemberFunction function = new MemberFunction();
		function.setId(nextId());
		function.setCode(code);
		function.setName(name);
		functions.put(function.getId(), function);
		return function;
	}

	public LoyaltyMember member(String card, String first, String last, String phone, boolean active,
			RecordOrigin origin) {
		LoyaltyMember member = new LoyaltyMember();
		member.setId(nextId());
		member.setCardNumber(card);
		member.setFirstName(first);
		member.setLastName(last);
		member.setPhone(phone);
		member.setActive(active);
		member.setOrigin(origin);
		members.put(member.getId(), member);
		return member;
	}

	public LoyaltyProgram program(String code, double pointsPerDinar, boolean active, RecordOrigin origin) {
		LoyaltyProgram program = new LoyaltyProgram();
		program.setId(nextId());
		program.setProgramCode(code);
		program.setName("Program " + code);
		program.setStartDate(LocalDate.now().minusDays(10));
		program.setPointsPerDinar(pointsPerDinar);
		program.setPointValueMillimes(10);
		program.setMinimumRedemptionPoints(100);
		program.setMaximumRedemptionPercentage(30.0);
		program.setEarningTiers(new ArrayList<>());
		program.setActive(active);
		program.setOrigin(origin);
		programs.put(program.getId(), program);
		return program;
	}

	public Optional<LoyaltyMember> card(String cardNumber) {
		return members.values().stream().filter(m -> cardNumber.equals(m.getCardNumber())).findFirst();
	}

	// ─── Repositories ────────────────────────────────────────────

	public LoyaltyMemberRepository memberRepository() {
		return proxy(LoyaltyMemberRepository.class, (method, a) -> {
			switch (method) {
				case "findById":
					return Optional.ofNullable(members.get(a[0]));
				case "findByCardNumber":
					return card((String) a[0]);
				case "findByCardNumberIn": {
					Collection<?> cards = (Collection<?>) a[0];
					return members.values().stream().filter(m -> cards.contains(m.getCardNumber()))
							.collect(Collectors.toList());
				}
				case "findByPhone":
					return members.values().stream().filter(m -> a[0].equals(m.getPhone())).collect(Collectors.toList());
				case "findCardNumbersLike": {
					Pattern pattern = like((String) a[0]);
					return members.values().stream().map(LoyaltyMember::getCardNumber)
							.filter(c -> c != null && pattern.matcher(c).matches()).collect(Collectors.toList());
				}
				case "findActiveNotFrom":
					return members.values().stream()
							.filter(m -> Boolean.TRUE.equals(m.getActive()) && m.getOrigin() != a[0])
							.collect(Collectors.toList());
				case "findAll":
					return new ArrayList<>(members.values());
				case "searchMembers": {
					String q = ((String) a[0]).toLowerCase();
					return members.values().stream().filter(m -> m.getCardNumber().toLowerCase().contains(q)
							|| m.getFirstName().toLowerCase().contains(q) || m.getLastName().toLowerCase().contains(q)
							|| m.getPhone() != null && m.getPhone().contains(q)).collect(Collectors.toList());
				}
				case "findMaxCardSequence":
					return members.values().stream().map(LoyaltyMember::getCardNumber)
							.filter(c -> c.matches("LYL-\\d+")).map(c -> Integer.parseInt(c.substring(4)))
							.max(Comparator.naturalOrder()).orElse(0);
				case "save": {
					LoyaltyMember member = (LoyaltyMember) a[0];
					assign(member);
					members.put(member.getId(), member);
					memberSaves++;
					return member;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	public LoyaltyProgramRepository programRepository() {
		return proxy(LoyaltyProgramRepository.class, (method, a) -> {
			switch (method) {
				case "findById":
					return Optional.ofNullable(programs.get(a[0]));
				case "findByProgramCode":
					return programs.values().stream().filter(p -> a[0].equals(p.getProgramCode())).findFirst();
				case "findByActiveTrue":
					return programs.values().stream().filter(p -> Boolean.TRUE.equals(p.getActive()))
							.collect(Collectors.toList());
				case "findActiveNotFrom":
					return programs.values().stream()
							.filter(p -> Boolean.TRUE.equals(p.getActive()) && p.getOrigin() != a[0])
							.collect(Collectors.toList());
				case "findCurrentActivePrograms": {
					LocalDate today = (LocalDate) a[0];
					return programs.values().stream()
							.filter(p -> Boolean.TRUE.equals(p.getActive()) && !p.getStartDate().isAfter(today)
									&& (p.getEndDate() == null || !p.getEndDate().isBefore(today)))
							.sorted(Comparator.comparing(LoyaltyProgram::getStartDate).reversed())
							.collect(Collectors.toList());
				}
				case "findAllByOrderByStartDateDesc":
					return programs.values().stream()
							.sorted(Comparator.comparing(LoyaltyProgram::getStartDate).reversed())
							.collect(Collectors.toList());
				case "save": {
					LoyaltyProgram program = (LoyaltyProgram) a[0];
					assign(program);
					programs.put(program.getId(), program);
					return program;
				}
				case "delete":
					programs.remove(((LoyaltyProgram) a[0]).getId());
					return null;
				default:
					return UNHANDLED;
			}
		});
	}

	public LoyaltyTransactionRepository transactionRepository() {
		return proxy(LoyaltyTransactionRepository.class, (method, a) -> {
			switch (method) {
				case "save": {
					LoyaltyTransaction tx = (LoyaltyTransaction) a[0];
					if (tx.getId() == null) {
						tx.setId(nextId());
						transactions.add(tx);
					}
					return tx;
				}
				case "countByLoyaltyProgram":
					return transactions.stream().filter(t -> t.getLoyaltyProgram() == a[0]).count();
				case "findByLoyaltyMemberAndSalesHeaderAndType":
					return transactions.stream().filter(t -> t.getLoyaltyMember() == a[0] && t.getSalesHeader() == a[1]
							&& t.getType() == a[2]).collect(Collectors.toList());
				default:
					return UNHANDLED;
			}
		});
	}

	public MemberFunctionRepository functionRepository() {
		return proxy(MemberFunctionRepository.class, (method, a) -> {
			switch (method) {
				case "findById":
					return Optional.ofNullable(functions.get(a[0]));
				case "findByCode":
					return functions.values().stream().filter(f -> a[0].equals(f.getCode())).findFirst();
				case "countActive": // 2.2.2: active is null or true
					return functions.values().stream().filter(f -> f.getActive() == null || f.getActive()).count();
				case "save": {
					MemberFunction function = (MemberFunction) a[0];
					assign(function);
					functions.put(function.getId(), function);
					return function;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	public CustomerRepository customerRepository() {
		return proxy(CustomerRepository.class, (method, a) -> {
			switch (method) {
				case "findById":
					return Optional.ofNullable(customers.get(a[0]));
				case "findByCustomerCode":
					return customers.values().stream().filter(c -> a[0].equals(c.getCustomerCode())).findFirst();
				default:
					return UNHANDLED;
			}
		});
	}

	/** A real LoyaltyService over these tables, LOYALTY_ENABLED true, with the network hooks (null: loyalty LOCAL). */
	public LoyaltyService loyaltyService(LoyaltyNetworkHooks hooks) {
		LoyaltyService service = new LoyaltyService();
		GeneralSetup enabled = new GeneralSetup();
		enabled.setCode("LOYALTY_ENABLED");
		enabled.setValeur("true");
		inject(service, "loyaltyMemberRepository", memberRepository());
		inject(service, "loyaltyProgramRepository", programRepository());
		inject(service, "loyaltyTransactionRepository", transactionRepository());
		inject(service, "memberFunctionRepository", functionRepository());
		inject(service, "customerRepository", customerRepository());
		inject(service, "generalSetupRepository", proxy(GeneralSetupRepository.class,
				(method, a) -> "findByCode".equals(method) ? Optional.of(enabled) : UNHANDLED));
		inject(service, "networkHooks", hooks);
		return service;
	}

	// ─── Helpers ─────────────────────────────────────────────────

	private void assign(_BaseEntity entity) {
		if (entity.getId() == null) {
			entity.setId(nextId());
		}
	}

	/** A SQL LIKE pattern with '!' as the escape character, as a regex. */
	public static Pattern like(String pattern) {
		StringBuilder regex = new StringBuilder();
		for (int i = 0; i < pattern.length(); i++) {
			char c = pattern.charAt(i);
			if (c == '!' && i + 1 < pattern.length()) {
				regex.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
			} else if (c == '%') {
				regex.append(".*");
			} else if (c == '_') {
				regex.append('.');
			} else {
				regex.append(Pattern.quote(String.valueOf(c)));
			}
		}
		return Pattern.compile(regex.toString());
	}

	public static void inject(Object target, String name, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	public interface Handler {
		Object handle(String method, Object[] args);
	}

	public static final Object UNHANDLED = new Object();

	@SuppressWarnings("unchecked")
	public static <T> T proxy(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (p, method, args) -> {
			switch (method.getName()) {
				case "toString":
					return type.getSimpleName() + " (in memory)";
				case "hashCode":
					return System.identityHashCode(p);
				case "equals":
					return p == args[0];
				default:
					Object result = handler.handle(method.getName(), args == null ? new Object[0] : args);
					if (result == UNHANDLED) {
						throw new UnsupportedOperationException(
								"Unexpected call: " + type.getSimpleName() + "." + method.getName());
					}
					return result;
			}
		});
	}
}
