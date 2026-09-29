package com.pickone.global.storage;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 이미지 객체 저장소 포트 (SmsSender 와 같은 분리 방식).
 * 구현은 S3ImageStorage 하나이고, 로컬 MinIO 와 운영 S3 는 pickone.storage.* 설정만 다르다.
 * 읽기는 버킷 정책으로 images/* 접두사를 공개해 저장된 URL 을 그대로 쓴다 (docs/api.md 4.6).
 */
public interface ImageStorage {

	/** 클라이언트가 직접 PUT 할 수 있는 서명 URL. contentType·contentLength 는 서명에 포함되어 그대로 보내야 한다 */
	PresignedUpload presignPut(String objectKey, String contentType, long contentLength, Duration ttl);

	/** 앱이 읽는 공개 URL: {publicBaseUrl}/{objectKey} */
	String publicUrl(String objectKey);

	/** HEAD 로 실제 업로드 여부 확인 (고민 등록 시 발급만 받고 올리지 않은 키를 거른다) */
	boolean exists(String objectKey);

	record PresignedUpload(String uploadUrl, LocalDateTime expiresAt) {
	}

}
