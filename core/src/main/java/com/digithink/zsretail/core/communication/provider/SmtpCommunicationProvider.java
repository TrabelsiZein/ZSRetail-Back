package com.digithink.zsretail.core.communication.provider;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import javax.mail.AuthenticationFailedException;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.sun.mail.smtp.SMTPAddressFailedException;
import com.sun.mail.smtp.SMTPSendFailedException;
import com.sun.mail.smtp.SMTPSenderFailedException;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication step 8a: the provider "smtp" of the email channel (communication.email.provider=smtp). Its own
 * mail session (never the application's shared mail bean), with a connect, a read and a write timeout; one connection
 * per message. Plain text in UTF-8 (Arabic stays readable), the subject of the template, the sender address and name of
 * the properties. The answers:
 * <ul>
 * <li>an address that is not a valid email: never sent, FAILED at once with the reason;</li>
 * <li>a 5xx answer of the server (an address or a text refused): FAILED at once with the server's reason;</li>
 * <li>no connection, a timeout, a 4xx answer: a temporary failure, tried again after the waits of the sender (1, 5, 15,
 * 60 min);</li>
 * <li>the login refused (a wrong user or password): a temporary failure too, the installation's setting being wrong,
 * not the message: once corrected, the waiting messages leave.</li>
 * </ul>
 * Without a host or a sender address the provider cannot send: the channel is treated as off, with one WARN line at
 * the start, never a refused start (CommunicationSettings). See docs/modules/communication.md, "Step 8a".
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION)
@Log4j2
public class SmtpCommunicationProvider implements CommunicationProvider {

	public static final String NAME = "smtp";
	public static final String HOST_KEY = "communication.email.smtp.host";
	public static final String PORT_KEY = "communication.email.smtp.port";
	public static final String SECURITY_KEY = "communication.email.smtp.security";
	public static final String USERNAME_KEY = "communication.email.smtp.username";
	public static final String PASSWORD_KEY = "communication.email.smtp.password";
	public static final String TIMEOUT_KEY = "communication.email.smtp.timeout-seconds";
	public static final String FROM_ADDRESS_KEY = "communication.email.from-address";
	public static final String FROM_NAME_KEY = "communication.email.from-name";

	static final int DEFAULT_PORT = 587;
	static final String DEFAULT_SECURITY = "starttls";
	static final int DEFAULT_TIMEOUT_SECONDS = 15;

	private final String host;
	private final int port;
	private final String security;
	private final String username;
	private final String password;
	private final int timeoutSeconds;
	private final String fromAddress;
	private final String fromName;
	private final String unavailable;
	private final Session session;

	@Autowired
	public SmtpCommunicationProvider(Environment environment) {
		this((PropertyResolver) environment);
	}

	public SmtpCommunicationProvider(PropertyResolver env) {
		host = text(env, HOST_KEY);
		username = text(env, USERNAME_KEY);
		password = env.getProperty(PASSWORD_KEY) == null || env.getProperty(PASSWORD_KEY).isEmpty() ? null
				: env.getProperty(PASSWORD_KEY);
		fromAddress = text(env, FROM_ADDRESS_KEY);
		fromName = text(env, FROM_NAME_KEY);
		String problem = null;
		Integer readPort = number(env, PORT_KEY, DEFAULT_PORT, 1, 65535);
		Integer readTimeout = number(env, TIMEOUT_KEY, DEFAULT_TIMEOUT_SECONDS, 1, 300);
		String readSecurity = text(env, SECURITY_KEY) == null ? DEFAULT_SECURITY
				: text(env, SECURITY_KEY).toLowerCase(Locale.ROOT);
		if (host == null) {
			problem = HOST_KEY + " is empty";
		} else if (readPort == null) {
			problem = PORT_KEY + "='" + env.getProperty(PORT_KEY) + "' is not a port (1 to 65535)";
		} else if (readTimeout == null) {
			problem = TIMEOUT_KEY + "='" + env.getProperty(TIMEOUT_KEY) + "' is not 1 to 300 seconds";
		} else if (!("none".equals(readSecurity) || "starttls".equals(readSecurity) || "ssl".equals(readSecurity))) {
			problem = SECURITY_KEY + "='" + env.getProperty(SECURITY_KEY) + "' is not none, starttls or ssl";
		} else if (fromAddress == null || !isEmail(fromAddress)) {
			problem = FROM_ADDRESS_KEY + (fromAddress == null ? " is empty" : "='" + fromAddress + "' is not an email");
		}
		port = readPort == null ? DEFAULT_PORT : readPort;
		timeoutSeconds = readTimeout == null ? DEFAULT_TIMEOUT_SECONDS : readTimeout;
		security = readSecurity;
		unavailable = problem;
		session = Session.getInstance(properties());
	}

	private Properties properties() {
		Properties props = new Properties();
		String ms = String.valueOf(timeoutSeconds * 1000L);
		props.put("mail.transport.protocol", "smtp");
		props.put("mail.smtp.host", host == null ? "" : host);
		props.put("mail.smtp.port", String.valueOf(port));
		props.put("mail.smtp.connectiontimeout", ms);
		props.put("mail.smtp.timeout", ms);
		props.put("mail.smtp.writetimeout", ms);
		props.put("mail.smtp.auth", String.valueOf(username != null));
		if (fromAddress != null) {
			props.put("mail.smtp.from", fromAddress);
			// The Message-ID on the sender's domain (<...@magasin.tn>), not this computer's name (<...@DESKTOP-...>)
			props.put("mail.from", fromAddress);
		}
		if ("starttls".equals(security)) {
			props.put("mail.smtp.starttls.enable", "true");
			props.put("mail.smtp.starttls.required", "true");
		} else if ("ssl".equals(security)) {
			props.put("mail.smtp.ssl.enable", "true");
		}
		return props;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String unavailableReason() {
		return unavailable;
	}

	@Override
	public Map<String, Object> describe() {
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("host", host);
		view.put("port", port);
		view.put("security", security);
		view.put("username", username);
		view.put("password", password == null ? "not set" : "set");
		view.put("timeoutSeconds", timeoutSeconds);
		view.put("fromAddress", fromAddress);
		view.put("fromName", fromName);
		return view;
	}

	/** The sender: "Name <address>", or the address alone. */
	public String sender() {
		return fromAddress == null ? null : (fromName == null ? fromAddress : fromName + " <" + fromAddress + ">");
	}

	@Override
	public SendResult send(CommunicationMessage message) {
		if (unavailable != null) {
			return SendResult.temporaryFailure("smtp cannot send: " + unavailable);
		}
		InternetAddress to;
		try {
			to = new InternetAddress(message.getAddress() == null ? "" : message.getAddress().trim(), true);
			to.validate();
			if (!isEmail(to.getAddress())) {
				throw new AddressException("not an email");
			}
		} catch (AddressException e) {
			return SendResult.permanentFailure("invalid email address '" + message.getAddress() + "': not sent");
		}
		Transport transport = null;
		try {
			MimeMessage mime = new MimeMessage(session);
			mime.setFrom(new InternetAddress(fromAddress, fromName, "UTF-8"));
			mime.setRecipient(Message.RecipientType.TO, to);
			mime.setSubject(message.getSubject() == null ? "" : message.getSubject(), "UTF-8");
			mime.setText(message.getBody() == null ? "" : message.getBody(), "UTF-8");
			mime.setSentDate(new Date());
			mime.saveChanges();
			transport = session.getTransport("smtp");
			transport.connect(host, port, username, password);
			transport.sendMessage(mime, mime.getAllRecipients());
			return SendResult.accepted(mime.getMessageID());
		} catch (AuthenticationFailedException e) {
			return SendResult.temporaryFailure("SMTP login refused (" + host + "): " + reason(e));
		} catch (MessagingException e) {
			return failure(e);
		} catch (UnsupportedEncodingException e) {
			return SendResult.permanentFailure("sender name not encodable: " + e.getMessage());
		} finally {
			if (transport != null) {
				try {
					transport.close();
				} catch (MessagingException ignored) {
					// the message is sent or not already
				}
			}
		}
	}

	/** A failure of the server or of the connection, by its SMTP answer code. */
	static SendResult failure(MessagingException e) {
		Integer code = smtpCode(e);
		String why = reason(e);
		if (code != null) {
			return code >= 500 ? SendResult.permanentFailure("SMTP " + code + ": " + why)
					: SendResult.temporaryFailure("SMTP " + code + ": " + why);
		}
		Throwable cause = rootCause(e);
		if (cause instanceof SocketTimeoutException) {
			return SendResult.temporaryFailure("SMTP timeout: " + why);
		}
		if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
			return SendResult.temporaryFailure("SMTP server unreachable: " + why);
		}
		if (cause instanceof IOException) {
			return SendResult.temporaryFailure("SMTP connection lost: " + why);
		}
		return SendResult.temporaryFailure("SMTP: " + why);
	}

	/** The SMTP answer code of the first refusal in the chain, null when the server did not answer one. */
	static Integer smtpCode(Exception e) {
		Exception current = e;
		int guard = 0;
		while (current != null && guard++ < 10) {
			if (current instanceof SMTPAddressFailedException) {
				return ((SMTPAddressFailedException) current).getReturnCode();
			}
			if (current instanceof SMTPSenderFailedException) {
				return ((SMTPSenderFailedException) current).getReturnCode();
			}
			if (current instanceof SMTPSendFailedException) {
				SMTPSendFailedException failed = (SMTPSendFailedException) current;
				Integer nested = failed.getNextException() instanceof Exception && failed.getNextException() != failed
						? smtpCode(failed.getNextException()) : null;
				return nested != null ? nested : failed.getReturnCode();
			}
			current = current instanceof MessagingException ? ((MessagingException) current).getNextException() : null;
		}
		return null;
	}

	private static Throwable rootCause(Throwable e) {
		Throwable current = e;
		int guard = 0;
		while (guard++ < 10) {
			Throwable next = current instanceof MessagingException && ((MessagingException) current).getNextException() != null
					? ((MessagingException) current).getNextException() : current.getCause();
			if (next == null || next == current) {
				return current;
			}
			current = next;
		}
		return current;
	}

	/** The server's words, one line. */
	private static String reason(Exception e) {
		String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		Throwable root = rootCause(e);
		if (root != e && root.getMessage() != null && !text.contains(root.getMessage())) {
			text = text + " (" + root.getMessage() + ")";
		}
		return text.replaceAll("\\s+", " ").trim();
	}

	/** The email rule of the messages (CommunicationEventProcessor.address). */
	public static boolean isEmail(String value) {
		return value != null && value.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
	}

	private static String text(PropertyResolver env, String key) {
		String raw = env.getProperty(key);
		return raw == null || raw.trim().isEmpty() ? null : raw.trim();
	}

	/** A whole number between min and max; the default when absent or empty; null when not readable. */
	private static Integer number(PropertyResolver env, String key, int defaultValue, int min, int max) {
		String raw = text(env, key);
		if (raw == null) {
			return defaultValue;
		}
		try {
			int value = Integer.parseInt(raw);
			return value >= min && value <= max ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
