package com.digithink.zsretail.core.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import com.digithink.zsretail.core.config.MachineFileEnvironmentPostProcessor;
import com.digithink.zsretail.core.config.ZsRetailApplication;
import com.digithink.zsretail.core.model.LicenseRecord;
import com.digithink.zsretail.core.repository.LicenseRecordRepository;
import com.digithink.zsretail.core.service.MachineFingerprintService;

/**
 * Boot 4 migration, step 1.2: one application really started, the way ZsRetailApplication.run starts it (its type as a
 * profile and as default properties, its configuration files, every bean, the startup T-SQL), on an embedded Tomcat on a
 * free port and on {@link MigTestDatabase} only, and called over HTTP so a request goes through the real filter chains and,
 * for an error, the real ERROR dispatch.
 * <p>
 * No ERP connector, no head office link, no jobs by default. A shape that needs a connector or a link gives it an address on
 * {@link #CLOSED_PORT}: nothing can be called outside this machine. The arguments win over every other source, the outside
 * file a -Dzsretail.machine-file would name is refused, and {@link #nothingCanLeave} refuses any other address before a
 * single bean exists.
 */
public final class MigTestApplication implements AutoCloseable {

	/** The address of every connector and link of a test start: this machine, a port nothing listens on. */
	public static final String CLOSED_PORT = "http://127.0.0.1:1";

	private final Class<? extends ZsRetailApplication> application;
	private final String type;
	private final MigTestDatabase database;
	private final String[] more;
	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
	private final LoggingSystem logging = LoggingSystem.get(MigTestApplication.class.getClassLoader());
	private final LogLevel rootLevelBefore = logging.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME).getEffectiveLevel();

	private ConfigurableApplicationContext context;
	private String base;
	private BeanPostProcessor refuser;

	private MigTestApplication(Class<? extends ZsRetailApplication> application, String type, MigTestDatabase database,
			String... more) {
		this.application = application;
		this.type = type;
		this.database = database;
		this.more = more;
	}

	/**
	 * Started on a new empty database. Skips or fails when the server is unreachable ({@link MigTestDatabase}).
	 *
	 * @param more further settings, key=value; a key of the defaults below takes this value instead
	 */
	public static MigTestApplication start(Class<? extends ZsRetailApplication> application, String type, String... more) {
		noOutsideFile();
		MigTestDatabase database = MigTestDatabase.fromSettings();
		database.recreate();
		MigTestApplication started = new MigTestApplication(application, type, database, more);
		try {
			started.run();
		} catch (RuntimeException | Error e) {
			started.restoreLogging();
			database.drop();
			throw e;
		}
		return started;
	}

	private static void noOutsideFile() {
		String machineFile = System.getProperty(MachineFileEnvironmentPostProcessor.MACHINE_FILE_KEY);
		if (machineFile != null) {
			throw new IllegalStateException("The startup tests run on their own database, not on the outside file " + machineFile);
		}
	}

	/** What a start meant to be refused ended with. */
	public static final class Refusal {
		public final Throwable failure;
		public final int tablesLeft;

		Refusal(Throwable failure, int tablesLeft) {
			this.failure = failure;
			this.tablesLeft = tablesLeft;
		}
	}

	/**
	 * A start on a new empty database that one more post-processor, a bean of that start, is meant to refuse: what it ended
	 * with and the tables it left, then the database dropped. Fails when the start goes through.
	 */
	public static Refusal startRefused(Class<? extends ZsRetailApplication> application, String type, BeanPostProcessor refuser) {
		noOutsideFile();
		MigTestDatabase database = MigTestDatabase.fromSettings();
		database.recreate();
		MigTestApplication started = new MigTestApplication(application, type, database);
		started.refuser = refuser;
		try {
			started.run();
		} catch (RuntimeException e) {
			return new Refusal(e, database.tableCount());
		} finally {
			started.restoreLogging();
			if (started.context != null) {
				started.context.close();
			}
			database.drop();
		}
		throw new AssertionError("The start went through");
	}

	/**
	 * The level the start found: Boot sets the JVM's root logger from logging.level.root and leaves it there, and the tests
	 * that run next in this JVM read the logs (LinkJobSchedulerTest).
	 */
	private void restoreLogging() {
		logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, rootLevelBefore);
	}

	private void run() {
		SpringApplication spring = new SpringApplication(application);
		spring.setAdditionalProfiles(type);
		spring.setDefaultProperties(ZsRetailApplication.defaults(type));
		spring.setBannerMode(Banner.Mode.OFF);
		spring.addInitializers(starting -> {
			nothingCanLeave(starting.getEnvironment());
			if (refuser != null) {
				starting.getBeanFactory().addBeanPostProcessor(refuser);
			}
		});
		// A key given twice on a command line reads as "a,b": a shape's value replaces the default here
		Map<String, String> settings = new LinkedHashMap<>();
		settings.put("spring.datasource.url", database.url());
		settings.put("spring.datasource.username", database.username());
		settings.put("spring.datasource.password", database.password());
		settings.put("server.port", "0");
		settings.put("erp.connector", "none");
		settings.put("erp.sync.enabled", "false");
		settings.put("headoffice.url", "");
		settings.put("logging.level.root", "WARN");
		for (String line : more) {
			int equals = line.indexOf('=');
			settings.put(line.substring(0, equals), line.substring(equals + 1));
		}
		context = spring.run(settings.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
				.toArray(String[]::new));
		base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port")
				+ context.getEnvironment().getProperty("server.servlet.context-path");
	}

	/**
	 * Before any bean exists: every address of a connector or of the head office link, from any source (an environment
	 * variable included), is empty or on {@link #CLOSED_PORT}. Never a customer's system.
	 */
	static void nothingCanLeave(ConfigurableEnvironment environment) {
		for (PropertySource<?> source : environment.getPropertySources()) {
			if (!(source instanceof EnumerablePropertySource)) {
				continue;
			}
			for (String key : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
				String name = key.toLowerCase(Locale.ROOT).replace('_', '.');
				if ((name.startsWith("erp.") || name.startsWith("headoffice.")) && (name.contains("url") || name.contains("host"))) {
					String value = environment.getProperty(key);
					if (value != null && !value.trim().isEmpty() && !value.trim().startsWith(CLOSED_PORT)) {
						throw new IllegalStateException("A startup test calls nothing outside this machine: " + key + "=" + value);
					}
				}
			}
		}
	}

	/** Stopped and started again on the same database, as an installation does at every restart. */
	public void restart() {
		context.close();
		run();
	}

	public ConfigurableApplicationContext context() {
		return context;
	}

	public <T> T bean(Class<T> beanClass) {
		return context.getBean(beanClass);
	}

	/** The application stopped, then the database dropped. */
	@Override
	public void close() {
		try {
			context.close();
		} finally {
			restoreLogging();
			database.drop();
		}
	}

	// ─── The licence: a fresh database has none, so every path but the bypassed ones answers 402 ─────────────────────

	/** The row LicenseService.getStatus() reads as VALID: bound to this machine, a year left. */
	public void withLicense() {
		withoutLicense();
		LicenseRecord record = new LicenseRecord();
		record.setCompanyName("MIGTEST");
		record.setInstallationId(bean(MachineFingerprintService.class).getInstallationId());
		record.setIssuedAt(LocalDate.now());
		record.setExpiresAt(LocalDate.now().plusYears(1));
		record.setRawJson("{}");
		record.setUploadedAt(LocalDateTime.now());
		record.setUploadedBy("migtest");
		record.setCurrentLicense(true);
		bean(LicenseRecordRepository.class).save(record);
	}

	public void withoutLicense() {
		bean(LicenseRecordRepository.class).deleteAll();
	}

	// ─── HTTP ───────────────────────────────────────────────────────────────────────────────────────────────────────

	/** A response as a caller gets it. */
	public static final class Answer {
		public final int status;
		public final String body;
		private final HttpResponse<String> response;

		Answer(HttpResponse<String> response) {
			this.response = response;
			this.status = response.statusCode();
			this.body = response.body();
		}

		public String header(String name) {
			return response.headers().firstValue(name).orElse(null);
		}
	}

	/** A call inside the context path; body null: none; headers: name, value, name, value... */
	public Answer call(String method, String path, String body, String... headers) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).method(method,
				body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		if (body != null) {
			request.header("Content-Type", "application/json");
		}
		if (headers.length > 0) {
			request.headers(headers);
		}
		try {
			return new Answer(http.send(request.build(), HttpResponse.BodyHandlers.ofString()));
		} catch (IOException e) {
			throw new IllegalStateException(method + " " + path + ": " + e, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(method + " " + path + " interrupted", e);
		}
	}

	/** POST /login, as the front does: the credentials in the headers username and password. */
	public Answer login(String username, String password) {
		return call("POST", "/login", null, "username", username, "password", password);
	}

	/** The Authorization header of a real login. */
	public String[] bearerOf(String username, String password) {
		Answer login = login(username, password);
		String token = login.header("Authorization");
		if (login.status != 200 || token == null) {
			throw new IllegalStateException("Login of " + username + " answered " + login.status + ": " + login.body);
		}
		return new String[] { "Authorization", token };
	}
}
