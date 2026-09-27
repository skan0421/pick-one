package com.pickone.phone.sms;

import com.pickone.global.crypto.PhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬/테스트용 가짜 발송기. 실제로 보내지 않고 로그로만 남긴다.
 * 수신 번호는 항상 마스킹하고, 인증번호가 담긴 본문은 DEBUG 레벨에만 남긴다 (application.yml 에서 이 클래스만 DEBUG).
 */
@Slf4j
@Component
@Profile({"local", "test"})
public class LoggingSmsSender implements SmsSender {

	@Override
	public void send(String phoneE164, String message) {
		log.info("[SMS 발송 생략] to={} (본문은 DEBUG 로그 참고)", PhoneNumber.mask(phoneE164));
		log.debug("[SMS 본문] to={} message={}", PhoneNumber.mask(phoneE164), message);
	}

}
