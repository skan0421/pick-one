package com.pickone.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * 여러 스레드가 동시에 로그를 남기는 테스트용 ListAppender (기본 구현은 ArrayList.add 라 스레드 안전하지 않다).
 * 재시도 실행기의 "낙관적 락 재시도" 로그를 세는 데 쓴다.
 */
public class ThreadSafeListAppender extends ListAppender<ILoggingEvent> {

	private final Logger logger;

	public ThreadSafeListAppender(Class<?> loggerClass) {
		this.logger = (Logger) LoggerFactory.getLogger(loggerClass);
	}

	@Override
	protected synchronized void append(ILoggingEvent event) {
		super.append(event);
	}

	public synchronized long count(String messageFragment) {
		return list.stream().filter(e -> e.getFormattedMessage().contains(messageFragment)).count();
	}

	public synchronized List<String> messages(String messageFragment) {
		return list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains(messageFragment)).toList();
	}

	public void attach() {
		start();
		logger.addAppender(this);
	}

	public void detach() {
		logger.detachAppender(this);
		stop();
	}

}
