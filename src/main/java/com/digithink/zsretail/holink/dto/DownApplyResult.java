package com.digithink.zsretail.holink.dto;

import lombok.Getter;
import lombok.ToString;

/**
 * What a copies down handler did with one page, or with its retries (step 3): records saved, records already as
 * received, removals done, records waiting for a missing target (task 3.5), records in error, and the first problem.
 * Filled by the handler; read by the pull.
 */
@Getter
@ToString
public final class DownApplyResult {

	private int applied;
	private int unchanged;
	private int removed;
	private int waiting;
	private int errors;

	/** "&lt;code&gt;: &lt;reason&gt;" of the first record waiting or in error; null when none. */
	private String firstProblem;

	public static DownApplyResult none() {
		return new DownApplyResult();
	}

	public void addApplied() {
		applied++;
	}

	public void addUnchanged() {
		unchanged++;
	}

	public void addRemoved() {
		removed++;
	}

	public void addWaiting(String code, String reason) {
		waiting++;
		problem(code, reason);
	}

	public void addError(String code, String reason) {
		errors++;
		problem(code, reason);
	}

	/** Adds the counts of another result; its first problem is kept when this one has none. */
	public void add(DownApplyResult other) {
		applied += other.applied;
		unchanged += other.unchanged;
		removed += other.removed;
		waiting += other.waiting;
		errors += other.errors;
		if (firstProblem == null) {
			firstProblem = other.firstProblem;
		}
	}

	public int getProblems() {
		return waiting + errors;
	}

	public boolean isEmpty() {
		return applied + unchanged + removed + waiting + errors == 0;
	}

	private void problem(String code, String reason) {
		if (firstProblem == null) {
			firstProblem = code + ": " + reason;
		}
	}
}
