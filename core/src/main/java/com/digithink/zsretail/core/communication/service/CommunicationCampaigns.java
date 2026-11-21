package com.digithink.zsretail.core.communication.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.communication.CommunicationException;
import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationCampaignStatus;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationCampaign;
import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.communication.repository.CommunicationCampaignRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationMessageRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationTemplateRepository;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.repository.LoyaltyMemberRepository;

/**
 * 3.3.0, communication step 7: the campaigns of the Communication page, where this installation sends. A campaign sends
 * one template to the members who accept messages (CampaignAudience); launching writes it RUNNING and nothing else, the
 * messages are made in the background (CommunicationCampaignMaker), never in the request. Refused with a coded reason
 * (COMM_*): the channel off, the template unknown, inactive, of another channel or using a sale's fields (ticket number,
 * total, points of the sale), nobody to receive it; the same template already launched today asks a second
 * confirmation (COMM_CAMPAIGN_SAME_DAY, 409). A running campaign can be cancelled: its waiting messages SKIPPED, the
 * ones sent stay. See docs/modules/communication.md, "Step 7".
 */
@Service
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
public class CommunicationCampaigns {

	static final int MAX_NAME = 100;
	static final int MIN_LIFETIME_HOURS = 1;
	static final int MAX_LIFETIME_HOURS = 720;
	static final int MAX_PAGE_SIZE = 200;
	private static final DateTimeFormatter CODE_TIME = DateTimeFormatter.ofPattern("yyMMdd-HHmmss-SSS");
	private static final DateTimeFormatter REASON_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final CommunicationCampaignRepository campaigns;
	private final CommunicationMessageRepository messages;
	private final CommunicationTemplateRepository templates;
	private final LoyaltyMemberRepository members;
	private final CommunicationSettings settings;
	private final Supplier<String> companyName;
	private final Supplier<String> licenceName;
	private final Optional<CampaignStoreNames> storeNames;
	private final Optional<CommunicationMembers> directory;
	private final Supplier<LocalDateTime> clock;
	/** Step 8a part 2: the channel's pause and rate (the window's sending time); null in the tests of before. */
	private CommunicationChannelStates channelStates;

	@Autowired
	public CommunicationCampaigns(CommunicationCampaignRepository campaigns, CommunicationMessageRepository messages,
			CommunicationTemplateRepository templates, LoyaltyMemberRepository members, CommunicationSettings settings,
			CommunicationNames names, Optional<CampaignStoreNames> storeNames, Optional<CommunicationMembers> directory,
			CommunicationChannelStates channelStates) {
		this(campaigns, messages, templates, members, settings, names::companyName, names::licenceName, storeNames,
				directory, LocalDateTime::now);
		this.channelStates = channelStates;
	}

	/** Step 8a part 2: with the channels' pause and rate (tests). */
	public CommunicationCampaigns withChannelStates(CommunicationChannelStates channelStates) {
		this.channelStates = channelStates;
		return this;
	}

	/** With given names and clock: used by the tests. */
	public CommunicationCampaigns(CommunicationCampaignRepository campaigns, CommunicationMessageRepository messages,
			CommunicationTemplateRepository templates, LoyaltyMemberRepository members, CommunicationSettings settings,
			Supplier<String> companyName, Supplier<String> licenceName, Optional<CampaignStoreNames> storeNames,
			Optional<CommunicationMembers> directory, Supplier<LocalDateTime> clock) {
		this.campaigns = campaigns;
		this.messages = messages;
		this.templates = templates;
		this.members = members;
		this.settings = settings;
		this.companyName = companyName;
		this.licenceName = licenceName;
		this.storeNames = storeNames;
		this.directory = directory;
		this.clock = clock;
	}

	// ─── The window ──────────────────────────────────────────────────

	/**
	 * Every template with what a campaign may do with it: {code, name, channel, languageCode, active, channelOn,
	 * saleFields, usable}. Usable: active, its channel on, no sale's field.
	 */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> templates() {
		return templates.findAll(Sort.by("code")).stream().map(t -> {
			Map<String, Object> row = new LinkedHashMap<>();
			List<String> sale = CommunicationTemplates.saleFields(t.getSubject(), t.getBody());
			boolean channelOn = settings.isEnabled(t.getChannel());
			row.put("code", t.getCode());
			row.put("name", t.getName());
			row.put("channel", t.getChannel());
			row.put("languageCode", t.getLanguageCode());
			row.put("active", Boolean.TRUE.equals(t.getActive()));
			row.put("channelOn", channelOn);
			row.put("saleFields", sale);
			// Step 8c: a WhatsApp template is sent by its name at Meta: without one, not offered
			boolean noProviderName = CommunicationTypes.WHATSAPP.equals(t.getChannel())
					&& CommunicationTemplates.providerTemplate(t) == null;
			row.put("noProviderName", noProviderName);
			row.put("providerProblem", t.getProviderProblem());
			row.put("usable", Boolean.TRUE.equals(t.getActive()) && channelOn && sale.isEmpty() && !noProviderName);
			return row;
		}).collect(Collectors.toList());
	}

	/**
	 * The window's count and preview for a template: {channel, receive, noConsent, noAddress, sameAddress, noValue,
	 * emptyFields (of the first member left out for a missing value), preview {text, length, encoding, parts, card,
	 * memberName} with the first member who would receive it (null when nobody), sameDay {code, name, launchedAt} when
	 * the template was launched today}. Refused as the launch is (except nobody to receive it, and the same day). Step 8a
	 * part 2: sending {perHour, waiting, hours, proposedLifetimeHours, beyondMax} ({@link #sending}), pause {since, until,
	 * problems, reason} of the channel when it is paused.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> audience(String templateCode, String channel) {
		CommunicationTemplate template = usableTemplate(templateCode, channel);
		CampaignAudience audience = audienceOf(template);
		CampaignAudience.Counts counts = audience.count(members);
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("channel", template.getChannel());
		answer.put("receive", counts.getReceive());
		answer.put("noConsent", counts.getNoConsent());
		answer.put("noAddress", counts.getNoAddress());
		answer.put("sameAddress", counts.getSameAddress());
		answer.put("noValue", counts.getNoValue());
		answer.put("preview", preview(template, counts.getFirst()));
		answer.put("emptyFields", counts.getNoValue() == 0 ? Collections.emptyList() : firstEmptyFields(audience));
		answer.put("sameDay", sameDay(template.getCode()));
		answer.put("sending", sending(template.getChannel(), counts.getReceive()));
		answer.put("pause", channelStates == null ? null
				: CommunicationChannelStates.pauseView(channelStates.of(template.getChannel())));
		return answer;
	}

	/** Step 8a part 2: the margin of the proposed lifetime over the sending time (a fifth more). */
	static final double LIFETIME_MARGIN = 1.2;

	/**
	 * Step 8a part 2: about how long the campaign takes to send, from the channel's recent rate (CommunicationChannelStates
	 * .perHour): {perHour, waiting (the channel's messages waiting now, sent before), hours ((waiting + receive) / perHour),
	 * proposedLifetimeHours (hours plus a fifth, rounded up, 1 to 720), beyondMax (more than 720 h needed)}. Null without
	 * a recent rate (never sent, a measure older than 30 days, the provider test) or nobody to receive it: no line.
	 */
	Map<String, Object> sending(String channel, long receive) {
		if (channelStates == null || receive <= 0) {
			return null;
		}
		Integer perHour = channelStates.perHour(channel, settings.provider(channel));
		if (perHour == null) {
			return null;
		}
		long waiting = channelStates.waiting(channel);
		double hours = (waiting + receive) / (double) perHour;
		double wanted = Math.ceil(hours * LIFETIME_MARGIN);
		Map<String, Object> sending = new LinkedHashMap<>();
		sending.put("perHour", perHour);
		sending.put("waiting", waiting);
		sending.put("hours", Math.round(hours * 100) / 100d);
		sending.put("proposedLifetimeHours", (int) Math.max(MIN_LIFETIME_HOURS, Math.min(MAX_LIFETIME_HOURS, wanted)));
		sending.put("beyondMax", wanted > MAX_LIFETIME_HOURS);
		return sending;
	}

	/** The new campaign: {name, templateCode, channel, lifetimeHours, confirmSameDay}. */
	public static final class LaunchForm {
		public String name;
		public String templateCode;
		public String channel;
		public Integer lifetimeHours;
		public Boolean confirmSameDay;
	}

	/**
	 * Launched: written RUNNING with the count of now (targeted) and the template's text; its messages are made by the
	 * maker at its next cycles. Returns the campaign's row.
	 */
	@Transactional
	public Map<String, Object> launch(LaunchForm form, String user) {
		String name = form.name == null ? "" : form.name.trim();
		if (name.isEmpty()) {
			throw CommunicationException.refused("COMM_CAMPAIGN_NAME_REQUIRED", "The campaign's name is required");
		}
		if (name.length() > MAX_NAME) {
			throw CommunicationException.refused("COMM_TEMPLATE_TOO_LONG", "The name is at most " + MAX_NAME + " characters",
					"field", "name", "max", MAX_NAME);
		}
		int lifetime = form.lifetimeHours == null ? CommunicationCampaign.DEFAULT_LIFETIME_HOURS : form.lifetimeHours;
		if (lifetime < MIN_LIFETIME_HOURS || lifetime > MAX_LIFETIME_HOURS) {
			throw CommunicationException.refused("COMM_CAMPAIGN_LIFETIME", "The lifetime is " + MIN_LIFETIME_HOURS + " to "
					+ MAX_LIFETIME_HOURS + " hours", "min", MIN_LIFETIME_HOURS, "max", MAX_LIFETIME_HOURS);
		}
		CommunicationTemplate template = usableTemplate(form.templateCode, form.channel);
		Map<String, Object> earlier = sameDay(template.getCode());
		if (earlier != null && !Boolean.TRUE.equals(form.confirmSameDay)) {
			throw CommunicationException.conflict("COMM_CAMPAIGN_SAME_DAY", "The template " + template.getCode()
					+ " was already launched today (" + earlier.get("name") + ", " + earlier.get("time") + ")", "name",
					earlier.get("name"), "time", earlier.get("time"), "code", earlier.get("code"));
		}
		CampaignAudience.Counts counts = audienceOf(template).count(members);
		if (counts.getReceive() == 0) {
			throw CommunicationException.refused("COMM_CAMPAIGN_NO_RECIPIENT", "Nobody would receive it: " + counts.getNoConsent()
					+ " without consent, " + counts.getNoAddress() + " without an address, " + counts.getNoValue()
					+ " with a value missing", "noConsent", counts.getNoConsent(), "noAddress", counts.getNoAddress(),
					"noValue", counts.getNoValue());
		}
		LocalDateTime now = clock.get();
		CommunicationCampaign campaign = new CommunicationCampaign();
		campaign.setCode("CMP-" + now.format(CODE_TIME));
		campaign.setName(name);
		campaign.setTemplateCode(template.getCode());
		campaign.setChannel(template.getChannel());
		campaign.setLanguageCode(template.getLanguageCode());
		// Step 8c: the template at Meta, kept as the text is (a change after the launch does not touch the campaign)
		campaign.setProviderTemplate(CommunicationTypes.WHATSAPP.equals(template.getChannel())
				? CommunicationTemplates.providerTemplate(template) : null);
		campaign.setSubject(template.getSubject());
		campaign.setBody(template.getBody());
		campaign.setLifetimeHours(lifetime);
		campaign.setTargeted(counts.getReceive());
		campaign.setStatus(CommunicationCampaignStatus.RUNNING);
		campaign.setLaunchedAt(now);
		campaign.setLaunchedBy(user);
		campaign.setCreatedBy(user);
		campaign.setUpdatedBy(user);
		CommunicationCampaign saved = campaigns.save(campaign);
		return row(saved, counters(Collections.singletonList(saved.getId())).get(saved.getId()));
	}

	// ─── The list and one campaign ───────────────────────────────────

	/** A page of campaigns, newest first, each with its messages' counters. */
	@Transactional(readOnly = true)
	public Map<String, Object> list(int page, int size) {
		Page<CommunicationCampaign> found = campaigns.findAll(PageRequest.of(Math.max(0, page),
				Math.max(1, Math.min(size, MAX_PAGE_SIZE)), Sort.by(Sort.Direction.DESC, "id")));
		Map<Long, Map<String, Long>> counters = counters(found.getContent().stream().map(CommunicationCampaign::getId)
				.collect(Collectors.toList()));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("content", found.getContent().stream().map(c -> row(c, counters.get(c.getId())))
				.collect(Collectors.toList()));
		result.put("totalElements", found.getTotalElements());
		result.put("totalPages", found.getTotalPages());
		result.put("number", found.getNumber());
		result.put("size", found.getSize());
		return result;
	}

	/** The latest campaigns {id, code, name} (the Messages tab's filter). */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> names() {
		return campaigns.findTop100ByOrderByIdDesc().stream().map(c -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", c.getId());
			row.put("code", c.getCode());
			row.put("name", c.getName());
			return row;
		}).collect(Collectors.toList());
	}

	/** One campaign, its counters and its text. */
	@Transactional(readOnly = true)
	public Map<String, Object> campaign(long id) {
		CommunicationCampaign campaign = find(id);
		Map<String, Object> row = row(campaign, counters(Collections.singletonList(id)).get(id));
		row.put("subject", campaign.getSubject());
		row.put("body", campaign.getBody());
		row.put("languageCode", campaign.getLanguageCode());
		return row;
	}

	/** Cancelled: CANCELLED, its waiting messages SKIPPED with the reason (one statement); the ones sent stay. */
	@Transactional
	public Map<String, Object> cancel(long id, String user) {
		CommunicationCampaign campaign = find(id);
		if (campaign.getStatus() != CommunicationCampaignStatus.RUNNING) {
			throw CommunicationException.refused("COMM_CAMPAIGN_NOT_RUNNING", "The campaign " + campaign.getCode()
					+ " is " + campaign.getStatus() + ", not running", "status", campaign.getStatus().name());
		}
		LocalDateTime now = clock.get();
		campaign.setStatus(CommunicationCampaignStatus.CANCELLED);
		campaign.setEndedAt(now);
		campaign.setCancelledBy(user);
		campaign.setUpdatedBy(user);
		campaigns.save(campaign);
		messages.skipWaiting(id, CommunicationMessageStatus.PENDING, CommunicationMessageStatus.SKIPPED,
				cancelledReason(user, now), now, user);
		return campaign(id);
	}

	/** The reason of a cancelled campaign's messages, e.g. "campaign cancelled by admin at 2026-11-21 10:15". */
	public static String cancelledReason(String user, LocalDateTime at) {
		return "campaign cancelled by " + (user == null ? "?" : user) + " at " + at.format(REASON_TIME);
	}

	// ─── Helpers ─────────────────────────────────────────────────────

	/** The template a campaign may send, or the coded refusal. */
	CommunicationTemplate usableTemplate(String templateCode, String channel) {
		String code = templateCode == null ? "" : templateCode.trim();
		CommunicationTemplate template = templates.findByCode(code).orElseThrow(() -> CommunicationException.refused(
				"COMM_TEMPLATE_NOT_FOUND", "Template " + code + " not found", "code", code));
		String wanted = channel == null || channel.trim().isEmpty() ? template.getChannel()
				: channel.trim().toUpperCase(Locale.ROOT);
		if (!wanted.equals(template.getChannel())) {
			throw CommunicationException.refused("COMM_CAMPAIGN_TEMPLATE_CHANNEL", "Template " + template.getCode()
					+ " is for " + template.getChannel() + ", not " + wanted, "template", template.getCode(), "channel",
					template.getChannel());
		}
		if (!settings.isEnabled(template.getChannel())) {
			throw CommunicationException.refused("COMM_CHANNEL_OFF", "The channel " + template.getChannel()
					+ " is off (communication." + template.getChannel().toLowerCase(Locale.ROOT) + ".enabled)", "channel",
					template.getChannel());
		}
		if (!Boolean.TRUE.equals(template.getActive())) {
			throw CommunicationException.refused("COMM_CAMPAIGN_TEMPLATE_INACTIVE", "Template " + template.getCode()
					+ " is switched off", "template", template.getCode());
		}
		List<String> sale = CommunicationTemplates.saleFields(template.getSubject(), template.getBody());
		if (!sale.isEmpty()) {
			String fields = sale.stream().map(f -> "{" + f + "}").collect(Collectors.joining(", "));
			throw CommunicationException.refused("COMM_CAMPAIGN_TEMPLATE_FIELDS", "Template " + template.getCode()
					+ " uses " + fields + ": a sale's values, which a campaign does not have", "template", template.getCode(),
					"fields", fields);
		}
		// Step 8c: WhatsApp sends a template by its name at Meta
		if (CommunicationTypes.WHATSAPP.equals(template.getChannel()) && CommunicationTemplates.providerTemplate(template) == null) {
			throw CommunicationException.refused("COMM_TEMPLATE_PROVIDER_NAME", "Template " + template.getCode()
					+ " has no provider template name: a WhatsApp message is sent by the name of its template at Meta",
					"template", template.getCode());
		}
		return template;
	}

	/** The audience of a template here, with this installation's names. */
	CampaignAudience audienceOf(CommunicationTemplate template) {
		return audienceOf(template.getChannel(), CommunicationTemplates.usedFields(template.getSubject(),
				template.getBody()));
	}

	/** The audience of a channel and the fields used (the maker's, from a campaign's own copy of the text). */
	public CampaignAudience audienceOf(String channel, List<String> used) {
		BiFunction<String, String, String> storeOf = storeNames.map(CampaignStoreNames::resolver).orElse(null);
		Set<String> aliases = directory.map(CommunicationMembers::aliasCards).orElse(Collections.emptySet());
		return new CampaignAudience(channel, used, settings.getCountryCode(), companyName.get(), licenceName.get(),
				storeOf, aliases);
	}

	/** The fields without a value of the first member left out for one (the window says which). */
	private List<String> firstEmptyFields(CampaignAudience audience) {
		Set<String> taken = new java.util.HashSet<>();
		long afterId = 0;
		while (true) {
			List<Object[]> rows = members.findCampaignCandidates(com.digithink.zsretail.core.model.enumeration.RecordOrigin
					.HEAD_OFFICE, afterId, PageRequest.of(0, CampaignAudience.PAGE));
			for (Object[] row : rows) {
				CampaignAudience.Judged judged = audience.judge(row, taken);
				afterId = judged.getMemberId();
				if (judged.getOutcome() == CampaignAudience.Outcome.NO_VALUE) {
					return audience.emptyFields(judged.getValues());
				}
			}
			if (rows.size() < CampaignAudience.PAGE) {
				return Collections.emptyList();
			}
		}
	}

	private Map<String, Object> preview(CommunicationTemplate template, CampaignAudience.Judged first) {
		if (first == null) {
			return null;
		}
		String text = CommunicationTemplates.preview(template.getBody(), first.getValues());
		Map<String, Object> preview = new LinkedHashMap<>();
		preview.put("text", text);
		preview.put("subject", template.getSubject() == null ? null
				: CommunicationTemplates.preview(template.getSubject(), first.getValues()));
		if (CommunicationTypes.SMS.equals(template.getChannel())) {
			SmsParts sms = SmsParts.of(text);
			preview.put("length", sms.getLength());
			preview.put("encoding", sms.getEncoding());
			preview.put("parts", sms.getParts());
		} else {
			preview.put("length", text.length());
		}
		preview.put("card", first.getCard());
		Map<String, Object> values = first.getValues();
		preview.put("memberName", ((values.get("firstName") == null ? "" : values.get("firstName")) + " "
				+ (values.get("lastName") == null ? "" : values.get("lastName"))).trim());
		preview.put("address", first.getAddress());
		return preview;
	}

	/** The campaign of this template launched today, {code, name, time}; null when none. */
	private Map<String, Object> sameDay(String templateCode) {
		LocalDateTime today = clock.get().toLocalDate().atStartOfDay();
		return campaigns.findFirstByTemplateCodeAndLaunchedAtGreaterThanEqualOrderByIdDesc(templateCode, today)
				.map(c -> {
					Map<String, Object> earlier = new LinkedHashMap<>();
					earlier.put("code", c.getCode());
					earlier.put("name", c.getName());
					earlier.put("time", c.getLaunchedAt().toLocalTime().withNano(0).toString());
					return earlier;
				}).orElse(null);
	}

	private CommunicationCampaign find(long id) {
		return campaigns.findById(id).orElseThrow(() -> CommunicationException.notFound("COMM_CAMPAIGN_NOT_FOUND",
				"Campaign " + id + " not found"));
	}

	/** Per campaign, its messages counted by status (PENDING, SENT, FAILED, EXPIRED, SKIPPED). */
	private Map<Long, Map<String, Long>> counters(List<Long> ids) {
		Map<Long, Map<String, Long>> counters = new LinkedHashMap<>();
		if (ids.isEmpty()) {
			return counters;
		}
		for (Object[] row : messages.countByCampaignAndStatus(ids)) {
			counters.computeIfAbsent((Long) row[0], k -> new LinkedHashMap<>()).put(String.valueOf(row[1]),
					((Number) row[2]).longValue());
		}
		return counters;
	}

	private static Map<String, Object> row(CommunicationCampaign c, Map<String, Long> byStatus) {
		Map<String, Long> counts = byStatus == null ? Collections.emptyMap() : byStatus;
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", c.getId());
		row.put("code", c.getCode());
		row.put("name", c.getName());
		row.put("templateCode", c.getTemplateCode());
		row.put("channel", c.getChannel());
		row.put("status", c.getStatus());
		row.put("lifetimeHours", c.getLifetimeHours());
		row.put("launchedAt", c.getLaunchedAt());
		row.put("launchedBy", c.getLaunchedBy());
		row.put("targeted", c.getTargeted());
		row.put("made", c.getMade());
		row.put("leftOut", c.getLeftOut());
		row.put("madeAt", c.getMadeAt());
		row.put("endedAt", c.getEndedAt());
		row.put("cancelledBy", c.getCancelledBy());
		row.put("stopReason", c.getStopReason()); // step 8c
		row.put("waiting", counts.getOrDefault(CommunicationMessageStatus.PENDING.name(), 0L));
		row.put("sent", counts.getOrDefault(CommunicationMessageStatus.SENT.name(), 0L));
		row.put("failed", counts.getOrDefault(CommunicationMessageStatus.FAILED.name(), 0L)
				+ counts.getOrDefault(CommunicationMessageStatus.EXPIRED.name(), 0L));
		row.put("skipped", counts.getOrDefault(CommunicationMessageStatus.SKIPPED.name(), 0L));
		return row;
	}
}
