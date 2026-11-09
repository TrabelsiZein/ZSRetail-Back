package com.digithink.zsretail.support;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** The ERROR lines a class logs while something runs. */
public final class ErrorLines {

	/** What runs. */
	public interface Action {
		void run() throws Exception;
	}

	private ErrorLines() {
	}

	public static List<String> of(Class<?> logged, Action action) throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(logged);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		try {
			action.run();
		} finally {
			logger.detachAppender(logs);
		}
		return logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).map(ILoggingEvent::getFormattedMessage)
				.collect(Collectors.toList());
	}
}
