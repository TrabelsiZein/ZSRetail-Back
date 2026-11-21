package com.digithink.zsretail.core.communication.service;

import java.util.Set;
import java.util.TreeSet;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication step 8e: a store whose loyalty is the head office's only finds its events and passes them up; the
 * head office's channel lines are the ones used. The channel lines written for this store (its own file, a -D, not the
 * defaults inside the WAR) are never read nor checked: one WARN names them (the names only, never a value).
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION_PUSHED_UP)
@Log4j2
public class CommunicationIgnoredLines {

	/** The two lines a store that passes its events up does use. */
	static final Set<String> USED = new TreeSet<>(java.util.Arrays.asList("communication.enabled",
			"communication.cycle-seconds"));

	private final ConfigurableEnvironment environment;

	public CommunicationIgnoredLines(ConfigurableEnvironment environment) {
		this.environment = environment;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void warn() {
		Set<String> ignored = ignored(environment);
		if (!ignored.isEmpty()) {
			log.warn("Communication: this store's loyalty is the head office's: it passes its events up and the head office"
					+ " sends; these lines are not used here (set them at the head office): {}", String.join(", ", ignored));
		}
	}

	/** The communication.* lines set outside the WAR's own files, but the two this store uses. */
	static Set<String> ignored(ConfigurableEnvironment environment) {
		Set<String> ignored = new TreeSet<>();
		for (PropertySource<?> source : environment.getPropertySources()) {
			if (!(source instanceof EnumerablePropertySource) || source.getName().contains("classpath:")) {
				continue;
			}
			for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
				if (name.startsWith("communication.") && !USED.contains(name)) {
					ignored.add(name);
				}
			}
		}
		return ignored;
	}
}
