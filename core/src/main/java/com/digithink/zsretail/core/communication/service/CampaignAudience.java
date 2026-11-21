package com.digithink.zsretail.core.communication.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.springframework.data.domain.PageRequest;

import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.digithink.zsretail.core.repository.LoyaltyMemberRepository;

/**
 * 3.3.0, communication step 7: who receives a campaign. One rule for the count the window shows and for the messages the
 * maker writes: the same query (LoyaltyMemberRepository.findCampaignCandidates: active members with the consent, by id),
 * then, for each member in id order, the same checks in the same order:
 * <ol>
 * <li>a card merged into another member (a head office's alias): never a recipient, counted nowhere;</li>
 * <li>no address for the channel (the address rule of every message: 8 digits for SMS and WhatsApp, an email): left out,
 * NO_ADDRESS;</li>
 * <li>the address of a member met before (two members, one phone): left out, SAME_ADDRESS; the first member by id keeps
 * it;</li>
 * <li>a field of the template without a value once the names stand in for each other (CommunicationTemplates
 * .withFallbacks): left out, NO_VALUE;</li>
 * <li>else a recipient, with its address and its values.</li>
 * </ol>
 * Not thread-safe: one per scan.
 */
public final class CampaignAudience {

	/** Members read per query. */
	public static final int PAGE = 500;

	public enum Outcome {
		RECEIVE, NO_ADDRESS, SAME_ADDRESS, NO_VALUE, ALIAS
	}

	/** A member judged. */
	public static final class Judged {
		private final Outcome outcome;
		private final long memberId;
		private final String card;
		private final String address;
		private final Map<String, Object> values;

		Judged(Outcome outcome, long memberId, String card, String address, Map<String, Object> values) {
			this.outcome = outcome;
			this.memberId = memberId;
			this.card = card;
			this.address = address;
			this.values = values;
		}

		public Outcome getOutcome() {
			return outcome;
		}

		public long getMemberId() {
			return memberId;
		}

		public String getCard() {
			return card;
		}

		public String getAddress() {
			return address;
		}

		public Map<String, Object> getValues() {
			return values;
		}
	}

	/** The count of a whole scan, and the first recipient (the preview's member). */
	public static final class Counts {
		private int receive;
		private int noAddress;
		private int sameAddress;
		private int noValue;
		private long noConsent;
		private Judged first;

		public int getReceive() {
			return receive;
		}

		public int getNoAddress() {
			return noAddress;
		}

		public int getSameAddress() {
			return sameAddress;
		}

		public int getNoValue() {
			return noValue;
		}

		public long getNoConsent() {
			return noConsent;
		}

		public Judged getFirst() {
			return first;
		}
	}

	private final String channel;
	private final List<String> used;
	private final String countryCode;
	private final String companyName;
	private final String licenceName;
	private final BiFunction<String, String, String> storeOf;
	private final Set<String> aliasCards;

	/**
	 * @param channel     the campaign's channel
	 * @param used        the fields its template uses (subject and text)
	 * @param companyName the company's name (Company information), null when empty
	 * @param licenceName the licence holder's name, the last stand-in
	 * @param storeOf     (createdBy, card) -> the member's store name; null: none
	 * @param aliasCards  the cards merged into another member (a head office)
	 */
	public CampaignAudience(String channel, List<String> used, String countryCode, String companyName, String licenceName,
			BiFunction<String, String, String> storeOf, Set<String> aliasCards) {
		this.channel = channel;
		this.used = used;
		this.countryCode = countryCode;
		this.companyName = companyName;
		this.licenceName = licenceName;
		this.storeOf = storeOf == null ? (createdBy, card) -> null : storeOf;
		this.aliasCards = aliasCards == null ? Collections.emptySet() : aliasCards;
	}

	/**
	 * One row of findCampaignCandidates [id, card, firstName, lastName, phone, email, points, createdBy], judged.
	 * {@code taken}: the addresses of the recipients met before (the scan's own, or the campaign's messages already made);
	 * a recipient's address is added to it.
	 */
	public Judged judge(Object[] row, Set<String> taken) {
		long id = ((Number) row[0]).longValue();
		String card = (String) row[1];
		if (card != null && aliasCards.contains(card)) {
			return new Judged(Outcome.ALIAS, id, card, null, null);
		}
		String address = CommunicationEventProcessor.address(channel, (String) row[4], (String) row[5], countryCode);
		if (address == null) {
			return new Judged(Outcome.NO_ADDRESS, id, card, null, null);
		}
		if (taken.contains(key(address))) {
			return new Judged(Outcome.SAME_ADDRESS, id, card, address, null);
		}
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("firstName", row[2]);
		values.put("lastName", row[3]);
		values.put("cardNumber", card);
		values.put("pointsBalance", row[6] == null ? 0 : row[6]);
		values.put("companyName", companyName);
		values.put("storeName", storeOf.apply((String) row[7], card));
		Map<String, Object> filled = CommunicationTemplates.withFallbacks(values, licenceName);
		if (!CommunicationTemplates.emptyFields(filled, used.stream().map(f -> "{" + f + "}").toArray(String[]::new))
				.isEmpty()) {
			return new Judged(Outcome.NO_VALUE, id, card, address, filled);
		}
		taken.add(key(address));
		return new Judged(Outcome.RECEIVE, id, card, address, filled);
	}

	/** The fields of the template without a value for this member (the NO_VALUE reason). */
	public List<String> emptyFields(Map<String, Object> values) {
		return CommunicationTemplates.emptyFields(values, used.stream().map(f -> "{" + f + "}").toArray(String[]::new));
	}

	/** One address once: an email whatever its case. */
	public static String key(String address) {
		return address == null ? null : address.trim().toLowerCase(java.util.Locale.ROOT);
	}

	/** The whole scan of the window: every candidate, judged in id order, counted; the members without consent too. */
	public Counts count(LoyaltyMemberRepository members) {
		Counts counts = new Counts();
		Set<String> taken = new java.util.HashSet<>();
		long afterId = 0;
		while (true) {
			List<Object[]> rows = members.findCampaignCandidates(RecordOrigin.HEAD_OFFICE, afterId, PageRequest.of(0, PAGE));
			for (Object[] row : rows) {
				Judged judged = judge(row, taken);
				afterId = judged.getMemberId();
				switch (judged.getOutcome()) {
					case RECEIVE:
						counts.receive++;
						if (counts.first == null) {
							counts.first = judged;
						}
						break;
					case NO_ADDRESS:
						counts.noAddress++;
						break;
					case SAME_ADDRESS:
						counts.sameAddress++;
						break;
					case NO_VALUE:
						counts.noValue++;
						break;
					default:
						break;
				}
			}
			if (rows.size() < PAGE) {
				break;
			}
		}
		counts.noConsent = members.countActiveWithoutConsent(RecordOrigin.HEAD_OFFICE);
		return counts;
	}
}
