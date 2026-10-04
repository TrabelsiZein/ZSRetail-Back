package com.digithink.zsretail.holink.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeOwned;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberCopyDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyProgramCopyDTO;
import com.digithink.zsretail.holink.dto.DownApplyResult;
import com.digithink.zsretail.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.holink.model.DownRecord;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Head office plan, step 4: the store side of the LOYALTY domain on the copies down mechanism, when loyalty is owned by
 * the head office. Records PROGRAM:&lt;code&gt; (the active program, consult-only here) and MEMBER:&lt;card&gt; (every
 * member of the network, with the head office balance) are saved by {@link LoyaltyCopyWriter}, each in its own
 * transaction and tracked in hol_down_record (APPLIED, or ERROR with the reason, retried at every cycle). Before the
 * pull, the members and programs made here before the switch are set inactive (kept). Earning in the sale is not
 * changed: it reads and writes these tables as before.
 */
@Component
@ConditionalOnHeadOfficeOwned(DataDomain.LOYALTY)
public class LoyaltyDownHandler implements DownHandler {

	static final int TIMEOUT_SECONDS = 15;
	static final String NO_CODE = "?";

	static final ObjectMapper COPY_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	private final LoyaltyCopyWriter writer;
	private final DownRecordLog records;
	private final TransactionOperations transactions;

	/** Codes applied by the pulls of the current cycle: the retry that follows skips them. ho-link thread only. */
	private final Set<String> appliedThisCycle = new HashSet<>();

	@Autowired
	public LoyaltyDownHandler(LoyaltyCopyWriter writer, DownRecordLog records,
			PlatformTransactionManager transactionManager) {
		this(writer, records, timed(transactionManager));
	}

	/** With given transactions: used by the tests. */
	public LoyaltyDownHandler(LoyaltyCopyWriter writer, DownRecordLog records, TransactionOperations transactions) {
		this.writer = writer;
		this.records = records;
		this.transactions = transactions;
	}

	private static TransactionTemplate timed(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	@Override
	public DataDomain getDomain() {
		return DataDomain.LOYALTY;
	}

	/** Loyalty owned by the head office: the local members and programs are set inactive, kept. */
	@Override
	public int deactivateLocal() {
		Integer count = transactions.execute(status -> writer.deactivateLocal());
		return count == null ? 0 : count;
	}

	@Override
	public DownApplyResult apply(List<JsonNode> page, List<String> removed) {
		DownApplyResult result = DownApplyResult.none();
		for (JsonNode record : page) {
			Copy copy;
			try {
				copy = read(record);
			} catch (Exception e) {
				result.addError(NO_CODE, "unreadable record (" + SalesCopyFinder.cause(e) + ")");
				continue;
			}
			if (copy == null) {
				result.addError(NO_CODE, "record without a kind or a code");
				continue;
			}
			appliedThisCycle.add(copy.code);
			applyAndTrack(copy, record.toString(), result);
		}
		for (String code : removed) {
			try {
				transactions.executeWithoutResult(status -> {
					boolean changed = code.startsWith(LoyaltyMemberCopyDTO.CODE_PREFIX)
							? writer.removeMember(code.substring(LoyaltyMemberCopyDTO.CODE_PREFIX.length()))
							: code.startsWith(LoyaltyProgramCopyDTO.CODE_PREFIX)
									&& writer.removeProgram(code.substring(LoyaltyProgramCopyDTO.CODE_PREFIX.length()));
					boolean tracked = records.remove(DataDomain.LOYALTY, code);
					if (changed || tracked) {
						result.addRemoved();
					}
				});
			} catch (RuntimeException e) {
				result.addError(code, "not removed (" + SalesCopyFinder.cause(e) + ")");
			}
		}
		return result;
	}

	/** Every cycle, the records in ERROR are applied again from the copy last received (e.g. a clash removed since). */
	@Override
	public DownApplyResult retry() {
		DownApplyResult result = DownApplyResult.none();
		try {
			List<DownRecord> rows = transactions.execute(status -> records.toRetry(DataDomain.LOYALTY));
			for (DownRecord row : rows == null ? new ArrayList<DownRecord>() : rows) {
				if (appliedThisCycle.contains(row.getRecordCode())) {
					continue;
				}
				Copy copy;
				try {
					copy = read(COPY_MAPPER.readTree(row.getPayload()));
				} catch (Exception e) {
					copy = null;
				}
				if (copy == null) {
					result.addError(row.getRecordCode(), row.getReason()); // stays as it is until a new copy comes
					continue;
				}
				applyAndTrack(copy, null, result);
			}
		} finally {
			appliedThisCycle.clear();
		}
		return result;
	}

	/** One record in its own transaction with its tracking row; payload null keeps the stored copy (a retry). */
	private void applyAndTrack(Copy copy, String payload, DownApplyResult result) {
		LoyaltyCopyWriter.Outcome outcome;
		try {
			outcome = transactions.execute(status -> {
				LoyaltyCopyWriter.Outcome saved = copy.member != null ? writer.saveMember(copy.member)
						: writer.saveProgram(copy.program);
				records.track(DataDomain.LOYALTY, copy.code, copy.name,
						saved.isApplied() ? DownRecordStatus.APPLIED : DownRecordStatus.ERROR, saved.getError(), null,
						payload);
				return saved;
			});
		} catch (RuntimeException e) {
			String reason = "not saved (" + SalesCopyFinder.cause(e) + ")";
			result.addError(copy.code, reason);
			try {
				transactions.executeWithoutResult(status -> records.track(DataDomain.LOYALTY, copy.code, copy.name,
						DownRecordStatus.ERROR, reason, null, payload));
			} catch (RuntimeException ignored) {
				// the row is written at the next cycle; the error is already counted
			}
			return;
		}
		if (!outcome.isApplied()) {
			result.addError(copy.code, outcome.getError());
		} else if (outcome.isWritten()) {
			result.addApplied();
		} else {
			result.addUnchanged();
		}
	}

	/** The record as a member or a program copy, with its record code and name; null without a kind or a code. */
	static Copy read(JsonNode record) throws Exception {
		JsonNode kind = record == null ? null : record.get("kind");
		if (kind == null) {
			return null;
		}
		if (LoyaltyMemberCopyDTO.KIND.equals(kind.asText())) {
			LoyaltyMemberCopyDTO member = COPY_MAPPER.treeToValue(record, LoyaltyMemberCopyDTO.class);
			if (blank(member.getCardNumber())) {
				return null;
			}
			Copy copy = new Copy(LoyaltyMemberCopyDTO.recordCode(member.getCardNumber()),
					(member.getFirstName() + " " + member.getLastName()).trim());
			copy.member = member;
			return copy;
		}
		if (LoyaltyProgramCopyDTO.KIND.equals(kind.asText())) {
			LoyaltyProgramCopyDTO program = COPY_MAPPER.treeToValue(record, LoyaltyProgramCopyDTO.class);
			if (blank(program.getProgramCode())) {
				return null;
			}
			Copy copy = new Copy(LoyaltyProgramCopyDTO.recordCode(program.getProgramCode()), program.getName());
			copy.program = program;
			return copy;
		}
		return null;
	}

	private static boolean blank(String value) {
		return value == null || value.trim().isEmpty();
	}

	/** One record of the page. */
	static final class Copy {
		final String code;
		final String name;
		LoyaltyMemberCopyDTO member;
		LoyaltyProgramCopyDTO program;

		Copy(String code, String name) {
			this.code = code;
			this.name = name;
		}
	}
}
