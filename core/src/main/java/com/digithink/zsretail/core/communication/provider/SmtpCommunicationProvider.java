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
 * mail session (never the application's shared mail bean), with a connect, a read and a write timeout. Step 8a part 2:
 * one connection per cycle ({@link SmtpBatch}: opened at the first email, closed at the end of the cycle; the Canaux
 * tab's test has its own); the server's name checked against its TLS certificate; the EHLO name and the Message-ID on
 * the sender's domain. Plain text in UTF-8 (Arabic stays readable), the subject of the template, the sender address and
 * name of the properties. The answers:
 * <ul>
 * <li>an address that is not a valid email: never sent, FAILED at once with the reason;</li>
 * <li>the channel's problems, the same for every email (step 8a part 2: the channel pauses, the emails keep their
 * attempts): the connection not opened (no server, a timeout, TLS, the login refused), the sender refused, a 421 on a
 * connection that sent nothing yet, the server congested (x.4.5, e.g. a daily quota);</li>
 * <li>a 5xx answer of the server about the email (an address or a text refused): FAILED at once with the server's
 * reason;</li>
 * <li>a 4xx answer, a timeout or a connection lost while an email is sent, before its final ".": a temporary failure,
 * tried again after the waits of the sender (1, 5, 15, 60 min);</li>
 * <li>step 8b: no answer after the email's final "." (a timeout, the connection cut): uncertain, the server may have
 * taken it: FAILED "no answer from the provider", sent again by hand only, the channel paused.</li>
 * </ul>
 * Email only (step 8b). Without a host or a sender address the provider cannot send: the channel is treated as off,
 * with one WARN line at the start, never a refused start (CommunicationSettings). See docs/modules/communication.md,
 * "Step 8a", "Step 8a, part 2" and "Step 8b".
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
			// Step 8a part 2: the EHLO name too (the servers write it in the Received headers the members can read)
			props.put("mail.smtp.localhost", ehloName(fromAddress));
		}
		// Step 8a part 2: the server's name checked against its TLS certificate (JavaMail 1.6 does not by default)
		props.put("mail.smtp.ssl.checkserveridentity", "true");
		// Step 8a part 2: after a refused email the connection is checked with RSET (a NOOP otherwise), cleaner after a
		// refused transaction; the batch then keeps it or opens a new one
		props.put("mail.smtp.userset", "true");
		if ("starttls".equals(security)) {
			props.put("mail.smtp.starttls.enable", "true");
			props.put("mail.smtp.starttls.required", "true");
		} else if ("ssl".equals(security)) {
			props.put("mail.smtp.ssl.enable", "true");
		}
		return props;
	}

	/** The sender address's domain, the name this installation gives itself in EHLO. */
	static String ehloName(String fromAddress) {
		int at = fromAddress.lastIndexOf('@');
		return at >= 0 && at < fromAddress.length() - 1 ? fromAddress.substring(at + 1).trim() : fromAddress.trim();
	}

	/** Step 8a part 2: the mail session's settings (the tests). */
	Properties sessionProperties() {
		return session.getProperties();
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String unavailableReason() {
		return unavailable;
	}

	/** Step 8b: email only. */
	@Override
	public boolean serves(String channel) {
		return com.digithink.zsretail.core.communication.CommunicationTypes.EMAIL.equals(channel);
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
	@Override
	public String sender() {
		return fromAddress == null ? null : (fromName == null ? fromAddress : fromName + " <" + fromAddress + ">");
	}

	/** One message alone, on its own connection (the Canaux tab's test). */
	@Override
	public SendResult send(CommunicationMessage message) {
		try (SmtpBatch batch = new SmtpBatch()) {
			return batch.send(message);
		}
	}

	/** Step 8a part 2: the cycle's emails on one connection, opened at the first one, closed at the end. */
	@Override
	public SmtpBatch openBatch() {
		return new SmtpBatch();
	}

	/**
	 * Step 8a part 2: one connection for the emails of a cycle. Opened at the first email (an invalid address needs none);
	 * not opened (no server, a timeout, TLS, the login refused): a channel problem. After a failed email the connection
	 * is checked (RSET): closed by the server or lost, the next email opens a new one. One thread.
	 */
	public final class SmtpBatch implements Batch {

		private MarkedTransport transport;
		/** The emails accepted on the connection open now. */
		private int acceptedOnConnection;
		private int connections;

		/** How many connections this batch opened (the tests, the throughput measure). */
		public int connections() {
			return connections;
		}

		@Override
		public SendResult send(CommunicationMessage message) {
			if (unavailable != null) {
				return SendResult.channelProblem("smtp cannot send: " + unavailable);
			}
			InternetAddress to = recipient(message.getAddress());
			if (to == null) {
				return SendResult.permanentFailure("invalid email address '" + message.getAddress() + "': not sent");
			}
			MimeMessage mime;
			try {
				mime = new MimeMessage(session);
				mime.setFrom(new InternetAddress(fromAddress, fromName, "UTF-8"));
				mime.setRecipient(Message.RecipientType.TO, to);
				mime.setSubject(message.getSubject() == null ? "" : message.getSubject(), "UTF-8");
				mime.setText(message.getBody() == null ? "" : message.getBody(), "UTF-8");
				mime.setSentDate(new Date());
				mime.saveChanges();
			} catch (UnsupportedEncodingException e) {
				return SendResult.permanentFailure("sender name not encodable: " + e.getMessage());
			} catch (MessagingException e) {
				return SendResult.permanentFailure("the email could not be made: " + reason(e));
			}
			if (transport == null) {
				SendResult notOpen = open();
				if (notOpen != null) {
					return notOpen;
				}
			}
			try {
				transport.dataEnded = false;
				transport.sendMessage(mime, mime.getAllRecipients());
				acceptedOnConnection++;
				return SendResult.accepted(mime.getMessageID());
			} catch (MessagingException e) {
				// Step 8b: the final "." left and no answer came: the server may have taken it (never sent again by itself)
				SendResult failed = transport.dataEnded && noAnswer(e)
						? SendResult.uncertain("SMTP: no answer after the email was sent (" + reason(e) + ")")
						: failure(e, acceptedOnConnection > 0);
				if (!transport.isConnected()) { // RSET (mail.smtp.userset): closed by the server or lost
					drop();
				}
				return failed;
			}
		}

		/** The connection opened and logged in; null when done, the channel problem otherwise. */
		private SendResult open() {
			MarkedTransport opening = null;
			try {
				opening = new MarkedTransport(session, new javax.mail.URLName("smtp", host, port, null, username, null));
				connections++;
				opening.connect(host, port, username, password);
				transport = opening;
				acceptedOnConnection = 0;
				return null;
			} catch (AuthenticationFailedException e) {
				quietlyClose(opening);
				return SendResult.channelProblem("SMTP login refused (" + host + "): " + reason(e));
			} catch (MessagingException e) {
				quietlyClose(opening);
				return SendResult.channelProblem(openFailure(e));
			}
		}

		private void drop() {
			quietlyClose(transport);
			transport = null;
		}

		@Override
		public void close() {
			drop();
		}
	}

	private static void quietlyClose(Transport transport) {
		if (transport != null) {
			try {
				transport.close();
			} catch (MessagingException ignored) {
				// the emails are sent or not already
			}
		}
	}

	/** The member's address as an email, null when it is not one. */
	private static InternetAddress recipient(String address) {
		try {
			InternetAddress to = new InternetAddress(address == null ? "" : address.trim(), true);
			to.validate();
			return isEmail(to.getAddress()) ? to : null;
		} catch (AddressException e) {
			return null;
		}
	}

	/**
	 * Step 8b: the SMTP transport, marking when an email's data ended (its final "." sent): a failure without an answer
	 * after it is uncertain (the server may have taken the email), before it the email was not taken.
	 */
	static final class MarkedTransport extends com.sun.mail.smtp.SMTPTransport {

		/** Set when the final "." of the email being sent is written. */
		volatile boolean dataEnded;

		MarkedTransport(Session session, javax.mail.URLName url) {
			super(session, url);
		}

		@Override
		protected void finishData() throws IOException, MessagingException {
			dataEnded = true;
			super.finishData();
		}
	}

	/** Step 8b: the server gave no answer (a timeout, the connection cut, JavaMail's -1 "[EOF]"), not a refusal. */
	static boolean noAnswer(MessagingException e) {
		Integer code = smtpCode(e);
		if (code != null && code >= 100) {
			return false;
		}
		Throwable cause = rootCause(e);
		return code != null || cause instanceof IOException
				|| String.valueOf(e.getMessage()).startsWith("Exception reading response");
	}

	/** Why the connection could not be opened (every next email would meet it too): a channel problem's text. */
	static String openFailure(MessagingException e) {
		String why = reason(e);
		if (tls(e)) {
			return "SMTP TLS failed: " + why;
		}
		Throwable cause = rootCause(e);
		if (cause instanceof SocketTimeoutException) {
			return "SMTP timeout: " + why;
		}
		if (cause instanceof ConnectException || cause instanceof UnknownHostException
				|| cause instanceof java.net.NoRouteToHostException) {
			return "SMTP server unreachable: " + why;
		}
		if (cause instanceof IOException) {
			return "SMTP connection lost: " + why;
		}
		Integer code = greetingCode(why);
		return code == null ? "SMTP: " + why : "SMTP " + code + ": " + why;
	}

	/** The code of a refused greeting ("... response: 421"), null when there is none. */
	private static Integer greetingCode(String why) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("response: (\\d{3})").matcher(why);
		return m.find() ? Integer.valueOf(m.group(1)) : null;
	}

	/** A TLS failure in the chain: a handshake refused, a certificate not trusted or not of this host, no STARTTLS. */
	private static boolean tls(Throwable e) {
		Throwable current = e;
		int guard = 0;
		while (current != null && guard++ < 10) {
			String text = current.getMessage() == null ? "" : current.getMessage();
			if (current instanceof javax.net.ssl.SSLException || text.contains("STARTTLS")
					|| text.contains("convert socket to TLS") || text.startsWith("Can't verify identity of server")) {
				return true;
			}
			Throwable next = current instanceof MessagingException && ((MessagingException) current).getNextException() != null
					? ((MessagingException) current).getNextException() : current.getCause();
			current = next == current ? null : next;
		}
		return false;
	}

	/**
	 * An email refused or lost on an open connection. Step 8a part 2, the channel's problems (every next email would get
	 * the same answer): the sender refused (MAIL FROM, any code), a 421 on a connection that has sent nothing yet, an
	 * answer with the enhanced code x.4.5 (mail system congestion, e.g. a daily quota reached). Otherwise the email's:
	 * 5xx FAILED at once, 4xx tried again; a 421 after emails accepted on the connection, a timeout or a connection lost:
	 * tried again (a new connection for the next email).
	 */
	static SendResult failure(MessagingException e, boolean connectionUsed) {
		Integer code = smtpCode(e);
		String why = reason(e);
		if (code != null && code < 100) {
			// no answer at all (JavaMail's -1, "[EOF]"): the connection lost before the server accepted the email
			return SendResult.temporaryFailure("SMTP connection lost: " + why);
		}
		if (code != null && code == 421) {
			return connectionUsed ? SendResult.temporaryFailure("SMTP 421: " + why)
					: SendResult.channelProblem("SMTP 421: " + why);
		}
		if (senderRefused(e)) {
			return SendResult.channelProblem("SMTP sender refused" + (code == null ? "" : " (" + code + ")") + ": " + why);
		}
		if (code != null && congestion(why)) {
			return SendResult.channelProblem("SMTP " + code + ": " + why);
		}
		if (code != null) {
			return code >= 500 ? SendResult.permanentFailure("SMTP " + code + ": " + why)
					: SendResult.temporaryFailure("SMTP " + code + ": " + why);
		}
		Throwable cause = rootCause(e);
		if (cause instanceof SocketTimeoutException) {
			return SendResult.temporaryFailure("SMTP timeout: " + why);
		}
		if (cause instanceof IOException) {
			return SendResult.temporaryFailure("SMTP connection lost: " + why);
		}
		return SendResult.temporaryFailure("SMTP: " + why);
	}

	/** The refusal came at MAIL FROM: the sender, the same for every email. */
	private static boolean senderRefused(Exception e) {
		Exception current = e;
		int guard = 0;
		while (current != null && guard++ < 10) {
			if (current instanceof SMTPSenderFailedException) {
				return true;
			}
			if (current instanceof SMTPSendFailedException && !(current instanceof SMTPAddressFailedException)) {
				String command = ((SMTPSendFailedException) current).getCommand();
				if (command != null && command.toUpperCase(Locale.ROOT).startsWith("MAIL FROM")) {
					return true;
				}
			}
			Exception next = current instanceof MessagingException ? ((MessagingException) current).getNextException() : null;
			current = next == current ? null : next;
		}
		return false;
	}

	/** The enhanced status code x.4.5 (RFC 3463, mail system congestion) in the server's answer, e.g. 550 5.4.5. */
	static boolean congestion(String answer) {
		return answer != null && java.util.regex.Pattern.compile("\\b\\d{3}[ -][245]\\.4\\.5\\b").matcher(answer).find();
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
