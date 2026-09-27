package com.pickone.global.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumberTest {

	@ParameterizedTest
	@ValueSource(strings = {"010-1234-5678", "01012345678", "+82 10 1234 5678", "+821012345678", "+82 010-1234-5678", "821012345678", "010 1234 5678"})
	void 여러_표기를_같은_E164로_정규화한다(String raw) {
		assertThat(PhoneNumber.toE164(raw)).isEqualTo("+821012345678");
	}

	@Test
	void 구형_10자리_번호도_허용한다() {
		assertThat(PhoneNumber.toE164("011-123-4567")).isEqualTo("+82111234567");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "abc", "02-1234-5678", "0101234567", "010123456789", "+1 415 555 2671", "020-1234-5678"})
	void 휴대폰_번호가_아니면_PHONE_INVALID_FORMAT(String raw) {
		assertThatThrownBy(() -> PhoneNumber.toE164(raw))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.PHONE_INVALID_FORMAT);
	}

	@Test
	void null_도_PHONE_INVALID_FORMAT() {
		assertThatThrownBy(() -> PhoneNumber.toE164(null)).isInstanceOf(BusinessException.class);
	}

	@Test
	void 마스킹하면_가운데가_가려진다() {
		assertThat(PhoneNumber.mask("+821012345678")).isEqualTo("+8210****5678");
		assertThat(PhoneNumber.mask(null)).isEqualTo("****");
	}

}
