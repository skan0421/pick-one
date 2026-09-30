package com.pickone.question.service;

import com.pickone.question.dto.CreateQuestionRequest;
import com.pickone.question.dto.CreateQuestionRequest.OptionRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 고민 등록 요청 내용의 지문(SHA-256, 16진수 64자). 같은 Idempotency-Key 로 온 두 요청이 같은 내용인지 비교한다.
 *
 * 포함하는 것: 유형, 본문, 선택지(순서대로, content 와 imageUrl). 앞뒤 공백은 무시한다.
 * 값마다 "길이:값" 으로 이어 붙인다. 구분자만 쓰면 ("ab", "c") 와 ("a", "bc") 가 같은 문자열이 된다.
 * 저장된 고민과 비교하지 않고 요청끼리 비교하는 이유: 저장값은 검증을 거치며 바뀔 수 있고(사진 주소 정규화),
 * 재요청은 검증 전에 판정해야 저장소 확인(HEAD) 없이 처음 응답을 돌려줄 수 있다.
 */
public final class QuestionRequestHash {

	private QuestionRequestHash() {
	}

	public static String of(CreateQuestionRequest request) {
		StringBuilder canonical = new StringBuilder();
		append(canonical, request.questionType().name());
		append(canonical, request.content());
		append(canonical, String.valueOf(request.options().size()));
		for (OptionRequest option : request.options()) {
			// null 인 선택지 항목은 형식 검증에서 걸러지지만, 지문 계산이 먼저 실행되므로 견딘다
			append(canonical, option == null ? null : option.content());
			append(canonical, option == null ? null : option.imageUrl());
		}
		return sha256(canonical.toString());
	}

	private static void append(StringBuilder canonical, String value) {
		String normalized = value == null ? "" : value.trim();
		canonical.append(normalized.length()).append(':').append(normalized).append('|');
	}

	private static String sha256(String text) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", e);
		}
	}

}
