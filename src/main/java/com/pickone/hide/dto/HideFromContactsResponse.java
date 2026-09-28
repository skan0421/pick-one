package com.pickone.hide.dto;

/** 켜기/끄기 결과 (docs/api.md 7.2). hiddenMembers = hide_relation 수, pending = hide_pending 수 */
public record HideFromContactsResponse(boolean enabled, long hiddenMembers, long pending) {
}
