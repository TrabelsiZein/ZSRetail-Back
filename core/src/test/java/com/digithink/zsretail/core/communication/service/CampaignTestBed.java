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
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.model.CommunicationRule;
import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider;
import com.digithink.zsretail.core.communication.repository.CommunicationCampaignRepository;
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

	CommunicationSettings settings() {
		return new CommunicationSettings(new MockEnvironment().withProperty("communication.sms.enabled", smsEnabled)
				.withProperty("communication.email.enabled", emailEnabled), Collections.singletonList("test"));
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
				() -> companyName, () -> licenceName, Optional.of(stores), Optional.of(directory), () -> now);
	}

	CommunicationCampaignMaker maker() {
		return new CommunicationCampaignMaker(campaignRepository(), messages(), members, campaigns(), settings(),
				TransactionOperations.withoutTransaction(), () -> now);
	}

	CommunicationSender sender(CommunicationProvider... providers) {
		return new CommunicationSender(messages(), members, settings(), java.util.Arrays.asList(providers), () -> now)
				.withCampaigns(campaignRepository());
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
					return Optional.ofNullable(eventTable.get(args[0]));
				default:
					return UNHANDLED;
			}
		});
	}

	CommunicationRuleRepository rules() {
		return stub(CommunicationRuleRepository.class, (method, args) ->
				"findByEventTypeAndActiveTrueOrderByCode".equals(method) ? ruleTable.stream()
						.filter(r -> r.getEventType().equals(args[0]) && Boolean.TRUE.equals(r.getActive()))
						.collect(Collectors.toList()) : UNHANDLED);
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
