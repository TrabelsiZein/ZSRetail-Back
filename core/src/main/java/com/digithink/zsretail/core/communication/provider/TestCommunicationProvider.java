package com.digithink.zsretail.core.communication.provider;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication module, step 3: the provider "test" (the default of every channel). Sends nothing: one INFO line
 * (the address masked, the length of the text) and the answer "accepted" with an id TEST-&lt;message id&gt;-&lt;8
 * characters&gt;. No HTTP client.
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
@Log4j2
public class TestCommunicationProvider implements CommunicationProvider {

	public static final String NAME = "test";

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public SendResult send(CommunicationMessage message) {
		String id = "TEST-" + message.getId() + "-" + UUID.randomUUID().toString().substring(0, 8);
		log.info("Communication: test provider, {} to {} not sent, accepted as {} ({} characters, template {})",
				message.getChannel(), masked(message.getAddress()), id,
				message.getBody() == null ? 0 : message.getBody().length(), message.getTemplateCode());
		return SendResult.accepted(id);
	}

	/** +21699000002 -> +2169****002; an email keeps its first letter and its domain. Null stays null. */
	public static String masked(String address) {
		if (address == null) {
			return null;
		}
		int at = address.indexOf('@');
		if (at > 0) {
			return address.charAt(0) + "***" + address.substring(at);
		}
		if (address.length() <= 7) {
			return "***";
		}
		return address.substring(0, 5) + "****" + address.substring(address.length() - 3);
	}
}
