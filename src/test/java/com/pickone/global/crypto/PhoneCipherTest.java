package com.pickone.global.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PhoneCipherTest {

	private static final String AES_KEY = randomBase64(32);
	private static final String HMAC_KEY = "test-hmac-key-" + randomBase64(32);

	private final PhoneCipher cipher = new PhoneCipher(new CryptoProperties(AES_KEY, HMAC_KEY));

	@Test
	void 암호화한_값을_복호화하면_원본이_나온다() {
		String encrypted = cipher.encrypt("+821012345678");

		assertThat(encrypted).doesNotContain("1012345678");
		assertThat(cipher.decrypt(encrypted)).isEqualTo("+821012345678");
	}

	@Test
	void 같은_번호라도_암호문은_매번_다르다_IV_랜덤() {
		assertThat(cipher.encrypt("+821012345678")).isNotEqualTo(cipher.encrypt("+821012345678"));
	}

	@Test
	void 다른_키로는_복호화할_수_없다() {
		String encrypted = cipher.encrypt("+821012345678");
		PhoneCipher other = new PhoneCipher(new CryptoProperties(randomBase64(32), HMAC_KEY));

		assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void HMAC은_같은_입력에_항상_같은_64자_hex다() {
		String a = cipher.hmac("+821012345678");
		String b = cipher.hmac("+821012345678");

		assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
		assertThat(cipher.hmac("+821012345679")).isNotEqualTo(a);
	}

	@Test
	void AES_키_길이가_틀리면_기동_시점에_실패한다() {
		assertThatThrownBy(() -> new CryptoProperties(randomBase64(20), HMAC_KEY))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("16/24/32");
	}

	@Test
	void HMAC_키가_짧거나_AES_키와_같으면_실패한다() {
		assertThatThrownBy(() -> new CryptoProperties(AES_KEY, "short")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new CryptoProperties(AES_KEY, AES_KEY)).isInstanceOf(IllegalStateException.class);
	}

	private static String randomBase64(int bytes) {
		byte[] b = new byte[bytes];
		new SecureRandom().nextBytes(b);
		return Base64.getEncoder().encodeToString(b);
	}

}
