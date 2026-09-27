package com.pickone;

import com.pickone.support.IntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flyway 마이그레이션이 빈 DB 에 정상 적용되는지, 그리고 DB 제약(복합 FK, CHECK)이 실제로 동작하는지 검증한다.
 * (ddl-auto=validate 이므로 컨텍스트가 뜨는 것 자체가 엔티티-스키마 일치 검증이기도 하다)
 * 각 테스트는 트랜잭션 안에서 실행되고 끝나면 롤백된다.
 */
@IntegrationTest
@Transactional
class SchemaMigrationTest {

	private static final List<String> EXPECTED_TABLES = List.of(
			"member", "question", "question_option", "vote",
			"point_wallet", "point_ledger", "hide_relation", "hide_pending",
			"member_block", "report",
			"member_social_account" // V2
	);

	@Autowired
	DataSource dataSource;

	JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		jdbc = new JdbcTemplate(dataSource);
	}

	@Test
	void ERD의_모든_테이블이_생성된다() {
		List<String> tables = jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
				String.class);

		assertThat(tables).containsAll(EXPECTED_TABLES);
	}

	@Test
	void 마이그레이션_이력이_모두_성공_상태다() {
		Integer failed = jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);
		Integer applied = jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);

		assertThat(failed).isZero();
		assertThat(applied).isGreaterThanOrEqualTo(2);
	}

	@Test
	void V2_이후_휴대폰_인증_전_회원은_이메일_비밀번호_휴대폰이_비어_있을_수_있다() {
		assertThatCode(() -> jdbc.update("""
				INSERT INTO member (email, password_hash, nickname, phone_encrypted, phone_hmac,
				                    hide_from_contacts, status, signup_status, created_at, updated_at)
				VALUES (NULL, NULL, 'social-only', NULL, NULL, b'0', 'ACTIVE', 'PENDING_PHONE', NOW(6), NOW(6))
				""")).doesNotThrowAnyException();
	}

	@Test
	void 같은_고민의_선택지로는_투표할_수_있다() {
		long memberId = insertMember("voter1");
		long questionId = insertQuestion(memberId);
		long optionId = insertOption(questionId, 1);

		assertThatCode(() -> insertVote(questionId, optionId, memberId))
				.doesNotThrowAnyException();
	}

	@Test
	void 다른_고민의_선택지로는_투표할_수_없다() {
		long memberId = insertMember("voter2");
		long questionId = insertQuestion(memberId);
		insertOption(questionId, 1);
		long otherQuestionId = insertQuestion(memberId);
		long otherOptionId = insertOption(otherQuestionId, 1);

		// question_id 는 questionId 인데 option 은 otherQuestionId 의 것 → 복합 FK 위반
		assertThatThrownBy(() -> insertVote(questionId, otherOptionId, memberId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 포인트_잔액은_음수가_될_수_없다() {
		long memberId = insertMember("wallet");

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO point_wallet (member_id, balance, version, updated_at) VALUES (?, -1, 0, NOW(6))",
				memberId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 자기_자신을_차단할_수_없다() {
		long memberId = insertMember("blocker");

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO member_block (blocker_id, blocked_id, created_at) VALUES (?, ?, NOW(6))",
				memberId, memberId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 자기_자신에게_고민을_숨길_수_없다() {
		long memberId = insertMember("hider");

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO hide_relation (owner_id, target_member_id, created_at) VALUES (?, ?, NOW(6))",
				memberId, memberId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 선택지_순서는_1에서_4_사이여야_한다() {
		long memberId = insertMember("author");
		long questionId = insertQuestion(memberId);

		assertThatThrownBy(() -> insertOption(questionId, 5))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---- 테스트 데이터 삽입 헬퍼 ----

	private long insertMember(String key) {
		jdbc.update("""
				INSERT INTO member (email, password_hash, nickname, phone_encrypted, phone_hmac,
				                    hide_from_contacts, status, created_at, updated_at)
				VALUES (?, 'hash', ?, 'enc', ?, b'0', 'ACTIVE', NOW(6), NOW(6))
				""", key + "@test.com", key, hmacOf(key));
		return lastInsertId();
	}

	private long insertQuestion(long memberId) {
		jdbc.update("""
				INSERT INTO question (member_id, question_type, content, status, created_at, updated_at)
				VALUES (?, 'TEXT', '점심 뭐 먹지', 'ACTIVE', NOW(6), NOW(6))
				""", memberId);
		return lastInsertId();
	}

	private long insertOption(long questionId, int sortOrder) {
		jdbc.update("""
				INSERT INTO question_option (question_id, sort_order, content) VALUES (?, ?, '선택지')
				""", questionId, sortOrder);
		return lastInsertId();
	}

	private void insertVote(long questionId, long optionId, long memberId) {
		jdbc.update("""
				INSERT INTO vote (question_id, option_id, member_id, created_at) VALUES (?, ?, ?, NOW(6))
				""", questionId, optionId, memberId);
	}

	private long lastInsertId() {
		Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		assertThat(id).isNotNull();
		return id;
	}

	/** phone_hmac 은 CHAR(64) 유니크이므로 키마다 다른 64자 문자열을 만든다 */
	private static String hmacOf(String key) {
		String padded = key + "0".repeat(64);
		return padded.substring(0, 64);
	}

}
