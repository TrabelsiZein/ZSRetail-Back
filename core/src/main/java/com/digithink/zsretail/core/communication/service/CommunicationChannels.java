package com.digithink.zsretail.core.communication.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.communication.CommunicationException;
import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationChannelState;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider;
import com.digithink.zsretail.core.communication.provider.TestCommunicationProvider;
import com.digithink.zsretail.core.communication.repository.CommunicationMessageRepository;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;

/**
 * 3.3.0, communication step 8a: the Canaux tab of the Communication page, where the messages are made. Read only: per
 * channel, on or off (and why off), its provider and what the provider shows of its settings (never a password or a
 * key: "set" or "not set"), its sender, the test-recipient mode, the last message given to its provider. The test send:
 * a fixed short text to an address typed in the tab, through the channel's provider, now, kept in the messages as a
 * test (recipient type TEST). Step 8a part 2: a channel's pause (since, until, reason) and its recent sending rate per
 * hour; a test accepted ends the pause. See docs/modules/communication.md, "Step 8a" and "Step 8a, part 2".
 */
@Service
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
public class CommunicationChannels {

	/** The recipient type of a test sent from the Canaux tab. */
	public static final String TEST = "TEST";
	private static final DateTimeFormatter TEST_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

	private final CommunicationSettings settings;
	private final Map<String, CommunicationProvider> providers = new LinkedHashMap<>();
	private final CommunicationMessageRepository messages;
	private final CommunicationSender sender;
	private final Supplier<String> companyName;
	private final Supplier<String> licenceName;
	private final Optional<CampaignStoreNames> storeNames;
	private final Supplier<LocalDateTime> clock;
	/** Step 8a part 2: each channel's pause and speed; null in the tests of before. */
	private CommunicationChannelStates channelStates;
	/** Step 8c: the templates (a WhatsApp test sends one); null in the tests of before. */
	private com.digithink.zsretail.core.communication.repository.CommunicationTemplateRepository templates;

	@Autowired
	public CommunicationChannels(CommunicationSettings settings, List<CommunicationProvider> providers,
			CommunicationMessageRepository messages, CommunicationSender sender, CommunicationNames names,
			Optional<CampaignStoreNames> storeNames, CommunicationChannelStates channelStates,
			com.digithink.zsretail.core.communication.repository.CommunicationTemplateRepository templates) {
		this(settings, providers, messages, sender, names::companyName, names::licenceName, storeNames, LocalDateTime::now);
		this.channelStates = channelStates;
		this.templates = templates;
	}

	/** Step 8c: with the templates (tests). */
	public CommunicationChannels withTemplates(
			com.digithink.zsretail.core.communication.repository.CommunicationTemplateRepository templates) {
		this.templates = templates;
		return this;
	}

	/** Step 8a part 2: with the channels' pause and speed (tests). */
	public CommunicationChannels withChannelStates(CommunicationChannelStates channelStates) {
		this.channelStates = channelStates;
		return this;
	}

	/** With given names and clock: used by the tests. */
	public CommunicationChannels(CommunicationSettings settings, List<CommunicationProvider> providers,
			CommunicationMessageRepository messages, CommunicationSender sender, Supplier<String> companyName,
			Supplier<String> licenceName, Optional<CampaignStoreNames> storeNames, Supplier<LocalDateTime> clock) {
		this.settings = settings;
		providers.forEach(p -> this.providers.put(p.getName(), p));
		this.messages = messages;
		this.sender = sender;
		this.companyName = companyName;
		this.licenceName = licenceName;
		this.storeNames = storeNames;
		this.clock = clock;
	}

	/**
	 * {channels: [{channel, on, offReason, provider, settings, sender, testRecipient, last {status, at, error}, pause
	 * {since, until, problems, reason} (step 8a part 2, null when not paused), perHour (null without a recent measure)}],
	 * testMode, companyNameEmpty, standIn {storeName, licenceName}}.
	 */
	public Map<String, Object> view() {
		List<Map<String, Object>> channels = new ArrayList<>();
		boolean testMode = false;
		Map<String, CommunicationChannelState> states = channelStates == null ? java.util.Collections.emptyMap()
				: channelStates.all();
		for (String channel : CommunicationTypes.CHANNELS) {
			Map<String, Object> row = new LinkedHashMap<>();
			String providerName = settings.provider(channel);
			CommunicationProvider provider = providers.get(providerName);
			row.put("channel", channel);
			row.put("on", settings.isEnabled(channel));
			row.put("offReason", settings.offReason(channel));
			row.put("provider", providerName);
			row.put("settings", provider == null ? null : provider.describe());
			// Step 8b: any provider's sender (an email's sender, an SMS's sender name)
			row.put("sender", provider == null ? null : provider.sender());
			String test = settings.testRecipient(channel);
			testMode |= test != null;
			row.put("testRecipient", test);
			row.put("last", messages.findFirstByChannelAndAttemptsGreaterThanOrderByUpdatedAtDesc(channel, 0).map(m -> {
				Map<String, Object> last = new LinkedHashMap<>();
				last.put("status", m.getStatus());
				last.put("at", m.getSentAt() != null ? m.getSentAt() : m.getUpdatedAt());
				last.put("error", m.getStatus() == CommunicationMessageStatus.SENT ? null : m.getLastError());
				return last;
			}).orElse(null));
			row.put("pause", CommunicationChannelStates.pauseView(states.get(channel)));
			row.put("perHour", channelStates == null ? null : channelStates.perHour(channel, providerName));
			channels.add(row);
		}
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("channels", channels);
		view.put("testMode", testMode);
		String company = companyName.get();
		view.put("companyNameEmpty", company == null);
		Map<String, Object> standIn = new LinkedHashMap<>();
		standIn.put("storeName", storeNames.map(s -> s.resolver().apply(null, null)).orElse(null));
		standIn.put("licenceName", licenceName.get());
		view.put("standIn", standIn);
		view.put("whatsappTemplates", whatsappTemplates()); // step 8c, the WhatsApp test
		return view;
	}

	/**
	 * A test now: a fixed short text to the address typed, through the channel's provider (the test-recipient mode applies
	 * as to any message). Refused: an unknown channel, a channel off, an address that is not one for the channel.
	 * Tried once: a temporary failure is FAILED at once (never tried again by the cycle; "Renvoyer" may send it again).
	 * Returns the message's row with its result.
	 */
	/**
	 * Step 8c: the WhatsApp templates a test can send (a name at Meta), each with the sample values of its {fields} in
	 * their order: [{code, name, providerTemplateName, language, format, active, values: [{field, value}]}].
	 */
	List<Map<String, Object>> whatsappTemplates() {
		List<Map<String, Object>> list = new ArrayList<>();
		if (templates == null) {
			return list;
		}
		Map<String, Object> sample = sample();
		templates.findAll(org.springframework.data.domain.Sort.by("code")).stream()
				.filter(t -> CommunicationTypes.WHATSAPP.equals(t.getChannel()) && CommunicationTemplates.providerTemplate(t) != null)
				.forEach(t -> {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("code", t.getCode());
					row.put("name", t.getName());
					row.put("providerTemplateName", t.getProviderTemplateName());
					row.put("language", t.providerLanguageOrOwn());
					row.put("format", CommunicationTemplates.parameterFormat(t.getProviderParameterFormat()));
					row.put("active", Boolean.TRUE.equals(t.getActive()));
					List<Map<String, Object>> values = new ArrayList<>();
					for (String field : CommunicationTemplates.usedFields(t.getBody())) {
						Map<String, Object> value = new LinkedHashMap<>();
						value.put("field", field);
						value.put("value", CommunicationTemplates.preview("{" + field + "}", sample));
						values.add(value);
					}
					row.put("values", values);
					list.add(row);
				});
		return list;
	}

	/** The sample values of the template window, the installation's company name in them. */
	private Map<String, Object> sample() {
		Map<String, Object> sample = new LinkedHashMap<>(CommunicationAdmin.SAMPLE);
		String company = companyName.get();
		sample.put("companyName", company == null || company.trim().isEmpty() ? null : company.trim());
		return CommunicationTemplates.withFallbacks(sample, licenceName.get());
	}

	/** As {@link #testSend(String, String, String, String)} without a template (SMS, email). */
	public Map<String, Object> testSend(String channel, String address, String user) {
		return testSend(channel, address, user, null);
	}

	/**
	 * Step 8c: a WhatsApp test sends a template (its name at Meta) with the sample values: {@code templateCode} required
	 * (COMM_TEST_TEMPLATE otherwise).
	 */
	public Map<String, Object> testSend(String channel, String address, String user, String templateCode) {
		String wanted = channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
		if (!CommunicationTypes.CHANNELS.contains(wanted)) {
			throw CommunicationException.refused("COMM_CHANNEL_UNKNOWN", "Unknown channel " + channel, "channel",
					channel == null ? "" : channel);
		}
		if (!settings.isEnabled(wanted)) {
			throw CommunicationException.refused("COMM_CHANNEL_OFF", "The channel " + wanted + " is off", "channel", wanted);
		}
		String to = CommunicationTypes.EMAIL.equals(wanted) ? CommunicationSettings.testEmail(address)
				: CommunicationSettings.testPhone(address, settings.getCountryCode());
		if (to == null) {
			throw CommunicationException.refused("COMM_TEST_ADDRESS", "'" + (address == null ? "" : address)
					+ "' is not " + (CommunicationTypes.EMAIL.equals(wanted) ? "an email" : "a phone (8 digits, or + and 8 to"
							+ " 15 digits)"), "channel", wanted, "address", address == null ? "" : address);
		}
		LocalDateTime now = clock.get();
		CommunicationMessage message = new CommunicationMessage();
		message.setChannel(wanted);
		message.setProvider(settings.provider(wanted));
		message.setRecipientType(TEST);
		message.setAddress(to);
		message.setSubject(CommunicationTypes.EMAIL.equals(wanted) ? "ZS Retail : message de test" : null);
		message.setBody("ZS Retail : message de test du canal " + wanted + " envoyé le " + now.format(TEST_TIME)
				+ ". Si vous le lisez, le canal fonctionne.");
		if (CommunicationTypes.WHATSAPP.equals(wanted) && !TestCommunicationProvider.NAME.equals(message.getProvider())) {
			// Step 8c: WhatsApp at Meta sends templates only: the one chosen, with the sample values
			String code = templateCode == null ? "" : templateCode.trim();
			com.digithink.zsretail.core.communication.model.CommunicationTemplate template = templates == null || code.isEmpty()
					? null : templates.findByCode(code).orElse(null);
			if (template == null || !CommunicationTypes.WHATSAPP.equals(template.getChannel())
					|| CommunicationTemplates.providerTemplate(template) == null) {
				throw CommunicationException.refused("COMM_TEST_TEMPLATE", "Choose a WhatsApp template with its name at Meta",
						"template", code);
			}
			Map<String, Object> sample = sample();
			message.setTemplateCode(template.getCode());
			message.setLanguageCode(template.getLanguageCode());
			message.setBody(CommunicationTemplates.render(template.getCode(), template.getBody(), sample));
			message.setProviderParams(CommunicationTemplates.providerParams(CommunicationTemplates.providerTemplate(template),
					template.getBody(), sample));
		}
		message.setDedupKey("TEST:" + UUID.randomUUID());
		message.setStatus(CommunicationMessageStatus.SENDING); // never taken by the cycle meanwhile
		message.setCreatedBy(user);
		message.setUpdatedBy(user);
		CommunicationMessage saved = messages.save(message);
		CommunicationChannelState before = channelStates == null ? null : channelStates.of(wanted);
		boolean wasPaused = before != null && before.isPaused();
		CommunicationSender.Handled handled = sender.sendNow(saved);
		CommunicationMessageStatus status = handled.status;
		if (status == CommunicationMessageStatus.PENDING) {
			// A test is tried once: a temporary failure is not tried again by the cycle (the tab shows it now). Step 8a
			// part 2: a channel problem used no attempt; the test was tried, its reason kept on its row
			saved.setStatus(CommunicationMessageStatus.FAILED);
			saved.setNextAttemptAt(null);
			if (saved.getAttempts() == 0) {
				saved.setAttempts(1);
			}
			if (handled.outcome == CommunicationProvider.Outcome.CHANNEL_PROBLEM) {
				saved.setLastError(handled.error);
			}
			messages.save(saved);
			status = CommunicationMessageStatus.FAILED;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", saved.getId());
		result.put("status", status);
		result.put("address", saved.getAddress());
		result.put("redirectedTo", saved.getRedirectedTo());
		result.put("providerMessageId", saved.getProviderMessageId());
		result.put("error", status == CommunicationMessageStatus.SENT ? null : saved.getLastError());
		// Step 8a part 2: the channel's pause after the test (lifted by it, kept, or started by its problem)
		result.put("pauseLifted", wasPaused && status == CommunicationMessageStatus.SENT);
		result.put("pause", channelStates == null ? null : CommunicationChannelStates.pauseView(channelStates.of(wanted)));
		return result;
	}
}
