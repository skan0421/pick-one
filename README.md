# pick-one

사소한 고민을 올리면 모르는 사람들이 몇 초 만에 골라주는 고민 투표 앱 (백엔드 포트폴리오)

- 기획: [docs/planning.md](docs/planning.md)
- DB 설계: [docs/erd.md](docs/erd.md)

## 기술 스택

Java 17 · Spring Boot 4.1 · Spring Data JPA · Spring Security(JWT) · Flyway · MariaDB 11.4 · Redis 7.4 · Gradle · Docker Compose · Testcontainers

## 로컬 실행

1. 환경변수 파일 준비 (둘 다 git 미추적)
   ```bash
   cp .env.example .env                                                        # DB_PASSWORD, DB_ROOT_PASSWORD 채우기
   cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml   # .env 와 같은 비밀번호 + JWT 비밀키
   ```
   JWT 비밀키(`pickone.jwt.secret`)는 32바이트 이상 임의 문자열입니다. 환경변수 `JWT_SECRET` 으로도 줄 수 있습니다.
2. DB·Redis 기동 (MariaDB 3307, Redis 6380. Refresh 토큰은 Redis 에 저장되므로 Redis 없이는 로그인이 실패합니다)
   ```bash
   docker compose up -d
   ```
3. 앱 실행 (기본 프로필 `local`, 기동 시 Flyway 가 `src/main/resources/db/migration` 을 적용)
   ```bash
   ./gradlew bootRun
   ```

## 테스트

Docker 가 실행 중이어야 합니다. 테스트는 Testcontainers 로 MariaDB 와 Redis 를 직접 띄우므로 별도 설정이 필요 없습니다.

```bash
./gradlew test
```

## 스키마 관리 규칙

- 스키마 변경은 항상 `src/main/resources/db/migration/V{n}__{설명}.sql` 로 추가합니다.
- `spring.jpa.hibernate.ddl-auto=validate` 로 두어 엔티티와 스키마가 어긋나면 기동 시 실패합니다.
