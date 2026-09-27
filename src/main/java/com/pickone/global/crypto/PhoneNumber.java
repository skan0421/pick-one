package com.pickone.global.crypto;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import java.util.regex.Pattern;

/**
 * 휴대폰 번호 정규화·마스킹. 한국 휴대폰 번호만 지원한다.
 * 입력 예: "010-1234-5678", "01012345678", "+82 10 1234 5678", "+821012345678" → "+821012345678"
 */
public final class PhoneNumber {

	/** 010 은 항상 11자리, 구형 011/016/017/018/019 는 10~11자리 */
	private static final Pattern KOREAN_MOBILE = Pattern.compile("^(010\\d{8}|01[16789]\\d{7,8})$");
	private static final String COUNTRY_CODE = "82";

	private PhoneNumber() {
	}

	/** E.164 형식(+8210XXXXXXXX)으로 정규화한다. 형식이 맞지 않으면 PHONE_INVALID_FORMAT */
	public static String toE164(String raw) {
		if (raw == null) {
			throw new BusinessException(ErrorCode.PHONE_INVALID_FORMAT);
		}
		String compact = raw.replaceAll("[^0-9+]", "");
		String national;
		if (compact.startsWith("+" + COUNTRY_CODE)) {
			national = toNational(compact.substring(1 + COUNTRY_CODE.length()));
		}
		else if (compact.startsWith("+")) {
			throw new BusinessException(ErrorCode.PHONE_INVALID_FORMAT); // 다른 국가 번호
		}
		else if (compact.startsWith(COUNTRY_CODE) && compact.length() >= 12) {
			national = toNational(compact.substring(COUNTRY_CODE.length()));
		}
		else {
			national = compact;
		}
		if (!KOREAN_MOBILE.matcher(national).matches()) {
			throw new BusinessException(ErrorCode.PHONE_INVALID_FORMAT);
		}
		return "+" + COUNTRY_CODE + national.substring(1);
	}

	/** 국가번호 뒤의 숫자열을 국내 표기(0 으로 시작)로 바꾼다. "+82 010..." 처럼 0 을 남긴 입력도 허용 */
	private static String toNational(String afterCountryCode) {
		return afterCountryCode.startsWith("0") ? afterCountryCode : "0" + afterCountryCode;
	}

	/** 로그용 마스킹: +821012345678 → +8210****5678 */
	public static String mask(String e164) {
		if (e164 == null || e164.length() < 9) {
			return "****";
		}
		return e164.substring(0, 5) + "****" + e164.substring(e164.length() - 4);
	}

}
