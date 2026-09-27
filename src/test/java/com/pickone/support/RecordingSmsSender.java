package com.pickone.support;

import com.pickone.phone.sms.SmsSender;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 테스트용 발송기. 마지막으로 보낸 본문을 번호별로 기록해 두고, 테스트가 인증번호를 꺼내 쓸 수 있게 한다.
 * (실제 응답에는 코드가 없으므로 이 경로로만 코드를 얻을 수 있다)
 */
public class RecordingSmsSender implements SmsSender {

	private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

	private final Map<String, String> lastMessageByPhone = new ConcurrentHashMap<>();

	@Override
	public void send(String phoneE164, String message) {
		lastMessageByPhone.put(phoneE164, message);
	}

	public String lastCodeFor(String phoneE164) {
		String message = lastMessageByPhone.get(phoneE164);
		if (message == null) {
			throw new IllegalStateException("발송 기록이 없습니다: " + phoneE164);
		}
		Matcher matcher = CODE.matcher(message);
		if (!matcher.find()) {
			throw new IllegalStateException("본문에 6자리 코드가 없습니다: " + message);
		}
		return matcher.group(1);
	}

	public int sentCount() {
		return lastMessageByPhone.size();
	}

}
