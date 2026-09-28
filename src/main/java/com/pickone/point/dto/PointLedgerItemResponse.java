package com.pickone.point.dto;

import com.pickone.point.domain.PointLedger;
import com.pickone.point.domain.RefType;
import com.pickone.point.domain.TxType;
import java.time.LocalDateTime;

/** 내역 항목 (docs/api.md 6.2) */
public record PointLedgerItemResponse(Long id, long amount, long balanceAfter, TxType txType, RefType refType, Long refId,
		LocalDateTime createdAt) {

	public static PointLedgerItemResponse from(PointLedger l) {
		return new PointLedgerItemResponse(l.getId(), l.getAmount(), l.getBalanceAfter(), l.getTxType(), l.getRefType(),
				l.getRefId(), l.getCreatedAt());
	}

}
