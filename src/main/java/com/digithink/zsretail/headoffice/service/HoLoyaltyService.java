package com.digithink.zsretail.headoffice.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberCopyDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyProgramCopyDTO;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.LoyaltyProgram;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.repository.LoyaltyProgramRepository;
import com.digithink.zsretail.service.LoyaltyCardNumbers;
import com.digithink.zsretail.service.LoyaltyNetworkHooks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Head office plan, step 4: the loyalty register of a head office on the copies down mechanism. The head office holds
 * the program, the members and the ledger in the existing loyalty tables and pages; every store receives the active
 * program with its tiers (record PROGRAM:&lt;code&gt;) and every member with the head office balance (record
 * MEMBER:&lt;card&gt;). Every change of a member or a program made through LoyaltyService (pages) or by the stores
 * (HoLoyaltyReceiver) is recorded for every store. Cards created at the head office are numbered LYL-HO-000001. Head
 * office only. See docs/modules/head-office.md, "Shared loyalty".
 */
@Service
@ConditionalOnHeadOffice
public class HoLoyaltyService implements DownDomainProvider, LoyaltyNetworkHooks {

	/** The copies: dates as ISO strings (2026-10-03, 2026-10-03T10:15:30). */
	public static final ObjectMapper COPY_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	static final String CARD_PREFIX = LoyaltyCardNumbers.prefixOf(LoyaltyCardNumbers.HEAD_OFFICE_CODE);

	private final LoyaltyMemberRepository members;
	private final LoyaltyProgramRepository programs;

	/** Looked up at the call: the feed itself collects the providers, this one included. */
	private final Supplier<CopiesDownFeed> feed;

	@Autowired
	public HoLoyaltyService(LoyaltyMemberRepository members, LoyaltyProgramRepository programs,
			ObjectProvider<CopiesDownFeed> feed) {
		this(members, programs, (Supplier<CopiesDownFeed>) feed::getObject);
	}

	/** With given collaborators: used by the tests. */
	public HoLoyaltyService(LoyaltyMemberRepository members, LoyaltyProgramRepository programs,
			Supplier<CopiesDownFeed> feed) {
		this.members = members;
		this.programs = programs;
		this.feed = feed;
	}

	// ─── Copies down ─────────────────────────────────────────────

	@Override
	public DataDomain getDomain() {
		return DataDomain.LOYALTY;
	}

	/** The active program and the members asked for; any other code (a closed program, an unknown card) is removed. */
	@Override
	public Map<String, JsonNode> load(Store store, List<String> codes) {
		Map<String, JsonNode> copies = new LinkedHashMap<>();
		List<String> cards = new ArrayList<>();
		for (String code : codes) {
			if (code.startsWith(LoyaltyProgramCopyDTO.CODE_PREFIX)) {
				programs.findByProgramCode(code.substring(LoyaltyProgramCopyDTO.CODE_PREFIX.length()))
						.filter(program -> Boolean.TRUE.equals(program.getActive()))
						.ifPresent(program -> copies.put(code, COPY_MAPPER.valueToTree(LoyaltyProgramCopyDTO.of(program))));
			} else if (code.startsWith(LoyaltyMemberCopyDTO.CODE_PREFIX)) {
				cards.add(code.substring(LoyaltyMemberCopyDTO.CODE_PREFIX.length()));
			}
		}
		if (!cards.isEmpty()) {
			for (LoyaltyMember member : members.findByCardNumberIn(cards)) {
				copies.put(LoyaltyMemberCopyDTO.recordCode(member.getCardNumber()),
						COPY_MAPPER.valueToTree(LoyaltyMemberCopyDTO.of(member)));
			}
		}
		// Answered in the order asked: the feed reads the map by code
		return copies;
	}

	/** The active programs and every member, each for every store. */
	@Override
	public Map<String, StoreTargets> currentTargets() {
		Map<String, StoreTargets> result = new LinkedHashMap<>();
		for (LoyaltyProgram program : programs.findByActiveTrue()) {
			result.put(LoyaltyProgramCopyDTO.recordCode(program.getProgramCode()), StoreTargets.all());
		}
		for (LoyaltyMember member : members.findAll()) {
			result.put(LoyaltyMemberCopyDTO.recordCode(member.getCardNumber()), StoreTargets.all());
		}
		return result;
	}

	/** A member changed (pages, a store's movement or edit): every store gets it at its next pull. */
	public void recordMember(String cardNumber) {
		feed.get().recordChange(DataDomain.LOYALTY, LoyaltyMemberCopyDTO.recordCode(cardNumber), StoreTargets.all());
	}

	// ─── Hooks of LoyaltyService ─────────────────────────────────

	/** LYL-HO-000001: the head office's own sequence. */
	@Override
	public String nextCardNumber() {
		return LoyaltyCardNumbers.next(CARD_PREFIX,
				members.findCardNumbersLike(LoyaltyCardNumbers.likePattern(CARD_PREFIX)));
	}

	/** The head office register is the network's: every member counts. */
	@Override
	public boolean holdsPhone(LoyaltyMember member) {
		return true;
	}

	@Override
	public void beforeMemberCreated(LoyaltyMember member) {
		// nothing: the head office owns its members
	}

	@Override
	public void afterMemberSaved(LoyaltyMember member, boolean created) {
		recordMember(member.getCardNumber());
	}

	@Override
	public void afterProgramSaved(LoyaltyProgram program) {
		recordProgram(program);
	}

	/** The stores get the code as removed (a deleted program is not found). */
	@Override
	public void beforeProgramDeleted(LoyaltyProgram program) {
		recordProgram(program);
	}

	private void recordProgram(LoyaltyProgram program) {
		feed.get().recordChange(DataDomain.LOYALTY, LoyaltyProgramCopyDTO.recordCode(program.getProgramCode()),
				StoreTargets.all());
	}
}
