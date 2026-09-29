package com.pickone.point.service;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.global.paging.CursorCodec;
import com.pickone.global.paging.CursorPage;
import com.pickone.global.paging.KeysetCursor;
import com.pickone.global.paging.PageSize;
import com.pickone.global.time.KstDates;
import com.pickone.point.PointProperties;
import com.pickone.point.domain.PointLedger;
import com.pickone.point.domain.PointWallet;
import com.pickone.point.domain.TxType;
import com.pickone.point.dto.PointBalanceResponse;
import com.pickone.point.dto.PointLedgerItemResponse;
import com.pickone.point.repository.PointDailyCounterStore;
import com.pickone.point.repository.PointLedgerRepository;
import com.pickone.point.repository.PointWalletRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 포인트 잔액·내역 조회 (docs/api.md 6.1, 6.2) */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(PointProperties.class)
public class PointService {

	private final PointWalletRepository pointWalletRepository;
	private final PointLedgerRepository pointLedgerRepository;
	private final PointDailyCounterStore dailyCounterStore;
	private final PointProperties properties;
	private final CursorCodec cursorCodec;

	/** todayEarned 는 Redis 캐시에서 읽고, 유실됐으면 원장 SUM 으로 다시 계산해 캐시를 채운다 */
	@Transactional(readOnly = true)
	public PointBalanceResponse balance(Long memberId) {
		PointWallet wallet = pointWalletRepository.findById(memberId)
				.orElseThrow(() -> new BusinessException(ErrorCode.POINT_WALLET_NOT_FOUND));
		String today = KstDates.today();
		long todayEarned = dailyCounterStore.get(memberId, today).orElseGet(() -> {
			long fromLedger = pointLedgerRepository.sumAmountByMemberIdAndTxTypeSince(
					memberId, TxType.VOTE_REWARD, KstDates.startOfTodayInSystemZone());
			dailyCounterStore.set(memberId, today, fromLedger, KstDates.untilMidnight());
			return fromLedger;
		});
		return new PointBalanceResponse(wallet.getBalance(), todayEarned, properties.dailyEarnLimit());
	}

	/** 내역: created_at DESC, id DESC 키셋 커서 (idx_point_ledger_member_id_created_at) */
	@Transactional(readOnly = true)
	public CursorPage<PointLedgerItemResponse> ledger(Long memberId, String cursor, Integer size) {
		int pageSize = PageSize.normalize(size);
		Limit limit = Limit.of(pageSize + 1);
		List<PointLedger> page;
		if (cursor == null) {
			page = pointLedgerRepository.findFirstPage(memberId, limit);
		}
		else {
			KeysetCursor keyset = cursorCodec.decode(cursor, KeysetCursor.class);
			page = pointLedgerRepository.findAfter(memberId, keyset.createdAt(), keyset.id(), limit);
		}

		boolean hasNext = page.size() > pageSize;
		List<PointLedger> visible = hasNext ? page.subList(0, pageSize) : page;
		String next = null;
		if (hasNext) {
			PointLedger last = visible.get(visible.size() - 1);
			next = cursorCodec.encode(new KeysetCursor(last.getCreatedAt(), last.getId()));
		}
		return CursorPage.of(visible.stream().map(PointLedgerItemResponse::from).toList(), next, hasNext);
	}

}
