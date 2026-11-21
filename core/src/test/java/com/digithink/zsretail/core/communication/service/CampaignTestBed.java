package com.digithink.zsretail.core.communication.service;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationCampaignStatus;
import com.digithink.zsretail.core.communication.enumeration.CommunicationEventStatus;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationCampaign;
import com.digithink.zsretail.core.communication.model.CommunicationChannelState;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.model.CommunicationRule;
import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider;
import com.digithink.zsretail.core.communication.repository.CommunicationCampaignRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationChannelStateRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationEventRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationMessageRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationRuleRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationTemplateRepository;
import com.digithink.zsretail.core.communication.scheduler.CommunicationCampaignMaker;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.digithink.zsretail.core.repository.LoyaltyMemberRepository;

/**
 * 3.3.0, communication step 7: the module's tables in memory, each repository answering as its query does (the member
 * query as findCampaignCandidates, run on H2 in CampaignAudienceQueryTest), for the campaign tests. No Spring context.
 */
final class CampaignTestBed {

	LocalDateTime now = LocalDateTime.of(2026, 11, 21, 10, 0);
	String smsEnabled = "true";
	String emailEnabled = "false";
	String companyName = "Happyness";
	String licenceName = "Licence SARL";
	BiFunction<String, String, String> storeOf = (createdBy, card) -> "Magasin Centre";
	Set<String> aliasCards = Collections.emptySet();

	final Map<Long, CommunicationCampaign> campaignTable = new LinkedHashMap<>();
	final Map<Long, CommunicationMessage> messageTable = new LinkedHashMap<>();
	final Map<String, CommunicationTemplate> templateTable = new LinkedHashMap<>();
	final Map<Long, LoyaltyMember> memberTable = new LinkedHashMap<>();
	final Map<Long, CommunicationEvent> eventTable = new LinkedHashMap<>();
	final List<CommunicationRule> ruleTable = new ArrayList<>();
	/** Step 8a part 2: comm_channel_state by channel. */
	final Map<String, CommunicationChannelState> channelStateTable = new LinkedHashMap<>();
	/** The members' query, replaced by the H2 one in CampaignAudienceQueryTest. */
	LoyaltyMemberRepository members = stub(LoyaltyMemberRepository.class, this::member);

	// ─── Data ────────────────────────────────────────────────────────

	LoyaltyMember member(String card, String firstName, String phone, boolean consent) {
		LoyaltyMember member = new LoyaltyMember();
		member.setId((long) memberTable.size() + 1);
		member.setCardNumber(card);
		member.setFirstName(firstName);
		member.setLastName("Ben Ali");
		member.setPhone(phone);
		member.setActive(true);
		member.setMessageConsent(consent);
		member.setLoyaltyPoints(120);
		memberTable.put(member.getId(), member);
		return member;
	}

	CommunicationTemplate template(String code, String channel, String body, boolean active) {
		CommunicationTemplate template = new CommunicationTemplate();
		template.setId((long) templateTable.size() + 1);
		template.setCode(code);
		template.setName(code);
		template.setChannel(channel);
		template.setLanguageCode("fr");
		template.setBody(body);
		template.setActive(active);
		templateTable.put(code, template);
		return template;
	}

	CommunicationMessage automatic(String card, String phone) {
		CommunicationMessage message = new CommunicationMessage();
		message.setEventId(99L);
		message.setChannel(CommunicationTypes.SMS);
		message.setProvider("test");
		message.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		message.setRecipientRef(card);
		message.setAddress("+216" + phone);
		message.setBody("Merci");
		message.setDedupKey("99:TICKET_THANKS_SMS:" + card);
		return messages().save(message);
	}

	List<CommunicationMessage> messagesOf(long campaignId) {
		return messageTable.values().stream().filter(m -> Long.valueOf(campaignId).equals(m.getCampaignId()))
				.collect(Collectors.toList());
	}

	// ─── The beans ───────────────────────────────────────────────────

	/** Step 8a: more property lines (the provider of a channel, the test recipients...). */
	final Map<String, String> properties = new LinkedHashMap<>();
	/** Step 8a: the providers that exist besides test. */
	final List<CommunicationProvider> extraProviders = new ArrayList<>();

	CommunicationSettings settings() {
		// Step 8e: no default provider any more: the test one written, unless a test sets its own
		MockEnvironment env = new MockEnvironment().withProperty("communication.sms.enabled", smsEnabled)
				.withProperty("communication.email.enabled", emailEnabled).withProperty("communication.sms.provider", "test")
				.withProperty("communication.email.provider", "test").withProperty("communication.whatsapp.provider", "test");
		properties.forEach(env::setProperty);
		List<CommunicationProvider> all = new ArrayList<>(extraProviders);
		all.add(new com.digithink.zsretail.core.communication.provider.TestCommunicationProvider());
		return new CommunicationSettings(env, all.stream().map(CommunicationProvider::getName).collect(Collectors.toList()),
				all);
	}

	CommunicationCampaigns campaigns() {
		CommunicationMembers directory = new CommunicationMembers() {
			@Override
			public Found find(String cardNumber) {
				return Found.none();
			}

			@Override
			public boolean arriveLater() {
				return false;
			}

			@Override
			public Set<String> aliasCards() {
				return aliasCards;
			}
		};
		CampaignStoreNames stores = () -> storeOf;
		return new CommunicationCampaigns(campaignRepository(), messages(), templates(), members, settings(),
				() -> companyName, () -> licenceName, Optional.of(stores), Optional.of(directory), () -> now)
				.withChannelStates(channelStates());
	}

	CommunicationCampaignMaker maker() {
		return new CommunicationCampaignMaker(campaignRepository(), messages(), members, campaigns(), settings(),
				TransactionOperations.withoutTransaction(), () -> now);
	}

	/** Step 8a part 2: with the channels' pause and speed, as in the application. */
	CommunicationSender sender(CommunicationProvider... providers) {
		return new CommunicationSender(messages(), members, settings(), java.util.Arrays.asList(providers), () -> now)
				.withCampaigns(campaignRepository()).withChannelStates(channelStates()).withTemplateProblems(templateProblems());
	}

	/** Step 8c: a template refused by its provider (marked, its campaign stopped), on the bed's clock. */
	CommunicationTemplateProblems templateProblems() {
		return new CommunicationTemplateProblems(templates(), campaignRepository(), messages(), () -> now);
	}

	/** Step 8a part 2: each channel's pause and speed, on the bed's clock, a cycle every 60 s. */
	CommunicationChannelStates channelStates() {
		return new CommunicationChannelStates(channelStateRepository(), messages(), () -> now, 60);
	}

	CommunicationChannelStateRepository channelStateRepository() {
		return stub(CommunicationChannelStateRepository.class, (method, args) -> {
			switch (method) {
				case "findAll":
					return new ArrayList<>(channelStateTable.values());
				case "findByChannel":
					return Optional.ofNullable(channelStateTable.get(args[0]));
				case "save":
					CommunicationChannelState state = (CommunicationChannelState) args[0];
					if (state.getId() == null) {
						state.setId((long) channelStateTable.size() + 1);
					}
					channelStateTable.put(state.getChannel(), state);
					return state;
				default:
					return UNHANDLED;
			}
		});
	}

	CommunicationEventProcessor processor() {
		return new CommunicationEventProcessor(events(), rules(), templates(), messages(), members, settings(),
				TransactionOperations.withoutTransaction(), () -> now).withLicenceName(() -> licenceName);
	}

	// ─── The repositories ────────────────────────────────────────────

	CommunicationCampaignRepository campaignRepository() {
		return stub(CommunicationCampaignRepository.class, (method, args) -> {
			switch (method) {
				case "save":
					CommunicationCampaign campaign = (CommunicationCampaign) args[0];
					if (campaign.getId() == null) {
						campaign.setId((long) campaignTable.size() + 1);
					}
					campaignTable.put(campaign.getId(), campaign);
					return campaign;
				case "findById":
					return Optional.ofNullable(campaignTable.get(args[0]));
				case "findAllById":
					return campaignTable.values().stream().filter(c -> ((Collection<?>) args[0]).contains(c.getId()))
							.collect(Collectors.toList());
				case "findByStatusOrderById":
					return campaignTable.values().stream().filter(c -> c.getStatus() == args[0]).collect(Collectors.toList());
				case "findFirstByTemplateCodeAndLaunchedAtGreaterThanEqualOrderByIdDesc":
					return campaignTable.values().stream().filter(c -> c.getTemplateCode().equals(args[0])
							&& !c.getLaunchedAt().isBefore((LocalDateTime) args[1]))
							.max(Comparator.comparing(CommunicationCampaign::getId));
				case "findTop100ByOrderByIdDesc":
					return campaignTable.values().stream().sorted(Comparator.comparing(CommunicationCampaign::getId)
							.reversed()).collect(Collectors.toList());
				case "findAll":
					List<CommunicationCampaign> all = campaignTable.values().stream().sorted(Comparator.comparing(
							CommunicationCampaign::getId).reversed()).collect(Collectors.toList());
					return new PageImpl<>(all, (Pageable) args[0], all.size());
				default:
					return UNHANDLED;
			}
		});
	}

	CommunicationMessageRepository messages() {
		return stub(CommunicationMessageRepository.class, (method, args) -> {
			switch (method) {
				case "save":
					CommunicationMessage message = (CommunicationMessage) args[0];
					if (message.getId() == null) {
						if (messageTable.values().stream().anyMatch(m -> m.getDedupKey().equals(message.getDedupKey()))) {
							throw new IllegalStateException("uk_comm_message_dedup: " + message.getDedupKey());
						}
						message.setId((long) messageTable.size() + 1);
						message.setCreatedAt(now);
					}
					messageTable.put(message.getId(), message);
					return message;
				case "findByDedupKey":
					return messageTable.values().stream().filter(m -> m.getDedupKey().equals(args[0])).findFirst();
				case "findById": // step 8e: the resend's read
					return Optional.ofNullable(messageTable.get(args[0]));
				case "claim": { // step 8e: SENDING only if still PENDING
					CommunicationMessage claimed = messageTable.get(args[0]);
					if (claimed == null || claimed.getStatus() != args[1]) {
						return 0;
					}
					claimed.setStatus((CommunicationMessageStatus) args[2]);
					return 1;
				}
				case "findExistingDedupKeys":
					return messageTable.values().stream().map(CommunicationMessage::getDedupKey)
							.filter(((Collection<?>) args[0])::contains).collect(Collectors.toList());
				case "findDue":
					LocalDateTime at = (LocalDateTime) args[1];
					long afterId = (Long) args[2];
					int queue = (Integer) args[4];
					return messageTable.values().stream()
							.filter(m -> m.getStatus() == args[0] && (m.getNextAttemptAt() == null || !m.getNextAttemptAt().isAfter(at)))
							.filter(m -> (queue == CommunicationMessageRepository.AUTOMATIC) == (m.getCampaignId() == null))
							.filter(m -> m.getId() > afterId).limit(((Pageable) args[3]).getPageSize())
							.collect(Collectors.toList());
				case "findDueOutside": {
					LocalDateTime when = (LocalDateTime) args[1];
					long after = (Long) args[2];
					int which = (Integer) args[4];
					Collection<?> paused = (Collection<?>) args[5];
					return messageTable.values().stream()
							.filter(m -> m.getStatus() == args[0] && (m.getNextAttemptAt() == null || !m.getNextAttemptAt().isAfter(when)))
							.filter(m -> (which == CommunicationMessageRepository.AUTOMATIC) == (m.getCampaignId() == null))
							.filter(m -> !paused.contains(m.getChannel()))
							.filter(m -> m.getId() > after).limit(((Pageable) args[3]).getPageSize())
							.collect(Collectors.toList());
				}
				case "expireWaiting": {
					int expired = 0;
					for (CommunicationMessage m : messageTable.values()) {
						if (m.getChannel().equals(args[0]) && m.getStatus() == args[1] && m.getExpiresAt() != null
								&& !m.getExpiresAt().isAfter((LocalDateTime) args[4])) {
							m.setStatus((CommunicationMessageStatus) args[2]);
							m.setLastError((String) args[3]);
							expired++;
						}
					}
					return expired;
				}
				case "countByChannelAndStatus":
					return messageTable.values().stream().filter(m -> m.getChannel().equals(args[0])
							&& m.getStatus() == args[1]).count();
				case "countByCampaignAndStatus":
					Map<String, Object[]> groups = new LinkedHashMap<>();
					for (CommunicationMessage m : messageTable.values()) {
						if (((Collection<?>) args[0]).contains(m.getCampaignId())) {
							Object[] group = groups.computeIfAbsent(m.getCampaignId() + ":" + m.getStatus(),
									k -> new Object[] { m.getCampaignId(), m.getStatus(), 0L });
							group[2] = (Long) group[2] + 1;
						}
					}
					return new ArrayList<>(groups.values());
				case "countByCampaignIdAndStatus":
					return messageTable.values().stream().filter(m -> args[0].equals(m.getCampaignId())
							&& m.getStatus() == args[1]).count();
				case "markInterrupted": {
					int left = 0;
					for (CommunicationMessage m : messageTable.values()) {
						if (m.getStatus() == args[0]) {
							m.setStatus((CommunicationMessageStatus) args[1]);
							m.setLastError((String) args[2]);
							left++;
						}
					}
					return left;
				}
				case "findFirstByChannelAndAttemptsGreaterThanOrderByUpdatedAtDesc":
					return messageTable.values().stream().filter(m -> m.getChannel().equals(args[0])
							&& m.getAttempts() > (Integer) args[1]).reduce((a, b) -> b);
				case "skipWaiting":
					int changed = 0;
					for (CommunicationMessage m : messageTable.values()) {
						if (args[0].equals(m.getCampaignId()) && m.getStatus() == args[1]) {
							m.setStatus((CommunicationMessageStatus) args[2]);
							m.setLastError((String) args[3]);
							changed++;
						}
					}
					return changed;
				default:
					return UNHANDLED;
			}
		});
	}

	CommunicationTemplateRepository templates() {
		return stub(CommunicationTemplateRepository.class, (method, args) -> {
			switch (method) {
				case "findByCode":
					return Optional.ofNullable(templateTable.get(args[0]));
				case "findAll":
					return new ArrayList<>(templateTable.values());
				case "findByProviderProblemIsNotNull":
					return templateTable.values().stream().filter(t -> t.getProviderProblem() != null)
							.collect(Collectors.toList());
				case "save":
					CommunicationTemplate saved = (CommunicationTemplate) args[0];
					templateTable.put(saved.getCode(), saved);
					return saved;
				default:
					return UNHANDLED;
			}
		});
	}

	CommunicationEventRepository events() {
		return stub(CommunicationEventRepository.class, (method, args) -> {
			switch (method) {
				case "findToProcess":
					long afterId = (Long) args[3];
					return eventTable.values().stream().filter(e -> e.getStatus() == CommunicationEventStatus.PENDING)
							.filter(e -> e.getId() > afterId).map(CommunicationEvent::getId).collect(Collectors.toList());
				case "findById":
				case "findForProcessing": // step 8e: the locked read
					return Optional.ofNullable(eventTable.get(args[0]));
				default:
					return UNHANDLED;
			}
		});
	}

	CommunicationRuleRepository rules() {
		return stub(CommunicationRuleRepository.class, (method, args) -> {
			switch (method) {
				case "findByEventTypeAndActiveTrueOrderByCode":
					return ruleTable.stream().filter(r -> r.getEventType().equals(args[0]) && Boolean.TRUE.equals(r.getActive()))
							.collect(Collectors.toList());
				// step 8c: the Rules tab's calls
				case "findByCode":
					return ruleTable.stream().filter(r -> r.getCode().equals(args[0])).findFirst();
				case "findById":
					return ruleTable.stream().filter(r -> args[0].equals(r.getId())).findFirst();
				case "findAll":
					return new ArrayList<>(ruleTable);
				case "save":
					CommunicationRule rule = (CommunicationRule) args[0];
					if (rule.getId() == null) {
						rule.setId((long) ruleTable.size() + 1);
						ruleTable.add(rule);
					}
					return rule;
				default:
					return UNHANDLED;
			}
		});
	}

	/** Step 8c: the Communication page's service on the bed's tables (rules, templates). */
	CommunicationAdmin admin() {
		return new CommunicationAdmin(events(), messages(), rules(), templates(), members, settings(), Optional.empty(),
				() -> companyName, () -> now);
	}

	/** The members, as findCampaignCandidates and countActiveWithoutConsent read them; findByCardNumber. */
	private Object member(String method, Object[] args) {
		switch (method) {
			case "findCampaignCandidates": {
				RecordOrigin notOrigin = (RecordOrigin) args[0];
				long afterId = (Long) args[1];
				return memberTable.values().stream()
						.filter(m -> Boolean.TRUE.equals(m.getActive()) && Boolean.TRUE.equals(m.getMessageConsent()))
						.filter(m -> m.getOrigin() == null || m.getOrigin() != notOrigin).filter(m -> m.getId() > afterId)
						.sorted(Comparator.comparing(LoyaltyMember::getId)).limit(((Pageable) args[2]).getPageSize())
						.map(m -> new Object[] { m.getId(), m.getCardNumber(), m.getFirstName(), m.getLastName(),
								m.getPhone(), m.getEmail(), m.getLoyaltyPoints(), m.getCreatedBy() })
						.collect(Collectors.toList());
			}
			case "countActiveWithoutConsent":
				return memberTable.values().stream().filter(m -> Boolean.TRUE.equals(m.getActive())
						&& !Boolean.TRUE.equals(m.getMessageConsent())).count();
			case "findByCardNumber":
				return memberTable.values().stream().filter(m -> m.getCardNumber().equals(args[0])).findFirst();
			default:
				return UNHANDLED;
		}
	}

	// ─── Stubs ───────────────────────────────────────────────────────

	interface Handler {
		Object handle(String method, Object[] args);
	}

	static final Object UNHANDLED = new Object();

	@SuppressWarnings("unchecked")
	static <T> T stub(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				case "toString": return type.getSimpleName() + "Stub";
				default: break;
			}
			Object result = handler.handle(method.getName(), args == null ? new Object[0] : args);
			if (result == UNHANDLED) {
				throw new UnsupportedOperationException("Unexpected call: " + type.getSimpleName() + "." + method.getName());
			}
			return result;
		});
	}

	static boolean running(CommunicationCampaign campaign) {
		return campaign.getStatus() == CommunicationCampaignStatus.RUNNING;
	}
}
