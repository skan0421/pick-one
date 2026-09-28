package com.pickone.upload.service;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.global.storage.ImageStorage;
import com.pickone.global.storage.ImageStorage.PresignedUpload;
import com.pickone.global.storage.StorageProperties;
import com.pickone.upload.dto.ImageUploadResponse;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 사진 업로드 URL 발급 (docs/api.md 4.6).
 * 서버는 파일을 받지 않는다. 형식·크기를 검증한 뒤 회원별 폴더의 UUID 키로 presigned PUT URL 을 만들어 준다.
 * 키에 회원 ID 가 들어가므로 고민 등록 시 "본인이 발급받은 주소인지" 를 URL 만으로 판정할 수 있다 (ImageUrlValidator).
 */
@Service
@RequiredArgsConstructor
public class ImageUploadService {

	public static final String KEY_PREFIX = "images/";
	static final Map<String, String> EXTENSIONS = Map.of(
			"image/jpeg", "jpg",
			"image/png", "png",
			"image/webp", "webp");

	private final ImageStorage imageStorage;
	private final StorageProperties properties;

	public ImageUploadResponse issue(Long memberId, String contentType, long size) {
		String type = contentType.trim().toLowerCase(Locale.ROOT);
		if (!properties.allowedContentTypes().contains(type) || !EXTENSIONS.containsKey(type)) {
			throw new BusinessException(ErrorCode.IMAGE_TYPE_NOT_ALLOWED);
		}
		if (size > properties.maxImageSize().toBytes()) {
			throw new BusinessException(ErrorCode.IMAGE_TOO_LARGE,
					"이미지는 " + properties.maxImageSize().toMegabytes() + "MB 이하여야 합니다.");
		}
		String key = objectKey(memberId, EXTENSIONS.get(type));
		PresignedUpload upload = imageStorage.presignPut(key, type, size, properties.presignTtl());
		return new ImageUploadResponse(upload.uploadUrl(), imageStorage.publicUrl(key), upload.expiresAt());
	}

	/** images/{memberId}/{uuid}.{ext} */
	static String objectKey(Long memberId, String extension) {
		return KEY_PREFIX + memberId + "/" + UUID.randomUUID() + "." + extension;
	}

}
