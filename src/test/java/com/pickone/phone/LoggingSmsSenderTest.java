package com.pickone.phone;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pickone.phone.sms.LoggingSmsSender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** 가짜 발송기가 로그에 휴대폰 번호 원본을 남기지 않는지 확인한다 */
class LoggingSmsSenderTest {

	private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingSmsSender.class);
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private Level originalLevel;

	@BeforeEach
	void attachAppender() {
		originalLevel = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void detachAppender() {
		logger.detachAppender(appender);
		logger.setLevel(originalLevel);
	}

	@Test
	void 로그에는_마스킹된_번호만_남고_원본_번호는_없다() {
		new LoggingSmsSender().send("+821012345678", "[pick-one] 인증번호 483920");

		List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		assertThat(messages).isNotEmpty();
		assertThat(messages).allSatisfy(m -> {
			assertThat(m).doesNotContain("1012345678");
			assertThat(m).doesNotContain("01012345678");
		});
		assertThat(messages).anySatisfy(m -> assertThat(m).contains("+8210****5678"));
	}

	@Test
	void 인증번호_본문은_DEBUG_레벨에만_남는다() {
		new LoggingSmsSender().send("+821012345678", "[pick-one] 인증번호 483920");

		assertThat(appender.list)
				.filteredOn(e -> e.getFormattedMessage().contains("483920"))
				.allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.DEBUG));
		assertThat(appender.list)
				.filteredOn(e -> e.getLevel() == Level.INFO)
				.allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("483920"));
	}

}
