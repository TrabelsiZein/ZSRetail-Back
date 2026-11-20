package com.digithink.zsretail.support;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.model.CommunicationRule;
import com.digithink.zsretail.core.communication.model.CommunicationState;
import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.communication.repository.CommunicationEventRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationMessageRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationRuleRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationStateRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationTemplateRepository;

/**
 * 3.3.0, communication step 6: the communication tables of one installation in memory (comm_event, comm_message,
 * comm_rule, comm_template, comm_state), read with the rules of the repositories' queries. One per side of a round trip.
 */
public class InMemoryCommunication {

	public final Map<Long, CommunicationEvent> events = new LinkedHashMap<>();
	public final Map<Long, CommunicationMessage> messages = new LinkedHashMap<>();
	public final List<CommunicationRule> rules = new ArrayList<>();
	public final List<CommunicationTemplate> templates = new ArrayList<>();
	public final Map<String, CommunicationState> states = new LinkedHashMap<>();
	private long ids;

	private long nextId() {
		return ++ids;
	}

	/** An event as a finder writes it, PENDING. */
	public CommunicationEvent event(String type, String card, LocalDateTime occurredAt, String store, Map<String, Object> payload) {
		CommunicationEvent event = new CommunicationEvent();
		event.setEventType(type);
		event.setSourceType(CommunicationTypes.TICKET_VALIDATED.equals(type) ? CommunicationTypes.SALES_HEADER
				: CommunicationTypes.LOYALTY_MEMBER);
		event.setSourceId(nextId());
		event.setDedupKey(CommunicationTypes.TICKET_VALIDATED.equals(type) ? type + ":" + store + ":" + event.getSourceId()
				: type + ":" + card);
		event.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		event.setRecipientRef(card);
		event.setOriginStoreCode(store);
		event.setOccurredAt(occurredAt);
		event.setPayload(CommunicationTypes.json(payload));
		return eventRepository().save(event);
	}

	public void rule(String code, String eventType, String templateCode, int lifetimeHours) {
		CommunicationRule rule = new CommunicationRule();
		rule.setId(nextId());
		rule.setCode(code);
		rule.setEventType(eventType);
		rule.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		rule.setChannel(CommunicationTypes.SMS);
		rule.setTemplateCode(templateCode);
		rule.setLifetimeHours(lifetimeHours);
		rule.setActive(true);
		rules.add(rule);
	}

	public void template(String code, String body) {
		CommunicationTemplate template = new CommunicationTemplate();
		template.setId(nextId());
		template.setCode(code);
		template.setName(code);
		template.setChannel(CommunicationTypes.SMS);
		template.setLanguageCode("fr");
		template.setBody(body);
		template.setActive(true);
		templates.add(template);
	}

	public CommunicationEvent byKey(String dedupKey) {
		return events.values().stream().filter(e -> e.getDedupKey().equals(dedupKey)).findFirst().orElse(null);
	}

	public List<CommunicationMessage> messagesOf(Long eventId) {
		return messages.values().stream().filter(m -> eventId.equals(m.getEventId())).collect(Collectors.toList());
	}

	@SuppressWarnings("unchecked")
	public CommunicationEventRepository eventRepository() {
		return stub(CommunicationEventRepository.class, (method, args) -> {
			switch (method) {
				case "findByDedupKey":
					return Optional.ofNullable(byKey((String) args[0]));
				case "findById":
					return Optional.ofNullable(events.get(args[0]));
				case "findAllById":
					return StreamSupport.stream(((Iterable<Long>) args[0]).spliterator(), false).map(events::get)
							.filter(e -> e != null).collect(Collectors.toList());
				case "findToProcess": {
					int max = (Integer) args[2];
					long afterId = (Long) args[3];
					return events.values().stream()
							.filter(e -> e.getStatus() == args[0] || (e.getStatus() == args[1] && e.getAttempts() < max))
							.filter(e -> e.getId() > afterId).map(CommunicationEvent::getId)
							.limit(((Pageable) args[4]).getPageSize()).collect(Collectors.toList());
				}
				case "findOlderThan":
					return events.values().stream().filter(e -> ((Collection<?>) args[0]).contains(e.getStatus()))
							.filter(e -> e.getOccurredAt().isBefore((LocalDateTime) args[1]))
							.limit(((Pageable) args[2]).getPageSize()).collect(Collectors.toList());
				case "save": {
					CommunicationEvent event = (CommunicationEvent) args[0];
					if (event.getId() == null) {
						if (byKey(event.getDedupKey()) != null) {
							throw new IllegalStateException("uk_comm_event_dedup: " + event.getDedupKey());
						}
						event.setId(nextId());
					}
					events.put(event.getId(), event);
					return event;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	public CommunicationMessageRepository messageRepository() {
		return stub(CommunicationMessageRepository.class, (method, args) -> {
			switch (method) {
				case "findByDedupKey":
					return messages.values().stream().filter(m -> m.getDedupKey().equals(args[0])).findFirst();
				case "findById":
					return Optional.ofNullable(messages.get(args[0]));
				case "findDue": {
					LocalDateTime at = (LocalDateTime) args[1];
					long afterId = (Long) args[2];
					return messages.values().stream().filter(m -> m.getStatus() == args[0])
							.filter(m -> m.getNextAttemptAt() == null || !m.getNextAttemptAt().isAfter(at))
							.filter(m -> m.getId() > afterId).limit(((Pageable) args[3]).getPageSize())
							.collect(Collectors.toList());
				}
				case "save": {
					CommunicationMessage message = (CommunicationMessage) args[0];
					if (message.getId() == null) {
						message.setId(nextId());
					}
					messages.put(message.getId(), message);
					return message;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	public CommunicationRuleRepository ruleRepository() {
		return stub(CommunicationRuleRepository.class, (method, args) -> {
			switch (method) {
				case "findByEventTypeAndActiveTrueOrderByCode":
					return rules.stream().filter(r -> r.getEventType().equals(args[0]) && Boolean.TRUE.equals(r.getActive()))
							.collect(Collectors.toList());
				case "findByCode":
					return rules.stream().filter(r -> r.getCode().equals(args[0])).findFirst();
				default:
					return UNHANDLED;
			}
		});
	}

	public CommunicationTemplateRepository templateRepository() {
		return stub(CommunicationTemplateRepository.class, (method, args) -> "findByCode".equals(method)
				? templates.stream().filter(t -> t.getCode().equals(args[0])).findFirst() : UNHANDLED);
	}

	public CommunicationStateRepository stateRepository() {
		return stub(CommunicationStateRepository.class, (method, args) -> {
			switch (method) {
				case "findByCode":
					return Optional.ofNullable(states.get(args[0]));
				case "save": {
					CommunicationState state = (CommunicationState) args[0];
					states.put(state.getCode(), state);
					return state;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	private interface Handler {
		Object handle(String method, Object[] args);
	}

	private static final Object UNHANDLED = new Object();

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				case "toString": return type.getSimpleName() + "InMemory";
				default: break;
			}
			Object result = handler.handle(method.getName(), args == null ? new Object[0] : args);
			if (result == UNHANDLED) {
				throw new UnsupportedOperationException("Unexpected call: " + type.getSimpleName() + "." + method.getName());
			}
			return result;
		});
	}
}
