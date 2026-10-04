package com.digithink.zsretail.holink.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeOwned;
import com.digithink.zsretail.dto.CreateLoyaltyMemberRequestDTO;
import com.digithink.zsretail.dto.LoyaltyMemberDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberCopyDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberEditDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyPhoneCheckDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyPointsAdjustDTO;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.LiveAnswer;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.MemberFunction;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.CustomerRepository;
import com.digithink.zsretail.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.repository.MemberFunctionRepository;
import com.digithink.zsretail.service.LoyaltyService;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, step 4: the loyalty member writes of a store whose loyalty is owned by its head office. Called by
 * LoyaltyAPI instead of LoyaltyService for these writes; reads, and earning in the sale, are LoyaltyService as before.
 * <ul>
 * <li><b>Enrol</b> (till and admin page): when the head office answers within the short timeout, the phone is checked
 * across the network first; a phone that already has a card is refused with today's duplicate message naming that
 * card, and that member is saved here so the cashier finds it. Otherwise, or when the head office does not answer,
 * LoyaltyService creates the member here as today (phone unique among the network members held here, card
 * LYL-&lt;store code&gt;-000001), and the LOYALTY_PUSH job sends it up. Enrolling never fails because of the head
 * office.</li>
 * <li><b>Edit, deactivate, link a customer</b>: through the head office (PUT /ho/loyalty/members/{card}), which refuses
 * a store without the right; the answer is saved here. Refused with a clear message when the head office is
 * unreachable.</li>
 * </ul>
 * The program is refused at the store (LoyaltyAPI). Step 5: the fresh balance at the till ({@link #refresh}) and the
 * manual point adjustments ({@link #adjust}), through the head office.
 */
@Service
@ConditionalOnHeadOfficeOwned(DataDomain.LOYALTY)
@Log4j2
public class StoreLoyaltyNetwork {

	public static final String PROGRAM_AT_HEAD_OFFICE = "The loyalty program is managed by the head office.";
	static final String UNREACHABLE = "The head office cannot be reached: changing a loyalty member needs it."
			+ " Try again later.";
	static final String NOT_IN_REGISTER = "This card is not in the network register (a local card switched off when"
			+ " loyalty moved to the head office).";
	static final String NOT_KNOWN_YET = "The head office does not know this card yet: it is sent within a minute."
			+ " Try again later.";
	static final String ADJUST_UNREACHABLE = "The head office cannot be reached: adjusting points needs it. Try again"
			+ " later.";
	public static final String ENROL_NEEDS_HEAD_OFFICE = "The head office cannot be reached: this store enrols a"
			+ " member only after the head office has checked the phone number. Try again later; the sale can go on"
			+ " without the card.";
	static final String NOT_FRESH_UNREACHABLE = "The head office does not answer: the balance shown is this store's.";
	static final String NOT_FRESH_UNKNOWN = "The head office does not know this card yet: the balance shown is this"
			+ " store's.";
	static final int TIMEOUT_SECONDS = 15;

	private final HeadOfficeClient client;
	private final LoyaltyService loyaltyService;
	private final LoyaltyMemberRepository members;
	private final MemberFunctionRepository functions;
	private final CustomerRepository customers;
	private final LoyaltyCopyWriter writer;
	private final HeadOfficeLinkStatus linkStatus;
	private final TransactionOperations transactions;

	/** Step 5: when each card was last refreshed from the head office. */
	private final LoyaltyFreshness freshness;

	@Autowired
	public StoreLoyaltyNetwork(HeadOfficeClient client, LoyaltyService loyaltyService, LoyaltyMemberRepository members,
			MemberFunctionRepository functions, CustomerRepository customers, LoyaltyCopyWriter writer,
			HeadOfficeLinkStatus linkStatus, LoyaltyFreshness freshness,
			PlatformTransactionManager transactionManager) {
		this(client, loyaltyService, members, functions, customers, writer, linkStatus, freshness,
				timed(transactionManager));
	}

	/** With given transactions: used by the tests. */
	public StoreLoyaltyNetwork(HeadOfficeClient client, LoyaltyService loyaltyService, LoyaltyMemberRepository members,
			MemberFunctionRepository functions, CustomerRepository customers, LoyaltyCopyWriter writer,
			HeadOfficeLinkStatus linkStatus, LoyaltyFreshness freshness, TransactionOperations transactions) {
		this.freshness = freshness;
		this.client = client;
		this.loyaltyService = loyaltyService;
		this.members = members;
		this.functions = functions;
		this.customers = customers;
		this.writer = writer;
		this.linkStatus = linkStatus;
		this.transactions = transactions;
	}

	private static TransactionTemplate timed(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	// ─── Enrol ───────────────────────────────────────────────────

	/**
	 * POST /loyalty/member. IllegalStateException (409) with today's duplicate message when the phone has a card in the
	 * network (head office answer) or here; LoyaltyService's other refusals as today.
	 */
	public LoyaltyMemberDTO enrol(CreateLoyaltyMemberRequestDTO request) {
		String phone = LoyaltyService.normalizePhone(request.getPhone());
		if (phone.matches("\\d{8}")) {
			LiveAnswer<LoyaltyPhoneCheckDTO> answer = client.findLoyaltyMemberByPhone(phone);
			if (answer.isOk() && answer.getBody().isFound() && answer.getBody().getMember() != null) {
				LoyaltyMemberCopyDTO holder = answer.getBody().getMember();
				try {
					transactions.executeWithoutResult(status -> writer.saveMember(holder));
				} catch (RuntimeException e) {
					log.warn("Loyalty: member {} of the network could not be saved here ({})", holder.getCardNumber(),
							SalesCopyFinder.cause(e));
				}
				throw new PhoneTakenException(holder.getCardNumber(), holder.getFirstName(), holder.getLastName(),
						holder.getActive());
			}
			if (!answer.isOk()) {
				String why = answer.isAnswered() ? "HTTP " + answer.getStatus() : answer.getState() + ": " + answer.getMessage();
				// Enrol switch (2026-10-04): this store enrols only with the head office's answer to the phone check
				if (Boolean.TRUE.equals(linkStatus.get().getEnrolRequiresOnline())) {
					log.info("Loyalty: enrol refused, the head office did not answer the phone check ({})", why);
					throw new NetworkException(503, ENROL_NEEDS_HEAD_OFFICE);
				}
				log.info("Loyalty: phone not checked at the head office, checked here only ({})", why);
			}
			// The check of LoyaltyService, here first so the 409 names the card in its fields too
			members.findByPhone(phone).stream().filter(m -> m.getOrigin() == RecordOrigin.HEAD_OFFICE)
					.min(LoyaltyService.PHONE_HOLDER_ORDER).ifPresent(m -> {
						throw new PhoneTakenException(m.getCardNumber(), m.getFirstName(), m.getLastName(),
								m.getActive());
					});
		}
		return loyaltyService.createMember(request);
	}

	/**
	 * Enrol refused: the phone already has a card in the network (head office answer, or a member of the register held
	 * here). Today's 409 message, plus the card for the caller: LoyaltyAPI adds existingCardNumber and
	 * existingCardActive to the body. Thrown only when loyalty is owned by the head office.
	 */
	public static class PhoneTakenException extends IllegalStateException {

		private static final long serialVersionUID = 1L;

		private final String cardNumber;
		private final boolean cardActive;

		public PhoneTakenException(String cardNumber, String firstName, String lastName, Boolean active) {
			super(LoyaltyService.phoneTakenMessage(cardNumber, firstName, lastName, active));
			this.cardNumber = cardNumber;
			this.cardActive = Boolean.TRUE.equals(active);
		}

		public String getCardNumber() {
			return cardNumber;
		}

		public boolean isCardActive() {
			return cardActive;
		}
	}

	// ─── Changes through the head office ─────────────────────────

	/** PUT /loyalty/member/{id}: the edit form, through the head office. */
	public LoyaltyMemberDTO edit(Long id, CreateLoyaltyMemberRequestDTO request) {
		LoyaltyMember member = networkMember(id);
		if (request.getMemberFunctionId() == null) {
			throw new NetworkException(400, "La fonction du membre est obligatoire");
		}
		MemberFunction function = functions.findById(request.getMemberFunctionId()).orElseThrow(
				() -> new NetworkException(400, "Fonction du membre introuvable: " + request.getMemberFunctionId()));
		LoyaltyMemberEditDTO edit = new LoyaltyMemberEditDTO();
		edit.setFirstName(request.getFirstName());
		edit.setLastName(request.getLastName());
		edit.setPhone(request.getPhone());
		edit.setEmail(request.getEmail());
		edit.setBirthDate(parseDate(request.getBirthDate()));
		edit.setMemberFunctionCode(function.getCode());
		edit.setMemberFunctionName(function.getName());
		edit.setCustomerCode(request.getCustomerId() == null ? null
				: customers.findById(request.getCustomerId()).map(Customer::getCustomerCode).orElse(null));
		return send(member, edit);
	}

	/** PUT /loyalty/member/{id}/toggle-active, through the head office. */
	public LoyaltyMemberDTO toggleActive(Long id) {
		LoyaltyMember member = networkMember(id);
		LoyaltyMemberEditDTO edit = asItIs(member);
		edit.setActive(!Boolean.TRUE.equals(member.getActive()));
		return send(member, edit);
	}

	/** PUT /loyalty/member/{id}/link-customer, through the head office. */
	public LoyaltyMemberDTO linkCustomer(Long id, Long customerId) {
		LoyaltyMember member = networkMember(id);
		LoyaltyMemberEditDTO edit = asItIs(member);
		if (customerId == null) {
			edit.setCustomerCode(null);
		} else {
			edit.setCustomerCode(customers.findById(customerId).map(Customer::getCustomerCode)
					.orElseThrow(() -> new NetworkException(400, "Customer not found: " + customerId)));
		}
		return send(member, edit);
	}

	/** GET /loyalty/network: what the pages need to know. */
	public Map<String, Object> status() {
		HeadOfficeLinkStatus.Snapshot snapshot = linkStatus.get();
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("ownedByHeadOffice", true);
		status.put("linkState", snapshot.getState());
		status.put("canEditMembers", snapshot.getCanEditMembers());
		status.put("canAdjustPoints", snapshot.getCanAdjustPoints());
		status.put("programEditable", false);
		status.put("pointsAdjustable", Boolean.TRUE.equals(snapshot.getCanAdjustPoints()));
		status.put("redeemRequiresOnline", snapshot.getRedeemRequiresOnline());
		status.put("enrolRequiresOnline", snapshot.getEnrolRequiresOnline());
		status.put("freshWindowSeconds", LoyaltyFreshness.WINDOW.getSeconds());
		return status;
	}

	// ─── Step 5: fresh balance and adjustments ───────────────────

	/**
	 * GET /loyalty/member/{id}/fresh, called by the POS when a member is selected: the member asked from the head
	 * office with the live timeouts, saved here with the balance rule of the pull, and answered with fresh true; when the
	 * head office does not answer (or does not know the card yet), this store's copy with fresh false and the reason.
	 * Never fails the till: {member, fresh, refreshedAt, message, redeemRequiresOnline, canRedeem}. 400 for an unknown id.
	 */
	public Map<String, Object> refresh(Long id) {
		LoyaltyMember member = members.findById(id)
				.orElseThrow(() -> new NetworkException(400, "Loyalty member not found: " + id));
		LoyaltyMember shown = member;
		LocalDateTime refreshedAt = null;
		String message;
		if (member.getOrigin() != RecordOrigin.HEAD_OFFICE) {
			message = NOT_IN_REGISTER;
		} else {
			LiveAnswer<LoyaltyMemberCopyDTO> answer = client.fetchLoyaltyMember(member.getCardNumber());
			if (answer.isOk()) {
				LoyaltyMemberCopyDTO copy = answer.getBody();
				LoyaltyCopyWriter.Outcome outcome = transactions.execute(status -> writer.saveMember(copy));
				if (outcome != null && outcome.isApplied()) {
					shown = members.findByCardNumber(copy.getCardNumber()).orElse(member);
					refreshedAt = freshness.markFresh(copy.getCardNumber());
					message = null;
				} else {
					message = outcome == null ? "not saved" : outcome.getError();
				}
			} else if (!answer.isAnswered()) {
				message = NOT_FRESH_UNREACHABLE;
			} else if (answer.getStatus() == 404) {
				message = NOT_FRESH_UNKNOWN;
			} else {
				message = "Unexpected answer from the head office: HTTP " + answer.getStatus()
						+ "; the balance shown is this store's.";
			}
		}
		boolean strict = Boolean.TRUE.equals(linkStatus.get().getRedeemRequiresOnline());
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("member", loyaltyService.toMemberDTO(shown));
		answer.put("fresh", refreshedAt != null);
		answer.put("refreshedAt", refreshedAt);
		answer.put("message", message);
		answer.put("redeemRequiresOnline", strict);
		answer.put("canRedeem", !strict || freshness.isFresh(shown.getCardNumber()));
		return answer;
	}

	/**
	 * POST /loyalty/member/{id}/adjust, through the head office (needs canAdjustPoints there): applied there, the new
	 * balance saved here. Nothing is written here as a movement, so nothing is sent twice.
	 */
	public LoyaltyMemberDTO adjust(Long id, int delta, String reason, String adjustedBy) {
		LoyaltyMember member = networkMember(id);
		LiveAnswer<LoyaltyMemberCopyDTO> answer = client.adjustLoyaltyPoints(member.getCardNumber(),
				new LoyaltyPointsAdjustDTO(delta, reason, adjustedBy));
		return save(member, answer, ADJUST_UNREACHABLE, "This store may not adjust points.", "points adjusted");
	}

	private LoyaltyMember networkMember(Long id) {
		LoyaltyMember member = members.findById(id)
				.orElseThrow(() -> new NetworkException(400, "Loyalty member not found: " + id));
		if (member.getOrigin() != RecordOrigin.HEAD_OFFICE) {
			throw new NetworkException(409, NOT_IN_REGISTER);
		}
		return member;
	}

	/** The member as it is here; the function is sent blank (the head office keeps its own). */
	private static LoyaltyMemberEditDTO asItIs(LoyaltyMember member) {
		LoyaltyMemberEditDTO edit = new LoyaltyMemberEditDTO();
		edit.setFirstName(member.getFirstName());
		edit.setLastName(member.getLastName());
		edit.setPhone(member.getPhone());
		edit.setEmail(member.getEmail());
		edit.setBirthDate(member.getBirthDate());
		edit.setCustomerCode(member.getCustomer() == null ? null : member.getCustomer().getCustomerCode());
		return edit;
	}

	/** The change at the head office, then its answer saved here. */
	private LoyaltyMemberDTO send(LoyaltyMember member, LoyaltyMemberEditDTO edit) {
		return save(member, client.editLoyaltyMember(member.getCardNumber(), edit), UNREACHABLE,
				"This store may not change loyalty members.", "changed");
	}

	/** A head office answer to a change: refusals with their status, or the member saved here (and fresh). */
	private LoyaltyMemberDTO save(LoyaltyMember member, LiveAnswer<LoyaltyMemberCopyDTO> answer, String unreachable,
			String forbidden, String what) {
		if (!answer.isAnswered()) {
			throw new NetworkException(503, unreachable);
		}
		if (!answer.isOk()) {
			switch (answer.getStatus()) {
				case 403:
					throw new NetworkException(403, message(answer, forbidden));
				case 404:
					throw new NetworkException(409, NOT_KNOWN_YET);
				case 400:
				case 409:
					throw new NetworkException(answer.getStatus(), message(answer, "Refused by the head office."));
				default:
					throw new NetworkException(502, "Unexpected answer from the head office: HTTP " + answer.getStatus());
			}
		}
		LoyaltyMemberCopyDTO copy = answer.getBody();
		transactions.executeWithoutResult(status -> writer.saveMember(copy));
		freshness.markFresh(copy.getCardNumber());
		LoyaltyMember saved = members.findByCardNumber(copy.getCardNumber()).orElse(member);
		log.info("Loyalty: member {} {} through the head office", copy.getCardNumber(), what);
		return loyaltyService.toMemberDTO(saved);
	}

	private static String message(LiveAnswer<?> answer, String fallback) {
		return answer.getError() == null ? fallback : answer.getError();
	}

	private static LocalDate parseDate(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return null;
		}
		try {
			return LocalDate.parse(raw.trim());
		} catch (DateTimeParseException e) {
			return null; // as LoyaltyService: an unreadable birth date is dropped
		}
	}

	/** A refusal of a member change, with the HTTP status LoyaltyAPI answers. */
	public static class NetworkException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final int status;

		public NetworkException(int status, String message) {
			super(message);
			this.status = status;
		}

		public int getStatus() {
			return status;
		}
	}
}
