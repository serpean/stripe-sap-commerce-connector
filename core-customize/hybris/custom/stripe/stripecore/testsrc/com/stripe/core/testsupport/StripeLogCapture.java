package com.stripe.core.testsupport;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Keeps the log events of one class during a test, so the test can check that a message was logged.
 */
public final class StripeLogCapture extends AbstractAppender implements AutoCloseable
{
	private final List<LogEvent> events = new CopyOnWriteArrayList<>();
	private final Logger logger;

	private StripeLogCapture(final Class<?> type)
	{
		super("stripe-log-capture-" + type.getSimpleName(), null, null, true, Property.EMPTY_ARRAY);
		this.logger = (Logger) LogManager.getLogger(type);
		start();
		logger.addAppender(this);
	}

	public static StripeLogCapture of(final Class<?> type)
	{
		return new StripeLogCapture(type);
	}

	@Override
	public void append(final LogEvent event)
	{
		events.add(event.toImmutable());
	}

	/**
	 * @return true when an ERROR event was logged whose message holds all the given texts
	 */
	public boolean hasError(final String... texts)
	{
		return events.stream()
				.filter(event -> Level.ERROR.equals(event.getLevel()))
				.map(event -> event.getMessage().getFormattedMessage())
				.anyMatch(message -> Arrays.stream(texts).allMatch(message::contains));
	}

	/**
	 * @return true when a WARN event was logged whose message holds all the given texts
	 */
	public boolean hasWarn(final String... texts)
	{
		return events.stream()
				.filter(event -> Level.WARN.equals(event.getLevel()))
				.map(event -> event.getMessage().getFormattedMessage())
				.anyMatch(message -> Arrays.stream(texts).allMatch(message::contains));
	}

	@Override
	public void close()
	{
		logger.removeAppender(this);
		stop();
	}
}
