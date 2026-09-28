package com.pickone.global.paging;

import java.util.List;

/** 커서 기반 목록 응답 (docs/api.md 1.5). hasNext 가 false 면 nextCursor 는 null */
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasNext) {

	public static <T> CursorPage<T> of(List<T> items, String nextCursor, boolean hasNext) {
		return new CursorPage<>(items, hasNext ? nextCursor : null, hasNext);
	}

}
