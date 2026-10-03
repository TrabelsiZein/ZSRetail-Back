package com.digithink.zsretail.holink.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeOwned;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberCopyDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberResultDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyProgramCopyDTO;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.model.LoyaltyMemberCopy;
import com.digithink.zsretail.holink.repository.LoyaltyMemberCopyRepository;
import com.digithink.zsretail.holink.repository.LoyaltyMovementCopyRepository;
import com.digithink.zsretail.model.LoyaltyEarningTier;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.LoyaltyProgram;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.LoyaltyTransactionType;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.CustomerRepository;
import com.digithink.zsretail.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.repository.LoyaltyProgramRepository;
import com.digithink.zsretail.repository.MemberFunctionRepository;
import com.digithink.zsretail.service.LoyaltyLedger;
import com.digithink.zsretail.service.MemberFunctionCodes;

/**
 * Head office plan, step 4: writes into this store's loyalty tables what the head office sends (copies down, answers of
 * the live questions, merge answers). Every method runs inside the caller's transaction.
 * <p>
 * <b>The balance shown at the store never goes backwards because of the order of sync.</b> A member's balance and
 * totals here are those of the head office, plus this store's movements the head office has not applied yet: the
 * loyalty_transaction rows of the member (and of a card of this store merged into it) without a SENT row in
 * hol_loyalty_movement_copy, replayed in order with {@link LoyaltyLedger} (never below zero). The pull and the push run
 * one after the other on the ho-link thread, and a movement is marked SENT only after the head office applied it, so a
 * copy received after that already contains it: a movement is counted once, by the head office or as not applied yet.
 * Between two pulls the balance is the one the sale wrote (the push does not touch it).
 */
@Component
@ConditionalOnHeadOfficeOwned(DataDomain.LOYALTY)
public class LoyaltyCopyWriter {

	static final String HEAD_OFFICE_USER = "HEAD_OFFICE";
	static final String LOCAL_CARD = "card number already used by a local member of this store";
	static final String LOCAL_PROGRAM = "program code already used by a local program of this store";

	private final LoyaltyMemberRepository members;
	private final LoyaltyProgramRepository programs;
	private final MemberFunctionRepository functions;
	private final CustomerRepository customers;
	private final LoyaltyMemberCopyRepository memberCopies;
	private final LoyaltyMovementCopyRepository movementCopies;

	public LoyaltyCopyWriter(LoyaltyMemberRepository members, LoyaltyProgramRepository programs,
			MemberFunctionRepository functions, CustomerRepository customers, LoyaltyMemberCopyRepository memberCopies,
			LoyaltyMovementCopyRepository movementCopies) {
		this.members = members;
		this.programs = programs;
		this.functions = functions;
		this.customers = customers;
		this.memberCopies = memberCopies;
		this.movementCopies = movementCopies;
	}

	// ─── Members ─────────────────────────────────────────────────

	/**
	 * Saves a member of the register by its card: created (origin HEAD_OFFICE) or updated, its balance as described
	 * above. A local member (made before the switch) with that card is not touched: ERROR.
	 */
	public Outcome saveMember(LoyaltyMemberCopyDTO copy) {
		Optional<LoyaltyMember> found = members.findByCardNumber(copy.getCardNumber());
		if (found.isPresent() && found.get().getOrigin() != RecordOrigin.HEAD_OFFICE) {
			return Outcome.error(LOCAL_CARD);
		}
		LoyaltyMember member = found.orElseGet(() -> {
			LoyaltyMember created = new LoyaltyMember();
			created.setCardNumber(copy.getCardNumber());
			created.setOrigin(RecordOrigin.HEAD_OFFICE);
			created.setCreatedBy(HEAD_OFFICE_USER);
			if (copy.getEnrolledAt() != null) {
				created.setCreatedAt(copy.getEnrolledAt());
			}
			return created;
		});
		List<Object> before = found.isPresent() ? signature(member) : null;
		member.setFirstName(copy.getFirstName());
		member.setLastName(copy.getLastName());
		member.setPhone(copy.getPhone());
		member.setEmail(copy.getEmail());
		member.setBirthDate(copy.getBirthDate());
		if (copy.getMemberFunctionCode() != null) {
			member.setMemberFunction(MemberFunctionCodes.resolve(functions, copy.getMemberFunctionCode(),
					copy.getMemberFunctionName()));
		}
		member.setCustomer(copy.getCustomerCode() == null ? null
				: customers.findByCustomerCode(copy.getCustomerCode()).orElse(null));
		member.setActive(copy.getActive() == null ? Boolean.TRUE : copy.getActive());
		LoyaltyLedger.State state = balance(copy, member.getId());
		member.setLoyaltyPoints(state.getBalance());
		member.setTotalPointsEarned(state.getEarned());
		member.setTotalPointsRedeemed(state.getRedeemed());
		if (before != null && before.equals(signature(member))) {
			return Outcome.unchanged();
		}
		member.setUpdatedBy(HEAD_OFFICE_USER);
		members.save(member);
		return Outcome.written();
	}

	/**
	 * The head office balance and totals of the copy plus this store's movements not applied there yet: those of the
	 * member (memberId, null when it is new here) and of the cards of this store merged into it.
	 */
	LoyaltyLedger.State balance(LoyaltyMemberCopyDTO copy, Long memberId) {
		LoyaltyLedger.State state = new LoyaltyLedger.State(copy.getLoyaltyPoints(), copy.getTotalPointsEarned(),
				copy.getTotalPointsRedeemed());
		List<Long> ids = new ArrayList<>();
		if (memberId != null) {
			ids.add(memberId);
		}
		for (LoyaltyMemberCopy merged : memberCopies.findBySurvivingCardNumber(copy.getCardNumber())) {
			ids.add(merged.getMemberId());
		}
		if (ids.isEmpty()) {
			return state;
		}
		for (Object[] row : movementCopies.findNotApplied(ids, SalesCopyStatus.SENT)) {
			LoyaltyTransactionType type = (LoyaltyTransactionType) row[1];
			int points = row[2] == null ? 0 : ((Number) row[2]).intValue();
			int delta = LoyaltyLedger.deltaOf(type, points, (Integer) row[3], (Integer) row[4]);
			LoyaltyLedger.apply(state, type, delta, row[5] != null);
		}
		return state;
	}

	/** A member removed for this store (never sent by the head office today): set inactive, kept. */
	public boolean removeMember(String cardNumber) {
		Optional<LoyaltyMember> found = members.findByCardNumber(cardNumber);
		if (!found.isPresent() || found.get().getOrigin() != RecordOrigin.HEAD_OFFICE
				|| !Boolean.TRUE.equals(found.get().getActive())) {
			return false;
		}
		found.get().setActive(false);
		found.get().setUpdatedBy(HEAD_OFFICE_USER);
		members.save(found.get());
		return true;
	}

	/**
	 * A card of this store merged at the head office into the card that has the phone: the local duplicate is set
	 * inactive, the surviving member saved (its balance counts the duplicate's movements not applied yet, which the head
	 * office applies to it as they arrive).
	 */
	public void merge(LoyaltyMemberCopy row, LoyaltyMemberResultDTO result) {
		row.setOutcome(LoyaltyMemberResultDTO.MERGED);
		row.setSurvivingCardNumber(result.getSurvivingCardNumber());
		memberCopies.save(row);
		members.findById(row.getMemberId()).ifPresent(duplicate -> {
			if (Boolean.TRUE.equals(duplicate.getActive())) {
				duplicate.setActive(false);
				duplicate.setUpdatedBy(HEAD_OFFICE_USER);
				members.save(duplicate);
			}
		});
		if (result.getMember() != null && result.getMember().getCardNumber() != null) {
			saveMember(result.getMember());
		}
	}

	// ─── Program ─────────────────────────────────────────────────

	/**
	 * Saves the head office program by its code (origin HEAD_OFFICE) and, when it is active, sets every other active
	 * program inactive: the store applies one program, the head office's. A local program with that code: ERROR.
	 */
	public Outcome saveProgram(LoyaltyProgramCopyDTO copy) {
		Optional<LoyaltyProgram> found = programs.findByProgramCode(copy.getProgramCode());
		if (found.isPresent() && found.get().getOrigin() != RecordOrigin.HEAD_OFFICE) {
			return Outcome.error(LOCAL_PROGRAM);
		}
		LoyaltyProgram program = found.orElseGet(() -> {
			LoyaltyProgram created = new LoyaltyProgram();
			created.setProgramCode(copy.getProgramCode());
			created.setOrigin(RecordOrigin.HEAD_OFFICE);
			created.setCreatedBy(HEAD_OFFICE_USER);
			return created;
		});
		List<Object> before = found.isPresent() ? signature(program) : null;
		program.setName(copy.getName() == null ? copy.getProgramCode() : copy.getName());
		program.setDescription(copy.getDescription());
		program.setStartDate(copy.getStartDate() == null ? LocalDate.now() : copy.getStartDate());
		program.setEndDate(copy.getEndDate());
		program.setPointsPerDinar(copy.getPointsPerDinar() == null ? 0.0 : copy.getPointsPerDinar());
		program.setPointValueMillimes(copy.getPointValueMillimes() == null ? 0 : copy.getPointValueMillimes());
		program.setMinimumRedemptionPoints(
				copy.getMinimumRedemptionPoints() == null ? 0 : copy.getMinimumRedemptionPoints());
		program.setMaximumRedemptionPercentage(
				copy.getMaximumRedemptionPercentage() == null ? 0.0 : copy.getMaximumRedemptionPercentage());
		program.setPointsExpiryDays(copy.getPointsExpiryDays());
		List<LoyaltyEarningTier> tiers = new ArrayList<>();
		if (copy.getEarningTiers() != null) {
			for (LoyaltyProgramCopyDTO.Tier tier : copy.getEarningTiers()) {
				tiers.add(new LoyaltyEarningTier(tier.getThresholdAmount(), tier.getPointsPerDinar()));
			}
		}
		if (program.getEarningTiers() == null) {
			program.setEarningTiers(tiers);
		} else if (!program.getEarningTiers().equals(tiers)) {
			program.getEarningTiers().clear();
			program.getEarningTiers().addAll(tiers);
		}
		program.setActive(copy.getActive() == null ? Boolean.TRUE : copy.getActive());
		boolean written = false;
		if (before == null || !before.equals(signature(program))) {
			program.setUpdatedBy(HEAD_OFFICE_USER);
			program = programs.save(program);
			written = true;
		}
		if (Boolean.TRUE.equals(program.getActive())) {
			for (LoyaltyProgram other : programs.findByActiveTrue()) {
				if (!other.getId().equals(program.getId())) {
					other.setActive(false);
					other.setUpdatedBy(HEAD_OFFICE_USER);
					programs.save(other);
					written = true;
				}
			}
		}
		return written ? Outcome.written() : Outcome.unchanged();
	}

	/** The head office program closed or deleted: set inactive here, kept (its transactions point to it). */
	public boolean removeProgram(String programCode) {
		Optional<LoyaltyProgram> found = programs.findByProgramCode(programCode);
		if (!found.isPresent() || found.get().getOrigin() != RecordOrigin.HEAD_OFFICE
				|| !Boolean.TRUE.equals(found.get().getActive())) {
			return false;
		}
		found.get().setActive(false);
		found.get().setUpdatedBy(HEAD_OFFICE_USER);
		programs.save(found.get());
		return true;
	}

	// ─── Local records ───────────────────────────────────────────

	/**
	 * The active members and programs made at this store before its loyalty was owned by the head office (origin null or
	 * LOCAL) are set inactive, kept; returns how many. Once done there are none: members created here since are of the
	 * register.
	 */
	public int deactivateLocal() {
		int count = 0;
		for (LoyaltyMember member : members.findActiveNotFrom(RecordOrigin.HEAD_OFFICE)) {
			member.setActive(false);
			member.setUpdatedBy(HEAD_OFFICE_USER);
			members.save(member);
			count++;
		}
		for (LoyaltyProgram program : programs.findActiveNotFrom(RecordOrigin.HEAD_OFFICE)) {
			program.setActive(false);
			program.setUpdatedBy(HEAD_OFFICE_USER);
			programs.save(program);
			count++;
		}
		return count;
	}

	private static List<Object> signature(LoyaltyMember m) {
		return Arrays.asList(m.getFirstName(), m.getLastName(), m.getPhone(), m.getEmail(), m.getBirthDate(),
				m.getMemberFunction() == null ? null : m.getMemberFunction().getId(),
				m.getCustomer() == null ? null : m.getCustomer().getId(), m.getActive(), m.getLoyaltyPoints(),
				m.getTotalPointsEarned(), m.getTotalPointsRedeemed());
	}

	private static List<Object> signature(LoyaltyProgram p) {
		return Arrays.asList(p.getName(), p.getDescription(), p.getStartDate(), p.getEndDate(), p.getPointsPerDinar(),
				p.getPointValueMillimes(), p.getMinimumRedemptionPoints(), p.getMaximumRedemptionPercentage(),
				p.getPointsExpiryDays(), p.getEarningTiers() == null ? null : new ArrayList<>(p.getEarningTiers()),
				p.getActive());
	}

	/** What saving one copy gave. */
	public static final class Outcome {

		private final boolean written;
		private final String error;

		private Outcome(boolean written, String error) {
			this.written = written;
			this.error = error;
		}

		static Outcome written() {
			return new Outcome(true, null);
		}

		static Outcome unchanged() {
			return new Outcome(false, null);
		}

		static Outcome error(String reason) {
			return new Outcome(false, reason);
		}

		public boolean isWritten() {
			return written;
		}

		/** Why the copy was not applied; null when applied. */
		public String getError() {
			return error;
		}

		public boolean isApplied() {
			return error == null;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Outcome && ((Outcome) other).written == written
					&& Objects.equals(((Outcome) other).error, error);
		}

		@Override
		public int hashCode() {
			return Objects.hash(written, error);
		}
	}
}
