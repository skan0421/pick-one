package com.pickone.hide.repository;

import com.pickone.hide.HideProperties;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 연락처 교체용 벌크 접근 (docs/api.md 7.1). 한 번에 최대 5,000건이라 JPA 대신 JdbcTemplate 을 쓴다.
 * - hide_pending 은 IDENTITY 라 Hibernate 가 INSERT 를 배치로 묶지 못하고 건마다 왕복한다. batchUpdate 는 한 번에 보낸다
 * - 회원 매칭도 엔티티를 5,000개 로드하지 않고 (id, phone_hmac) 두 컬럼만 IN 절로 읽는다 (uk_member_phone_hmac)
 * 호출자의 @Transactional 커넥션을 그대로 쓰므로 실패 시 함께 롤백된다. 번호 원문은 이 클래스에 들어오지 않는다 (HMAC 만).
 */
@Repository
@RequiredArgsConstructor
@EnableConfigurationProperties(HideProperties.class)
public class ContactHideJdbcRepository {

	private final JdbcTemplate jdbc;
	private final HideProperties properties;

	/** 기존 숨김 대상 전체 삭제 (전체 교체의 첫 단계) */
	public void deleteAllByOwner(Long ownerId) {
		jdbc.update("DELETE FROM hide_relation WHERE owner_id = ?", ownerId);
		jdbc.update("DELETE FROM hide_pending WHERE owner_id = ?", ownerId);
	}

	/** phone_hmac → member_id. 인증을 마친 회원만 phone_hmac 이 있다 */
	public Map<String, Long> findMemberIdsByPhoneHmacs(Collection<String> phoneHmacs) {
		Map<String, Long> result = new HashMap<>();
		for (List<String> chunk : chunks(new ArrayList<>(phoneHmacs))) {
			String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
			jdbc.query("SELECT id, phone_hmac FROM member WHERE deleted_at IS NULL AND phone_hmac IN (" + placeholders + ")",
					rs -> { result.put(rs.getString("phone_hmac"), rs.getLong("id")); },
					chunk.toArray());
		}
		return result;
	}

	public void insertRelations(Long ownerId, List<Long> targetMemberIds) {
		for (List<Long> chunk : chunks(targetMemberIds)) {
			jdbc.batchUpdate("INSERT INTO hide_relation (owner_id, target_member_id, created_at) VALUES (?, ?, NOW(6))",
					chunk, chunk.size(), (PreparedStatement ps, Long targetId) -> {
						ps.setLong(1, ownerId);
						ps.setLong(2, targetId);
					});
		}
	}

	public void insertPendings(Long ownerId, List<String> phoneHmacs) {
		for (List<String> chunk : chunks(phoneHmacs)) {
			jdbc.batchUpdate("INSERT INTO hide_pending (owner_id, phone_hmac, created_at) VALUES (?, ?, NOW(6))",
					chunk, chunk.size(), (PreparedStatement ps, String hmac) -> {
						ps.setLong(1, ownerId);
						ps.setString(2, hmac);
					});
		}
	}

	private <T> List<List<T>> chunks(List<T> items) {
		List<List<T>> chunks = new ArrayList<>();
		int size = properties.batchSize();
		for (int i = 0; i < items.size(); i += size) {
			chunks.add(items.subList(i, Math.min(i + size, items.size())));
		}
		return chunks;
	}

}
