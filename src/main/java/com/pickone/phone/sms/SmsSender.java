package com.pickone.phone.sms;

/**
 * 문자 발송 포트. 구현체는 프로필로 갈아 끼운다.
 * - local/test: LoggingSmsSender (실제 발송 없이 로그)
 * - prod: 문자 발송 업체 SDK 구현 (추후)
 */
public interface SmsSender {

	/**
	 * @param phoneE164 수신 번호 (+8210XXXXXXXX)
	 * @param message   본문 (인증번호 포함)
	 */
	void send(String phoneE164, String message);

}
