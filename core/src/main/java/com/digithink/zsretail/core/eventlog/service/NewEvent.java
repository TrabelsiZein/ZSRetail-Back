package com.digithink.zsretail.core.eventlog.service;

import java.time.LocalDateTime;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.model.UserAccount;

import lombok.Getter;

/**
 * 3.4.0, event log: what a caller says about one event, in one line, e.g.
 * {@code eventLog.write(NewEvent.of(EventCode.PAYMENT_CHANGED).subject(ticket.getId(), ticket.getSalesNumber())
 * .change("CASH", "CARD").amount(12.5))}. Only the code is needed: the importance and the subject type come from the
 * catalogue, the date and the UUID from the server, the user from the logged user when none is given.
 */
@Getter
public final class NewEvent {

	private final EventCode code;
	private String subjectId;
	private String subjectLabel;
	private Long posMachineId;
	private String posMachineName;
	private Long sessionId;
	private String sessionNumber;
	private String cartId;
	private boolean userGiven;
	private Long userId;
	private String userLogin;
	private String userName;
	private String userRole;
	private Long authorisedById;
	private String authorisedByLogin;
	private String authorisedByName;
	private Long badgeOwnerId;
	private String badgeOwnerLogin;
	private String badgeOwnerName;
	private String groupKey;
	private Long sentById;
	private String sentByLogin;
	private String clientAddress;
	private String oldValue;
	private String newValue;
	private Double amount;
	private String details;
	private LocalDateTime frontAt;
	private String frontUuid;
	private Long machineSequenceNo;

	private NewEvent(EventCode code) {
		this.code = code;
	}

	public static NewEvent of(EventCode code) {
		if (code == null) {
			throw new IllegalArgumentException("An event needs its code");
		}
		return new NewEvent(code);
	}

	/** The subject's id (any value, kept as text) and what a person reads for it (a number, a label). */
	public NewEvent subject(Object id, String label) {
		this.subjectId = id == null ? null : String.valueOf(id);
		this.subjectLabel = label;
		return this;
	}

	public NewEvent machine(Long id, String name) {
		this.posMachineId = id;
		this.posMachineName = name;
		return this;
	}

	public NewEvent session(Long id, String number) {
		this.sessionId = id;
		this.sessionNumber = number;
		return this;
	}

	/** The cart's UUID, made by the till at its first line. */
	public NewEvent cart(String uuid) {
		this.cartId = uuid;
		return this;
	}

	/** Who did it, when it is not the logged user (or when nobody is logged: a refused login names who tried). */
	public NewEvent user(UserAccount user) {
		this.userGiven = true;
		if (user != null) {
			this.userId = user.getId();
			this.userLogin = user.getUsername();
			this.userName = user.getFullName();
			this.userRole = user.getAppRole() == null ? null : user.getAppRole().getName();
		}
		return this;
	}

	/** Who did it, as plain values (a login that matches no user: only the login). */
	public NewEvent user(Long id, String login, String name, String role) {
		this.userGiven = true;
		this.userId = id;
		this.userLogin = login;
		this.userName = name;
		this.userRole = role;
		return this;
	}

	/** Who allowed it with a badge (the badge's owner); none when the user's own role allowed it. */
	public NewEvent authorisedBy(UserAccount owner) {
		if (owner != null) {
			authorisedBy(owner.getId(), owner.getUsername(), owner.getFullName());
		}
		return this;
	}

	public NewEvent authorisedBy(Long id, String login, String name) {
		this.authorisedById = id;
		this.authorisedByLogin = login;
		this.authorisedByName = name;
		return this;
	}

	/**
	 * Step 6: the owner of the badge scanned (its use, or its refusal when the badge is known); a refusal authorised
	 * nothing, so this is not authorisedBy.
	 */
	public NewEvent badgeOwner(UserAccount owner) {
		if (owner != null) {
			this.badgeOwnerId = owner.getId();
			this.badgeOwnerLogin = owner.getUsername();
			this.badgeOwnerName = owner.getFullName();
		}
		return this;
	}

	/** Step 6: the refused logins of one run share it (the page shows them as one line). */
	public NewEvent group(String key) {
		this.groupKey = key;
		return this;
	}

	/** The till's events: the user whose login sent them. */
	public NewEvent sentBy(Long id, String login) {
		this.sentById = id;
		this.sentByLogin = login;
		return this;
	}

	/** The address the request came from. */
	public NewEvent from(String address) {
		this.clientAddress = address;
		return this;
	}

	/** The value before and after (any value, kept as text). */
	public NewEvent change(Object before, Object after) {
		this.oldValue = before == null ? null : String.valueOf(before);
		this.newValue = after == null ? null : String.valueOf(after);
		return this;
	}

	public NewEvent amount(Double amount) {
		this.amount = amount;
		return this;
	}

	public NewEvent details(String details) {
		this.details = details;
		return this;
	}

	/** The till's events: its clock, its UUID of the event and its own number of the event. */
	public NewEvent fromTill(LocalDateTime frontAt, String frontUuid, Long machineSequenceNo) {
		this.frontAt = frontAt;
		this.frontUuid = frontUuid;
		this.machineSequenceNo = machineSequenceNo;
		return this;
	}
}
