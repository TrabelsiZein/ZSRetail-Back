package com.digithink.zsretail.core.support;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.mail.Session;
import javax.mail.internet.MimeMessage;

/**
 * 3.3.0, communication step 8a: a mail server that catches every mail on 127.0.0.1 and delivers none, for the tests of
 * the provider smtp and the screen tests (nothing installed, no mail leaves the PC). Enough SMTP for JavaMail: EHLO /
 * HELO, AUTH PLAIN and LOGIN (a user and a password asked when given), MAIL, RCPT, DATA, RSET, NOOP, QUIT. It can refuse
 * a recipient (550), answer a recipient busy (451), or stay silent (a timeout). Step 8a part 2: it counts the
 * connections (each mail knows the one it came on), the EHLO names and the refused logins, and can refuse the sender
 * (MAIL FROM), close a connection with 421 after a number of mails, or answer a daily quota reached (550 5.4.5). Each
 * mail kept in {@link #mails()}; with a folder, also written there as its raw .eml and a decoded .txt (UTF-8). From the
 * command line (one line per connection, login and mail on the console):
 * {@code java -cp <test classes and the mail jars> com.digithink.zsretail.core.support.CatchSmtpServer <port> <folder>
 * [user:password]}.
 */
public final class CatchSmtpServer implements Closeable {

	/** One mail caught. */
	public static final class Mail {
		private final String from;
		private final List<String> to;
		private final byte[] raw;
		private final int connection;

		Mail(String from, List<String> to, byte[] raw, int connection) {
			this.from = from;
			this.to = to;
			this.raw = raw;
			this.connection = connection;
		}

		/** The connection it came on, 1 for the first one the server accepted. */
		public int getConnection() {
			return connection;
		}

		public String getFrom() {
			return from;
		}

		public List<String> getTo() {
			return to;
		}

		public String getRaw() {
			return new String(raw, StandardCharsets.ISO_8859_1);
		}

		/** The mail as JavaMail reads it (subject and text decoded). */
		public MimeMessage parsed() {
			try {
				return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

		public String subject() {
			try {
				return parsed().getSubject();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

		public String text() {
			try {
				return String.valueOf(parsed().getContent());
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private final ServerSocket server;
	private final List<Mail> mails = new CopyOnWriteArrayList<>();
	private final AtomicInteger count = new AtomicInteger();
	private volatile String user;
	private volatile String password;
	private volatile boolean busy;
	private volatile boolean silent;
	private final Set<String> refused = Collections.synchronizedSet(new HashSet<>());
	private final Path folder;
	private final Thread acceptor;
	private final AtomicInteger connections = new AtomicInteger();
	private final AtomicInteger refusedLogins = new AtomicInteger();
	private final List<String> ehloNames = new CopyOnWriteArrayList<>();
	private volatile String senderAnswer;
	private volatile int closeAfter;
	private volatile boolean quota;
	private volatile boolean hangUp;
	private volatile boolean hangUpAtRecipient;
	private volatile long answerAfterDataMillis;
	private volatile boolean verbose;

	/** On 127.0.0.1, port 0 for any free port; folder null: kept in memory only. */
	public CatchSmtpServer(int port, Path folder) throws IOException {
		this.server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
		this.folder = folder;
		if (folder != null) {
			Files.createDirectories(folder);
		}
		acceptor = new Thread(this::accept, "catch-smtp");
		acceptor.setDaemon(true);
		acceptor.start();
	}

	public int getPort() {
		return server.getLocalPort();
	}

	public List<Mail> mails() {
		return new ArrayList<>(mails);
	}

	/** A login asked (null: none). */
	public CatchSmtpServer login(String user, String password) {
		this.user = user;
		this.password = password;
		return this;
	}

	/** Every recipient answered 451 (try again later): the mail's own temporary failure. */
	public CatchSmtpServer busy(boolean busy) {
		this.busy = busy;
		return this;
	}

	/** Step 8a part 2: MAIL FROM answered with this line (e.g. 553 5.7.1 ...); null: accepted. */
	public CatchSmtpServer refuseSender(String answer) {
		this.senderAnswer = answer;
		return this;
	}

	/** Step 8a part 2: after this many mails on a connection, the next MAIL FROM answered 421 and the connection closed. */
	public CatchSmtpServer closeAfter(int mails) {
		this.closeAfter = mails;
		return this;
	}

	/** Step 8a part 2: every mail answered 550 5.4.5 (a daily quota reached) at the end of its data. */
	public CatchSmtpServer quota(boolean quota) {
		this.quota = quota;
		return this;
	}

	/** Step 8a part 2: the connection closed at the end of a mail's data, before any answer (the mail not accepted). */
	public CatchSmtpServer hangUp(boolean hangUp) {
		this.hangUp = hangUp;
		return this;
	}

	/** Step 8c: a wait before the answer to a mail's final "." (a server that checks the mail first). */
	public CatchSmtpServer slowAnswerAfterData(long millis) {
		this.answerAfterDataMillis = millis;
		return this;
	}

	/** Step 8b: the connection closed at RCPT TO, before any answer (the mail never accepted, its data never sent). */
	public CatchSmtpServer hangUpAtRecipient(boolean hangUp) {
		this.hangUpAtRecipient = hangUp;
		return this;
	}

	/** Step 8a part 2: the connections accepted so far. */
	public int connections() {
		return connections.get();
	}

	/** Step 8a part 2: the logins refused so far. */
	public int refusedLogins() {
		return refusedLogins.get();
	}

	/** Step 8a part 2: the names the clients gave in EHLO / HELO, in order. */
	public List<String> ehloNames() {
		return new ArrayList<>(ehloNames);
	}

	/** No answer at all after the connection (the client's read timeout). */
	public CatchSmtpServer silent(boolean silent) {
		this.silent = silent;
		return this;
	}

	/** A recipient answered 550. */
	public CatchSmtpServer refuse(String address) {
		refused.add(address.toLowerCase(Locale.ROOT));
		return this;
	}

	@Override
	public void close() throws IOException {
		server.close();
	}

	private void accept() {
		while (!server.isClosed()) {
			try {
				Socket socket = server.accept();
				Thread session = new Thread(() -> serve(socket), "catch-smtp-session");
				session.setDaemon(true);
				session.start();
			} catch (IOException e) {
				return; // closed
			}
		}
	}

	private void serve(Socket socket) {
		try (Socket s = socket;
				BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
				OutputStream out = s.getOutputStream()) {
			if (silent) {
				Thread.sleep(60_000);
				return;
			}
			int connection = connections.incrementAndGet();
			note("connection " + connection + " opened");
			say(out, "220 catch ESMTP ready");
			boolean authed = user == null;
			String from = null;
			List<String> to = new ArrayList<>();
			int mailsHere = 0;
			String line;
			while ((line = in.readLine()) != null) {
				String upper = line.toUpperCase(Locale.ROOT);
				if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
					ehloNames.add(line.length() > 5 ? line.substring(5).trim() : "");
					note("connection " + connection + ": " + line.trim());
				}
				if (upper.startsWith("EHLO")) {
					say(out, "250-catch");
					say(out, "250-8BITMIME");
					if (user != null) {
						say(out, "250-AUTH PLAIN LOGIN");
					}
					say(out, "250 OK");
				} else if (upper.startsWith("HELO")) {
					say(out, "250 catch");
				} else if (upper.startsWith("AUTH PLAIN")) {
					String response = line.length() > 10 ? line.substring(10).trim() : "";
					if (response.isEmpty()) {
						say(out, "334 ");
						response = in.readLine();
					}
					String[] parts = new String(Base64.getDecoder().decode(response), StandardCharsets.UTF_8).split("\u0000");
					authed = parts.length >= 3 && parts[1].equals(user) && parts[2].equals(password);
					loginAnswer(out, authed, connection);
				} else if (upper.startsWith("AUTH LOGIN")) {
					say(out, "334 VXNlcm5hbWU6");
					String u = new String(Base64.getDecoder().decode(in.readLine().trim()), StandardCharsets.UTF_8);
					say(out, "334 UGFzc3dvcmQ6");
					String p = new String(Base64.getDecoder().decode(in.readLine().trim()), StandardCharsets.UTF_8);
					authed = u.equals(user) && p.equals(password);
					loginAnswer(out, authed, connection);
				} else if (upper.startsWith("MAIL FROM:")) {
					if (!authed) {
						say(out, "530 5.7.0 Authentication required");
					} else if (senderAnswer != null) {
						say(out, senderAnswer);
					} else if (closeAfter > 0 && mailsHere >= closeAfter) {
						say(out, "421 4.7.0 Too many messages on this connection, closing");
						note("connection " + connection + " closed by the server after " + mailsHere + " mail(s)");
						// Step 8c: closed as a server does, the answer first: a close with bytes still unread makes
						// Windows reset the connection, and the client may lose the 421 before reading it
						s.shutdownOutput();
						s.setSoTimeout(2000);
						try {
							while (in.readLine() != null) {
								// what the client still sends (QUIT) until it leaves
							}
						} catch (IOException ignored) {
							// the client left
						}
						return;
					} else {
						from = address(line.substring(10));
						to.clear();
						say(out, "250 2.1.0 Ok");
					}
				} else if (upper.startsWith("RCPT TO:")) {
					String rcpt = address(line.substring(8));
					if (hangUpAtRecipient) {
						note("connection " + connection + " lost: hung up at RCPT TO");
						return;
					}
					if (refused.contains(rcpt.toLowerCase(Locale.ROOT))) {
						say(out, "550 5.1.1 <" + rcpt + ">: Recipient address rejected: user unknown");
					} else if (busy) {
						say(out, "451 4.7.1 <" + rcpt + ">: Try again later");
					} else {
						to.add(rcpt);
						say(out, "250 2.1.5 Ok");
					}
				} else if (upper.equals("DATA")) {
					say(out, "354 End data with <CR><LF>.<CR><LF>");
					StringBuilder data = new StringBuilder();
					String row;
					while ((row = in.readLine()) != null && !row.equals(".")) {
						data.append(row.startsWith("..") ? row.substring(1) : row).append("\r\n");
					}
					if (quota) {
						say(out, "550 5.4.5 Daily user sending quota exceeded");
						continue;
					}
					if (answerAfterDataMillis > 0) {
						Thread.sleep(answerAfterDataMillis);
					}
					if (hangUp) {
						note("connection " + connection + " lost: hung up before answering the data");
						return; // the connection lost before the mail was accepted
					}
					keep(from, new ArrayList<>(to), data.toString().getBytes(StandardCharsets.ISO_8859_1), connection);
					mailsHere++;
					say(out, "250 2.0.0 Ok: queued as CATCH" + count.get());
				} else if (upper.equals("RSET")) {
					from = null;
					to.clear();
					say(out, "250 2.0.0 Ok");
				} else if (upper.equals("NOOP")) {
					say(out, "250 2.0.0 Ok");
				} else if (upper.equals("QUIT")) {
					say(out, "221 2.0.0 Bye");
					note("connection " + connection + " closed by the client after " + mailsHere + " mail(s)");
					return;
				} else {
					say(out, "502 5.5.2 Command not recognized");
				}
			}
		} catch (IOException | InterruptedException e) {
			// the client left
		}
	}

	private void loginAnswer(OutputStream out, boolean authed, int connection) throws IOException {
		if (!authed) {
			refusedLogins.incrementAndGet();
		}
		note("connection " + connection + ": login " + (authed ? "accepted" : "refused"));
		say(out, authed ? "235 2.7.0 Authentication successful" : "535 5.7.8 Authentication failed: bad user or password");
	}

	/** One line on the console when run from the command line. */
	private void note(String text) {
		if (verbose) {
			System.out.println(java.time.LocalTime.now().withNano(0) + " " + text);
		}
	}

	private void keep(String from, List<String> to, byte[] raw, int connection) throws IOException {
		Mail mail = new Mail(from, to, raw, connection);
		mails.add(mail);
		int n = count.incrementAndGet();
		note("connection " + connection + ": mail " + String.format("%04d", n) + " from " + from + " to "
				+ String.join(", ", to));
		if (folder != null) {
			String name = String.format("%04d-%s", n, String.join("_", to).replaceAll("[^A-Za-z0-9@._-]", "_"));
			Files.write(folder.resolve(name + ".eml"), raw);
			String decoded = "Connection: " + connection + "\nFrom: " + from + "\nTo: " + String.join(", ", to)
					+ "\nSubject: " + mail.subject() + "\nContent-Type: " + contentType(mail) + "\n\n" + mail.text() + "\n";
			Files.write(folder.resolve(name + ".txt"), decoded.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static String contentType(Mail mail) {
		try {
			return mail.parsed().getContentType();
		} catch (Exception e) {
			return "?";
		}
	}

	private static String address(String raw) {
		String value = raw.trim();
		int open = value.indexOf('<');
		int close = value.indexOf('>');
		return open >= 0 && close > open ? value.substring(open + 1, close) : value.split(" ")[0];
	}

	private static void say(OutputStream out, String line) throws IOException {
		out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
		out.flush();
	}

	/** Port, folder, optional user:password; runs until stopped. */
	public static void main(String[] args) throws Exception {
		CatchSmtpServer server = new CatchSmtpServer(Integer.parseInt(args[0]), Paths.get(args[1]));
		server.verbose = true;
		if (args.length > 2 && args[2].contains(":")) {
			server.login(args[2].substring(0, args[2].indexOf(':')), args[2].substring(args[2].indexOf(':') + 1));
		}
		System.out.println("Catch SMTP server on 127.0.0.1:" + server.getPort() + ", mails written to " + args[1]
				+ (server.user == null ? "" : ", login " + server.user));
		Thread.currentThread().join();
	}
}
