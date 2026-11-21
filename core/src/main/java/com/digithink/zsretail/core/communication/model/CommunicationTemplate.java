package com.digithink.zsretail.core.communication.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import org.hibernate.annotations.Nationalized;

import com.digithink.zsretail.core.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 3.3.0, communication module, step 3: a message text for one channel and one language. The body holds {fields} filled
 * from the event (CommunicationTemplates.FIELDS). Active or not: the column active of every entity. See
 * docs/modules/communication.md.
 */
@Entity
@Table(name = "comm_template", uniqueConstraints = @UniqueConstraint(name = "uk_comm_template_code", columnNames = "code"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class CommunicationTemplate extends _BaseEntity {

	@Nationalized
	@Column(name = "code", nullable = false, length = 50)
	private String code;

	@Nationalized
	@Column(name = "name", nullable = false, length = 100)
	private String name;

	/** WHATSAPP, SMS or EMAIL. */
	@Nationalized
	@Column(name = "channel", nullable = false, length = 20)
	private String channel;

	/** ar, fr or en. */
	@Nationalized
	@Column(name = "language_code", nullable = false, length = 5)
	private String languageCode;

	/** Email only. */
	@Nationalized
	@Column(name = "subject", length = 255)
	private String subject;

	@Nationalized
	@Column(name = "body", nullable = false, columnDefinition = "nvarchar(max)")
	private String body;

	/**
	 * WhatsApp only: the name of the template approved at the provider (step 8c: required before a rule or a campaign
	 * uses it). The text above must be the one approved there: Meta's text is what the member receives.
	 */
	@Nationalized
	@Column(name = "provider_template_name", length = 100)
	private String providerTemplateName;

	/** Step 8c, WhatsApp: the language code as Meta knows it (en_US, fr, ar...); null: {@link #languageCode}. */
	@Nationalized
	@Column(name = "provider_language", length = 10)
	private String providerLanguage;

	/**
	 * Step 8c, WhatsApp: how the template's values are named at Meta: POSITIONAL ({{1}}, {{2}}... in the order of first
	 * appearance of our {fields}) or NAMED ({{first_name}} for {firstName}); null: POSITIONAL.
	 */
	@Nationalized
	@Column(name = "provider_parameter_format", length = 10)
	private String providerParameterFormat;

	/**
	 * Step 8c: the provider's last refusal of this template (does not exist in that language, not approved, paused, wrong
	 * number of values): the Templates and Rules tabs show it; cleared by the next message accepted or by a save. Null:
	 * none.
	 */
	@Nationalized
	@Column(name = "provider_problem", length = 1000)
	private String providerProblem;

	@Column(name = "provider_problem_at")
	private java.time.LocalDateTime providerProblemAt;

	/** Step 8c: the language code for the provider. */
	public String providerLanguageOrOwn() {
		return providerLanguage == null || providerLanguage.trim().isEmpty() ? languageCode : providerLanguage.trim();
	}
}
