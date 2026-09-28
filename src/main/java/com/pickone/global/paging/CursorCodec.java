package com.pickone.global.paging;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 커서 ↔ 문자열 변환. 커서 객체를 JSON 으로 만든 뒤 base64url 로 감싼다.
 * 클라이언트는 해석하지 않고 그대로 돌려주면 되고, 깨진 값은 VALIDATION_ERROR 로 응답한다.
 */
@Component
@RequiredArgsConstructor
public class CursorCodec {

	private final ObjectMapper objectMapper;

	public String encode(Object cursor) {
		byte[] json = objectMapper.writeValueAsBytes(cursor);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
	}

	public <T> T decode(String cursor, Class<T> type) {
		try {
			byte[] json = Base64.getUrlDecoder().decode(cursor);
			return objectMapper.readValue(new String(json, StandardCharsets.UTF_8), type);
		}
		catch (RuntimeException e) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 값이 올바르지 않습니다.");
		}
	}

}
