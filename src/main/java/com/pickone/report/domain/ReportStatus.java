package com.pickone.report.domain;

/** 신고 처리 상태 (report.status). ACCEPTED/REJECTED 는 운영자 API(1차 범위 밖)에서 바꾼다 */
public enum ReportStatus {
	RECEIVED,
	ACCEPTED,
	REJECTED
}
