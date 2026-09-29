package com.pickone.upload.service;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.global.storage.ImageStorage;
import com.pickone.global.storage.StorageProperties;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 고민 등록 시 imageUrl 검증 (docs/api.md 4.6).
 * 1) 정확히 "{publicBaseUrl}/images/{memberId}/{uuid}.{ext}" 형식이어야 한다 — 외부 URL, 다른 회원 폴더, 쿼리스트링 모두 거절
 * 2) 그 키가 저장소에 실제로 있어야 한다 (HEAD) — 발급만 받고 올리지 않은 URL 거절
 * 실패는 모두 400 IMAGE_URL_INVALID 로 같게 응답한다 (남의 키가 존재하는지 알려 주지 않기 위해).
 */
@Component
@RequiredArgsConstructor
public class ImageUrlValidator {

	private static final Pattern FILE = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)$");

	private final ImageStorage imageStorage;
	private final StorageProperties properties;

	/** 검증을 통과한 정규화된 URL(trim)을 돌려준다 */
	public String validateOwned(Long memberId, String imageUrl) {
		String url = imageUrl.trim();
		String prefix = properties.publicBaseUrl() + "/" + ImageUploadService.KEY_PREFIX + memberId + "/";
		if (!url.startsWith(prefix)) {
			throw new BusinessException(ErrorCode.IMAGE_URL_INVALID);
		}
		String file = url.substring(prefix.length());
		if (!FILE.matcher(file).matches()) {
			throw new BusinessException(ErrorCode.IMAGE_URL_INVALID);
		}
		String key = ImageUploadService.KEY_PREFIX + memberId + "/" + file;
		if (!imageStorage.exists(key)) {
			throw new BusinessException(ErrorCode.IMAGE_URL_INVALID);
		}
		return url;
	}

}
