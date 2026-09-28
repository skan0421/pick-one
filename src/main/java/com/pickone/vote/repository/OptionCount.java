package com.pickone.vote.repository;

/** 선택지별 득표수 집계 행 (GROUP BY question_id, option_id) */
public record OptionCount(Long questionId, Long optionId, long count) {
}
