# pick-one 앱 (Expo)

pick-one 백엔드에 붙는 React Native(Expo) 앱입니다. 같은 코드로 휴대폰 앱과 웹이 함께 동작합니다.
지금은 가입 → 휴대폰 인증 → 피드에서 투표, 고민 올리기(글형·사진형), 내 고민 보기·삭제까지 됩니다. 디자인보다 실제 서버와 맞물려 동작하는 것을 우선했습니다.

## 기술 선택

| 항목 | 선택 | 이유 |
|---|---|---|
| 프레임워크 | Expo SDK 57, TypeScript | 기획(`docs/planning.md`)의 "앱 우선, 같은 코드로 웹 배포" |
| 화면 이동 | Expo Router | 파일 경로가 곧 화면 주소. 설정 파일 없이 폴더 구조만 보면 화면 구성을 알 수 있음 |
| 서버 상태 | TanStack Query | 로딩·오류·캐시를 직접 관리하지 않아도 됨 |
| UI | React Native Paper | 의존성 하나로 입력칸·버튼·카드가 갖춰져 있고 앱과 웹에서 똑같이 동작. 빌드 설정을 추가할 필요가 없음 (NativeWind, Tamagui 는 바벨·번들러 설정이 필요) |
| HTTP | 내장 `fetch` | 필요한 기능(헤더 첨부, 재발급)이 작아 라이브러리 없이 직접 작성 |

## 실행 방법

필요: Node.js 22 이상. 백엔드가 먼저 떠 있어야 합니다 ([루트 README 의 로컬 실행](../README.md#로컬-실행)).

```bash
cd app
npm install
cp .env.example .env      # 서버 주소 설정
npx expo start --web      # 웹 브라우저로 실행 (http://localhost:8081)
```

`.env` 를 고치면 개발 서버를 껐다가 다시 켜야 반영됩니다.

### 휴대폰에서 실행할 때: localhost 대신 PC 의 내부 IP

휴대폰에 [Expo Go](https://expo.dev/go) 를 설치하고 `npx expo start` 가 보여 주는 QR 코드를 찍으면 됩니다.
이때 `.env` 의 서버 주소는 `localhost` 가 아니라 **PC 의 내부 IP** 여야 합니다.

```
EXPO_PUBLIC_API_URL=http://192.168.0.12:8080
```

- `localhost` 는 "이 기기 자신"을 뜻합니다. 휴대폰에서 `localhost:8080` 을 부르면 PC 가 아니라 휴대폰 안에서 서버를 찾습니다.
- 내부 IP 확인: Windows 는 `ipconfig` 의 "IPv4 주소", macOS 는 `ipconfig getifaddr en0`
- 휴대폰과 PC 가 같은 Wi-Fi 에 있어야 합니다.
- 연결이 안 되면 Windows 방화벽이 8080 포트의 들어오는 연결을 막고 있는지 확인합니다.
- 웹과 달리 앱에는 CORS 제한이 없으므로 서버의 허용 origin 설정은 바꾸지 않아도 됩니다.
- 사진을 올리거나 보려면 저장소 주소도 바꿔야 합니다. 아래 [휴대폰에서 사진 올리기·보기](#휴대폰에서-사진-올리기보기-저장소-주소) 를 봅니다.

### 집 밖에서 휴대폰으로 접속하기 (Tailscale)

내부 IP 는 같은 Wi-Fi 안에서만 통합니다. 밖에서(LTE, 다른 Wi-Fi) 휴대폰으로 PC 의 개발 서버에 붙으려면 [Tailscale](https://tailscale.com) 을 씁니다.
Tailscale 은 내 기기들끼리만 통하는 사설망을 만들어 주고, 기기마다 어디서든 바뀌지 않는 `100.x.y.z` 주소를 줍니다.

아래의 `100.101.102.103` 은 예시입니다. 자기 PC 의 주소로 바꿔 넣습니다.

1. PC 와 휴대폰에 Tailscale 을 설치하고 **같은 계정**으로 로그인합니다.
2. PC 의 Tailscale IP 를 확인합니다. 트레이 아이콘을 누르거나 `tailscale ip -4` 를 실행합니다.
3. `app/.env` 의 서버 주소를 그 IP 로 바꿉니다.
   ```
   EXPO_PUBLIC_API_URL=http://100.101.102.103:8080
   ```
4. PowerShell 에서 개발 서버를 띄웁니다.
   ```powershell
   cd app
   $env:REACT_NATIVE_PACKAGER_HOSTNAME="100.101.102.103"; npx expo start
   ```
   이 환경변수가 없으면 QR 코드에 PC 의 내부 IP 가 들어가서, 같은 Wi-Fi 가 아닌 휴대폰은 앱 코드를 받아 오지 못합니다.
   `$env:` 로 넣은 값은 그 PowerShell 창을 닫으면 사라집니다.
5. 휴대폰의 Expo Go 로 QR 코드를 찍습니다. 휴대폰의 Tailscale 이 켜져 있어야 합니다.

연결이 안 될 때 확인할 것:

- **Windows 방화벽**: 처음 실행할 때 뜨는 "액세스 허용" 창에서 Node.js(개발 서버, 8081)와 Java(백엔드, 8080)를 허용해야 합니다. 창을 놓쳤다면 "Windows Defender 방화벽 → 앱 허용" 에서 두 항목을 허용합니다.
- **.env 를 고친 뒤 개발 서버를 다시 시작했는지**: 서버 주소는 시작할 때 한 번만 읽습니다.
- **휴대폰 브라우저로 웹 버전을 열 때만** 백엔드의 CORS 설정이 필요합니다. 주소가 `http://100.101.102.103:8081` 로 바뀌므로 백엔드를 `CORS_ALLOWED_ORIGINS=http://localhost:8081,http://100.101.102.103:8081` 과 함께 실행합니다. Expo Go 앱은 브라우저가 아니라서 해당하지 않습니다.
- **사진을 올릴 수 없거나 올린 사진이 안 보일 때**: 저장소 주소가 `localhost:9000` 인 상태입니다. 바로 아래 절대로 바꿉니다. 샘플 데이터의 사진은 외부 주소라 설정과 관계없이 보입니다.

### 휴대폰에서 사진 올리기·보기: 저장소 주소

사진은 백엔드를 거치지 않고 앱이 저장소(로컬은 MinIO, 9000 포트)와 직접 주고받습니다. 그래서 서버 주소(`EXPO_PUBLIC_API_URL`)와 별개로 **저장소 주소**도 휴대폰이 닿을 수 있는 주소여야 합니다.

기본 설정은 `http://localhost:9000` 이라 휴대폰에서는 두 가지가 모두 안 됩니다.

- 올리기: 백엔드가 만들어 준 업로드 주소가 `http://localhost:9000/...` 이라 휴대폰은 자기 자신에게 보내게 됩니다.
- 보기: 고민에 저장된 사진 주소도 `http://localhost:9000/...` 입니다.

앱에서 주소의 `localhost` 만 PC 의 IP 로 바꿔 보내는 방법은 쓸 수 없습니다. 업로드 주소의 서명에 호스트가 들어 있어서, 호스트를 바꾸면 저장소가 403 으로 거절합니다 (실제로 확인함).

**백엔드 코드를 고치지 않고 설정만으로 해결됩니다.** 백엔드는 설정의 저장소 주소로 업로드 주소와 사진 주소를 만들기 때문에, 그 값을 PC 의 IP 로 바꾸면 됩니다. 둘 중 하나를 고릅니다. (`100.101.102.103` 은 예시. 같은 Wi-Fi 에서 쓸 때는 PC 의 내부 IP)

방법 1. 환경변수로 그때만 바꾸기 (설정 파일을 건드리지 않음)

```powershell
$env:PICKONE_STORAGE_ENDPOINT="http://100.101.102.103:9000"; ./gradlew bootRun
```

방법 2. `src/main/resources/application-local.yml` 을 고치기 (git 에 올라가지 않는 파일)

```yaml
pickone:
  storage:
    endpoint: http://100.101.102.103:9000
```

| 확인한 것 (PC 에서, 방법 1) | 결과 |
|---|---|
| 업로드 주소와 사진 주소의 호스트 | `100.x.y.z:9000` 으로 바뀜 |
| 그 주소로 올리기 → 고민 등록 → 사진 보기 | 모두 성공 |
| 같은 PC 의 웹 브라우저에서 사진형 등록 | 성공. PC 도 자기 Tailscale IP 로 저장소에 닿으므로 웹과 휴대폰을 함께 쓸 수 있음 |
| 주소의 호스트만 바꿔서 올리기 | 403 |

알아 둘 것:

- **`STORAGE_ENDPOINT` 환경변수는 로컬에서 듣지 않습니다.** `application-local.yml` 에 `endpoint` 가 직접 적혀 있어 그 값이 우선합니다. 위의 `PICKONE_STORAGE_ENDPOINT` 는 설정 이름(`pickone.storage.endpoint`)을 그대로 환경변수로 쓴 것이라 파일보다 우선합니다.
- **`public-base-url` 은 따로 정하지 않아도 됩니다.** 비워 두면 `{endpoint}/{bucket}` 이 되어 업로드 주소와 사진 주소가 같은 호스트를 씁니다.
- **주소를 바꾸기 전에 올린 사진은 옛 주소 그대로입니다.** 사진 주소는 등록할 때 DB 에 저장되므로, `localhost:9000` 으로 올린 사진은 휴대폰에서 계속 안 보입니다. 주소를 바꾼 뒤 새로 올립니다.
- **설정을 되돌리면 그 사이 올린 사진은 Tailscale 이 켜져 있을 때만 보입니다.**
- **방화벽**: 9000 포트로 들어오는 연결이 허용돼 있어야 합니다. 저장소는 Docker 가 띄우므로 Node.js·Java 와 달리 "Docker Desktop Backend" 항목입니다. 휴대폰 브라우저에서 `http://100.101.102.103:9000/minio/health/live` 를 열어 오류 없이 빈 화면이 나오면 닿는 것입니다. 닿지 않으면 "Windows Defender 방화벽 → 앱 허용" 에서 Docker Desktop Backend 를 허용하거나 9000 포트의 인바운드 규칙을 추가합니다. 실제 안드로이드 폰에서 Tailscale 로 저장소에 닿아 사진이 올라가는 것을 확인했습니다.
- 저장소 쪽 CORS 는 따로 설정할 것이 없습니다. MinIO 가 요청한 origin 을 그대로 허용합니다.

### 웹에서 실행할 때: CORS

브라우저는 서버가 허용한 주소에서 온 요청만 통과시킵니다. 백엔드의 기본 허용 주소는 `http://localhost:8081` (Expo 웹 개발 서버의 기본 포트)입니다.
다른 포트나 주소로 띄웠다면 백엔드를 `CORS_ALLOWED_ORIGINS` 환경변수와 함께 실행합니다.

### 인증번호 확인

로컬 서버는 문자를 보내지 않습니다. 인증번호는 백엔드 로그의 `[SMS 본문]` 줄에 찍힙니다.

## 로컬 샘플 데이터

피드에는 내가 올린 고민이 나오지 않으므로, 투표를 시험하려면 다른 회원의 고민이 필요합니다.
저장소 루트의 `scripts/local/` 에 로컬 전용 샘플 데이터가 있습니다.

| 파일 | 내용 |
|---|---|
| `scripts/local/sample-data.sql` | 회원 3명(`샘플_민지`, `샘플_준호`, `샘플_서연`)과 고민 24개 (글형 16, 사진형 8, 그중 상단 노출 2) |
| `scripts/local/load-sample-data.ps1` | 위 SQL 을 로컬 DB 컨테이너(`pickone-mariadb`)에 넣는 스크립트 |

### 넣는 방법

`docker compose up -d` 로 DB 가 떠 있고, 백엔드를 한 번 이상 실행해 테이블이 만들어진 상태여야 합니다. 저장소 루트에서 실행합니다.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/local/load-sample-data.ps1
```

Git Bash 에서는 다음 두 줄입니다. (`MSYS_NO_PATHCONV=1` 이 없으면 Git Bash 가 `/tmp/...` 를 Windows 경로로 바꿔 버립니다)

```bash
MSYS_NO_PATHCONV=1 docker cp scripts/local/sample-data.sql pickone-mariadb:/tmp/pickone-sample-data.sql
MSYS_NO_PATHCONV=1 docker exec pickone-mariadb sh -c 'MYSQL_PWD=$MARIADB_PASSWORD exec mariadb --default-character-set=utf8mb4 --table -u$MARIADB_USER $MARIADB_DATABASE < /tmp/pickone-sample-data.sql'
```

끝에 아래 표가 나오면 정상입니다. `hex` 가 `EC8398` 이면 한글이 깨지지 않고 들어간 것입니다.

```
| members         |   3 | EC8398 |
| questions_text  |  16 |        |
| questions_image |   8 |        |
| options         |  60 |        |
| boosted         |   2 |        |
```

그다음 앱에서 **직접 가입한 계정**으로 로그인하면 피드에 샘플 고민이 나옵니다. 샘플 회원은 비밀번호가 없어 로그인할 수 없습니다.

### 알아 둘 것

- **여러 번 실행해도 됩니다.** 샘플 회원의 고민과 거기 달린 투표를 지우고 새로 넣으므로, 다 투표한 뒤 다시 실행하면 같은 고민에 또 투표할 수 있습니다. 투표로 받은 포인트는 지워지지 않습니다.
- **하루 적립 한도 확인**: 하루에 50P 까지만 적립됩니다. 샘플을 세 번 넣어 가며 51번째 투표를 하면 "오늘 적립 한도(50P) 도달" 안내를 볼 수 있습니다.
- **올린 시각은 넣은 시점 기준 2분 전 ~ 23시간 전**입니다. 피드는 최신순이라, 시간이 지나 샘플이 다른 고민 뒤로 밀렸다면 다시 넣으면 됩니다.
- **사진은 외부 주소**(picsum.photos)라 인터넷이 필요합니다. 받지 못하면 "사진을 불러올 수 없어요" 가 나오고 투표는 그대로 할 수 있습니다.
- DB 계정과 비밀번호는 컨테이너 안의 환경변수를 쓰므로 스크립트에 비밀번호가 들어 있지 않습니다.

### 왜 SQL 스크립트인가

| 방법 | 판단 |
|---|---|
| **별도 SQL 스크립트 (선택)** | 운영 코드·운영 빌드와 완전히 분리됩니다. 실행하지 않으면 아무 일도 일어나지 않습니다 |
| `local` 프로필 전용 시더 (`@Profile("local")` + `ApplicationRunner`) | 서버를 켤 때 자동으로 들어가 편하지만, 시험용 코드가 `src/main` 에 들어가 운영 jar 에 함께 실립니다. 프로필 설정을 잘못하면 운영에서 실행될 수 있습니다 |
| Flyway 마이그레이션 | 마이그레이션은 모든 환경에 똑같이 적용되므로 시험 데이터가 운영 DB 에도 들어갑니다 |

## 피드와 투표

| 동작 | 설명 |
|---|---|
| 카드 | 한 번에 한 장. 작성자, 올린 시각, 상단 노출 표시, 본문, 선택지 |
| 투표 | 선택지를 누르면 `POST /questions/{id}/votes`. 요청 중에는 버튼이 막힙니다 |
| 결과 | 투표한 뒤에만 선택지별 막대와 % 가 보입니다 (`docs/api.md` 4.2 의 "투표 전 결과 비공개") |
| 다음 카드 | 결과 화면에서 "다음" 버튼 또는 위로 밀기 |
| 밀어서 고르기 | 선택지가 2개인 글형만. 왼쪽 = 첫 번째, 오른쪽 = 두 번째. 버튼은 항상 함께 있습니다 |
| 이어 받기 | 남은 카드가 3장 이하가 되면 다음 쪽을 미리 받습니다. 같은 고민이 두 번 오면 한 번만 보여 줍니다 |
| 포인트 | 상단에 잔액. 투표하면 "+1P" 가 잠깐 뜨고 잔액이 갱신됩니다 |

투표가 실패했을 때는 서버의 에러 코드에 따라 다르게 처리합니다.

| 에러 코드 | 처리 |
|---|---|
| `VOTE_ALREADY_VOTED` | 에러로 보지 않고 `GET /questions/{id}/results` 로 결과를 보여 줍니다 |
| `QUESTION_NOT_FOUND`, `QUESTION_CLOSED` 등 다시 해도 안 되는 것 | 짧게 안내하고 다음 카드로 넘어갑니다 |
| 네트워크 오류, `POINT_WALLET_CONFLICT` 등 일시적인 것 | 안내하고 같은 카드에서 다시 고를 수 있게 합니다 |

### 웹에서의 스와이프

웹에서는 마우스로 끌어도 스와이프가 됩니다. 헤드리스 Chromium 에서 마우스 끌기로 확인했고, 버튼 위에서 끌기 시작해도 투표 요청은 한 번만 나갑니다.
휴대폰 브라우저의 터치와 실제 기기(Expo Go)에서는 아직 확인하지 못했습니다. 스와이프가 되지 않는 환경에서도 버튼만으로 모든 동작을 할 수 있습니다.

## 고민 올리기

| 항목 | 규칙 |
|---|---|
| 유형 | 글형 / 사진형 |
| 본문 | 1~300자. 글자 수 표시 |
| 글형 선택지 | 2~4개(추가·삭제 버튼), 각 1~20자. 같은 선택지는 올릴 수 없음 |
| 사진형 | 사진 정확히 2장. 앨범에서 고르고, 웹에서는 파일 선택 창 |

제출하면 앱이 먼저 검사하고, 통과한 것만 서버에 보냅니다. 서버가 거절하면 서버의 오류를 해당 입력칸 아래에 보여 줍니다.

| 서버 응답 | 보여 주는 곳 |
|---|---|
| `VALIDATION_ERROR` 의 `content` | 본문 아래 |
| `VALIDATION_ERROR` 의 `options[1].content` | 두 번째 선택지 아래 |
| `QUESTION_OPTION_COUNT_INVALID`, `QUESTION_OPTION_TYPE_MISMATCH`, `IMAGE_URL_INVALID` | 선택지 목록 아래 |
| 그 밖 (서버 연결 실패 등) | 제출 버튼 위 |

등록되면 입력을 비우고 내 고민 탭으로 이동합니다. 목록이 최신순이라 방금 올린 고민이 맨 위에 옵니다.

### 사진 업로드 흐름

```
사진 고르기 ─> 준비(필요하면 JPEG 변환·축소) ─> [올리기 버튼]
   사진마다:  POST /uploads/images (형식, 크기)  ─> 업로드 주소 받음
              PUT  업로드 주소 (사진 내용)         ─> 저장소로 직접
   두 장 모두 성공 ─> POST /questions (사진 주소 2개)
```

- **형식과 크기가 정확히 같아야 합니다.** 업로드 주소의 서명에 발급 때 보낸 형식과 크기가 들어 있어, 1바이트라도 다르면 저장소가 403 으로 거절합니다. 그래서 선택 창이 알려 주는 크기를 믿지 않고, 실제로 올릴 파일(변환했다면 변환한 뒤)의 크기를 발급에 보내고 그 파일을 그대로 올립니다.
- **올리는 방식은 앱과 웹이 다릅니다.** 휴대폰 앱은 기기의 파일을 그대로 보냅니다 (`expo-file-system` 의 `File.upload`. 파일 길이가 `Content-Length` 로 나갑니다). 웹은 브라우저 메모리의 내용(Blob)을 `fetch` 로 보냅니다. 앱에서 웹과 같은 방식으로 올리면 저장소가 403 으로 거절했습니다 (`docs/troubleshooting.md` 23).
- **저장소가 거절하면 이유가 문구에 나옵니다.** 예: "사진을 올리지 못했습니다. (HTTP 403, SignatureDoesNotMatch)". 같은 내용이 Expo 터미널(웹은 브라우저 콘솔)에 `[사진 업로드]` 로 시작하는 경고로도 남습니다.

  | Code | 뜻 | 확인할 것 |
  |---|---|---|
  | `SignatureDoesNotMatch` | 발급 때와 다르게 보냄 | 형식, 크기, 주소의 호스트 (위 "저장소 주소") |
  | `AccessDenied` | 주소가 만료됐거나 권한 없음 | 발급 후 5분이 지났는지 |
  | `RequestTimeTooSkewed` | 기기 시계가 서버와 많이 다름 | 휴대폰과 PC 의 시각 |
  | `MissingContentLength` | 크기를 밝히지 않고 보냄 | 올리는 방식 |
- **변환하는 경우**: 서버가 받지 않는 형식(아이폰 HEIC 등), 5MB 초과, 긴 변 1600 픽셀 초과. JPEG 로 바꾸며 긴 변 1600 → 1280 → 1024 → 800 순서로 5MB 이하가 될 때까지 줄입니다. 그 밖의 JPEG·PNG·WebP 는 그대로 올립니다.
- **한 장이라도 실패하면 등록하지 않습니다.** 입력은 그대로 남고, 다시 누르면 실패한 사진만 다시 올립니다.
- 올리는 동안 진행 표시가 보이고 제출 버튼과 사진 칸이 막힙니다. 진행은 "몇 장 끝났는지"까지만 보여 줍니다 (바이트 단위 진행률은 아님).
- **웹에서 HEIC**: Chrome 등 HEIC 를 풀지 못하는 브라우저에서는 변환할 수 없어 "변환할 수 없는 사진 형식" 안내가 나옵니다. 휴대폰 앱에서는 기기가 변환합니다.

## 내 고민

| 동작 | 설명 |
|---|---|
| 목록 | 최신순. 본문, 유형, 상태(진행 중 / 숨김 / 종료), 참여 수, 선택지별 결과 막대. 막대는 피드와 같은 컴포넌트 |
| 새로고침 | 당겨서 새로고침. 웹 브라우저에는 이 동작이 없어 위쪽의 새로고침 버튼을 씁니다 |
| 이어 받기 | 목록 끝에 가까워지면 다음 쪽을 받습니다 |
| 삭제 | 확인 창 → `DELETE /questions/{id}` → 목록에서 제거. 이미 삭제된 고민이면 에러 없이 목록에서만 뺍니다 |

상단 노출(boost)은 표시만 하고, 쓰는 버튼은 아직 없습니다.

## 확인하지 못한 것

웹(헤드리스 Chromium)에서는 실제 서버·저장소에 붙여 확인했습니다. 실제 안드로이드 폰(Expo Go, Tailscale 경유)에서는 사진 2장 고르기 → 업로드 → 사진형 고민 등록을 확인했습니다 (`docs/troubleshooting.md` 23). 아래는 확인하지 못했습니다.

- 휴대폰 앱에서 HEIC → JPEG 변환
- 아이폰에서의 사진 고르기·업로드
- 피드의 터치 스와이프

## 검사 명령

```bash
npm run typecheck   # 타입 검사 (tsc --noEmit)
npm run lint        # 린트
npm test            # 단위 테스트 (API 클라이언트, 피드, 올리기 입력 규칙, 사진 준비·업로드 흐름, 플랫폼별 업로드, 내 고민 목록)
```

CI 는 타입 검사와 린트만 돌립니다. 단위 테스트는 로컬에서 실행합니다.

단위 테스트는 화면을 그리지 않습니다. 화면과 웹 전용 파일은 브라우저로 직접 열어 확인합니다 (`docs/troubleshooting.md` 18).

## 폴더 구조

```
app/
  .env.example              서버 주소 예시
  app.json                  앱 이름, 아이콘 등 Expo 설정
  src/
    app/                    화면. 파일 경로가 곧 주소
      _layout.tsx           최상위 틀. 로그인 상태에 따라 열 수 있는 화면을 정함
      (auth)/               로그인 전
        login.tsx             /login
        signup.tsx            /signup
      verify-phone.tsx      /verify-phone  휴대폰 인증
      (tabs)/               로그인 후. 하단 탭 4개
        index.tsx             /              피드
        post.tsx              /post          올리기
        my-questions.tsx      /my-questions  내 고민
        my.tsx                /my            마이 (로그아웃)
    api/                    서버 호출
      client.ts               공통 요청 함수. 헤더 첨부, 에러 변환, 재발급
      errors.ts               ApiError
      config.ts               서버 주소
      auth.ts, phone.ts, members.ts, questions.ts, votes.ts, points.ts, uploads.ts   API 별 함수와 타입
    auth/                   로그인 상태
      AuthContext.tsx         현재 상태를 모든 화면에 제공
      session.ts              메모리에 든 토큰
      tokens.ts               토큰 타입과 읽기 함수 (앱·웹 공용)
      tokenStorage.ts         토큰 저장 (앱)
      tokenStorage.web.ts     토큰 저장 (웹)
    feed/                   피드와 투표
      feedState.ts            상태와 전이 규칙. 순수 함수
      voteFlow.ts             투표 한 번의 흐름. 에러 코드별 처리
      feedController.ts       위 둘과 서버 호출을 엮음
      useFeed.ts              feedController 를 화면에 연결
      time.ts                 서버 시각(KST) 해석, "3분 전" 표시
      swipe.ts                스와이프 방향 판정
      SwipeArea.tsx           스와이프를 알아채는 영역
      QuestionCard.tsx        카드 한 장
      ResultBars.tsx          결과 막대
    compose/                고민 올리기
      composeRules.ts         입력 규칙, 서버 오류를 입력칸으로 나누기. 순수 함수
      imagePrep.ts            사진을 변환할지, 얼마나 줄일지
      uploadFlow.ts           발급 → 올리기 → 등록의 순서
      imageTools.ts           사진 고르기·변환 (기기 기능)
      fileTransfer.ts         파일 크기 읽기, 저장소로 올리기 (앱)
      fileTransfer.web.ts     파일 읽기, 저장소로 올리기 (웹)
      storageError.ts         저장소가 거절한 이유 읽기 (앱·웹 공용). 순수 함수
      ComposeForm.tsx         올리기 화면
      ImageSlots.tsx          사진 두 칸
    mine/                   내 고민
      myQuestions.ts          목록 데이터 다루기. 순수 함수
      queryKeys.ts            목록 캐시의 키
      MyQuestionsList.tsx     목록 화면과 삭제 확인 창
      MyQuestionCard.tsx      고민 한 건
    __tests__/              단위 테스트
```

`feed/` 의 `feedState`, `voteFlow`, `feedController`, `time`, `swipe`, `compose/` 의 `composeRules`, `imagePrep`, `uploadFlow`, `mine/` 의 `myQuestions` 는 React 도 기기 기능도 쓰지 않습니다. 그래서 화면 없이 단위 테스트합니다.

Java/Spring 에 빗대면 다음과 같습니다.

| 여기 | Spring 에서 비슷한 것 |
|---|---|
| `src/app/` 의 파일 경로 | `@RequestMapping` 경로 |
| `_layout.tsx` 의 `Stack.Protected` | Spring Security 의 `requestMatchers(...)` |
| `api/*.ts` 의 `type` | DTO (`record`) |
| `api/client.ts` | `RestClient` + 인터셉터 |
| `AuthContext` | `SecurityContextHolder` |
| `Promise`, `async/await` | `CompletableFuture` |
| `tokenStorage.ts` / `.web.ts`, `fileTransfer.ts` / `.web.ts` | `@Profile` 로 갈아 끼우는 구현체 |
| `feed/feedState.ts` 의 상태 | 불변 객체 (`record`). 고치지 않고 새로 만듦 |
| `feed/feedState.ts` 의 `feedReducer` | 상태 기계의 전이 함수: (현재 상태, 일어난 일) → 다음 상태 |
| `feed/feedController.ts` | `@Service`. 서버 호출 함수를 생성자로 주입받음 |
| `app/(tabs)/index.tsx` | `@Controller`. 조립만 하고 규칙은 모름 |
| `useQuery` 의 `queryKey`, `invalidateQueries` | `@Cacheable` 의 key, `@CacheEvict` |
| 테스트의 `jest.fn()` | Mockito 의 `mock()` |
| `compose/composeRules.ts` | Bean Validation + `Validator`. 서버의 `@Size`, `@NotBlank` 와 같은 규칙 |
| `compose/imagePrep.ts` 의 `ImageTools`, `uploadFlow.ts` 의 `UploadApi` | 포트(인터페이스). 서버의 `ImageStorage`, `SmsSender` 와 같은 분리 |
| `compose/imageTools.ts`, `compose/fileTransfer.ts` | 어댑터(구현체). 서버의 `S3ImageStorage` |
| `useInfiniteQuery` | 커서로 `Slice` 를 이어 받아 쌓아 두는 캐시 |
| `useRef` 로 만든 제출 잠금 | `AtomicBoolean` (단일 스레드라 일반 값으로 충분) |

## 화면 흐름

서버의 가입 상태(`docs/api.md` 2.1)를 그대로 따릅니다.

```
로그인 안 됨 ──가입/로그인──> PENDING_PHONE ──휴대폰 인증──> ACTIVE
(로그인·가입 화면)            (휴대폰 인증 화면)             (하단 탭)
```

화면을 직접 이동시키는 코드는 거의 없습니다. 로그인 상태가 바뀌면 최상위 레이아웃이 그 상태에서 열 수 있는 화면으로 보냅니다.

## 토큰 재발급

access 토큰(30분)이 만료되면 서버는 401 `AUTH_EXPIRED_TOKEN` 을 돌려줍니다. 클라이언트는 refresh 토큰으로 새 토큰을 받고 원래 요청을 한 번 다시 보냅니다.

**재발급은 동시에 한 번만 나갑니다.** 서버는 이미 쓴 refresh 토큰이 다시 오면 탈취로 보고 그 회원의 모든 기기를 로그아웃시킵니다(`docs/api.md` 1.3). 한 화면이 요청 3개를 동시에 보내 셋 다 401 을 받았을 때 각자 재발급하면 그 상황이 됩니다. 그래서 진행 중인 재발급이 있으면 새로 보내지 않고 그 결과를 함께 기다립니다. 재발급이 끝난 뒤 늦게 도착한 401 은 재발급 없이 새 토큰으로 재시도만 합니다.

## 토큰 저장과 보안상 트레이드오프

| 플랫폼 | 저장소 | 특징 |
|---|---|---|
| 앱 (iOS/Android) | `expo-secure-store` | iOS 키체인 / Android Keystore 로 암호화 저장 |
| 웹 | `sessionStorage` | 암호화되지 않음. 아래 참고 |

웹에는 앱의 보안 저장소에 해당하는 것이 없어 선택지마다 단점이 있습니다.

- **httpOnly 쿠키**: 자바스크립트가 읽을 수 없어 가장 안전하지만, 지금 서버는 refresh 토큰을 요청 본문으로 받으므로 쓸 수 없습니다. 쓰려면 서버가 쿠키를 발급하고 CSRF 대책을 세워야 합니다.
- **localStorage**: 탭을 닫아도 로그인이 유지되지만 XSS 에 노출되고, 탭끼리 공유되므로 두 탭이 같은 refresh 토큰으로 동시에 재발급해 전 기기 로그아웃이 될 수 있습니다.
- **sessionStorage (선택)**: 탭마다 따로 저장되고 탭을 닫으면 지워집니다.

sessionStorage 를 고른 대가와 한계는 다음과 같습니다.

- XSS 에는 여전히 노출됩니다. 페이지에서 악성 스크립트가 실행되면 토큰을 읽을 수 있습니다.
- 탭을 닫거나 새 탭을 열면 다시 로그인해야 합니다.
- 브라우저 탭 복제 시 sessionStorage 가 복사되어 두 탭이 같은 refresh 토큰을 가질 수 있습니다. 이 상태에서 두 탭이 각각 재발급하면 서버가 재사용으로 감지해 전 기기 로그아웃이 됩니다. "재발급은 한 번만" 규칙은 탭 하나 안에서만 지켜집니다.

운영 배포 전에는 웹용 refresh 토큰을 httpOnly 쿠키로 옮기는 것을 검토해야 합니다.
