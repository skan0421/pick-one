package com.pickone.hide.service;

import com.pickone.global.crypto.PhoneCipher;
import com.pickone.global.crypto.PhoneNumber;
import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.hide.HideProperties;
import com.pickone.hide.dto.ContactsResponse;
import com.pickone.hide.dto.HideFromContactsResponse;
import com.pickone.hide.repository.ContactHideJdbcRepository;
import com.pickone.hide.repository.HidePendingRepository;
import com.pickone.hide.repository.HideRelationRepository;
import com.pickone.member.domain.Member;
import com.pickone.member.repository.MemberRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지인에게 숨기기 (docs/api.md 7장).
 *
 * 연락처 교체는 한 트랜잭션에서 "기존 전부 삭제 → 정규화·HMAC → 회원 매칭 → hide_relation / hide_pending 벌크 INSERT" 순서다.
 * 원본 번호는 정규화 직후 HMAC 으로 바뀌고, 이 클래스 밖으로 나가지 않으며 로그에는 건수만 남긴다.
 * 휴대폰 형식이 아닌 항목(유선 번호 등)은 건너뛰고, 내 번호는 제외한다 (chk_hide_relation_not_self 와도 충돌).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(HideProperties.class)
public class ContactHideService {

	private final MemberRepository memberRepository;
	private final HideRelationRepository hideRelationRepository;
	private final HidePendingRepository hidePendingRepository;
	private final ContactHideJdbcRepository contactHideJdbcRepository;
	private final PhoneCipher phoneCipher;
	private final HideProperties properties;

	@Transactional
	public ContactsResponse replaceContacts(Long memberId, List<String> phones) {
		if (phones.size() > properties.maxContacts()) {
			throw new BusinessException(ErrorCode.CONTACTS_TOO_MANY);
		}
		Member me = getMember(memberId);

		Set<String> hmacs = new LinkedHashSet<>();
		int skipped = 0;
		for (String raw : phones) {
			try {
				String hmac = phoneCipher.hmac(PhoneNumber.toE164(raw));
				if (!hmac.equals(me.getPhoneHmac())) {
					hmacs.add(hmac);
				}
			}
			catch (BusinessException e) {
				skipped++; // 휴대폰 형식이 아님. 번호는 로그에 남기지 않는다
			}
		}

		contactHideJdbcRepository.deleteAllByOwner(memberId);
		Map<String, Long> matched = contactHideJdbcRepository.findMemberIdsByPhoneHmacs(hmacs);
		List<Long> targets = new ArrayList<>();
		List<String> pendings = new ArrayList<>();
		for (String hmac : hmacs) {
			Long target = matched.get(hmac);
			if (target == null) {
				pendings.add(hmac);
			}
			else if (!target.equals(memberId)) {
				targets.add(target);
			}
		}
		contactHideJdbcRepository.insertRelations(memberId, targets);
		contactHideJdbcRepository.insertPendings(memberId, pendings);

		log.info("연락처 교체: memberId={}, received={}, matched={}, pending={}, skipped={}",
				memberId, hmacs.size(), targets.size(), pendings.size(), skipped);
		return new ContactsResponse(hmacs.size(), targets.size(), pendings.size());
	}

	/** 켜는 순간 피드 필터 조건 4 가 적용된다. 관계 데이터는 건드리지 않는다 */
	@Transactional
	public HideFromContactsResponse setHideFromContacts(Long memberId, boolean enabled) {
		Member me = getMember(memberId);
		me.changeHideFromContacts(enabled);
		return new HideFromContactsResponse(enabled,
				hideRelationRepository.countByIdOwnerId(memberId),
				hidePendingRepository.countByOwnerId(memberId));
	}

	private Member getMember(Long memberId) {
		return memberRepository.findById(memberId)
				.filter(m -> !m.isDeleted())
				.orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
	}

}
