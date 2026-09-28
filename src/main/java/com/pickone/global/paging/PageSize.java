package com.pickone.global.paging;

/** 커서 목록 API 의 size 파라미터 보정 (docs/api.md 1.5: 기본 20, 최대 50) */
public final class PageSize {

	public static final int DEFAULT = 20;
	public static final int MAX = 50;

	private PageSize() {
	}

	public static int normalize(Integer size) {
		if (size == null || size < 1) {
			return DEFAULT;
		}
		return Math.min(size, MAX);
	}

}
