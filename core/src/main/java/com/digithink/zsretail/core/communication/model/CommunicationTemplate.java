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

	/** WhatsApp only (a later step): the name of the template approved at the provider. */
	@Nationalized
	@Column(name = "provider_template_name", length = 100)
	private String providerTemplateName;
}
