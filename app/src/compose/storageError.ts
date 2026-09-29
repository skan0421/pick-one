// 저장소(S3, 로컬은 MinIO)가 업로드를 거절했을 때의 응답을 읽는다. 앱·웹 공용이고 기기 기능을 쓰지 않는다.
//
// 저장소는 우리 서버와 달리 JSON 이 아니라 XML 로 답한다.
//
//   <Error><Code>SignatureDoesNotMatch</Code><Message>The request signature ...</Message>...</Error>
//
// Code 가 거절한 이유다. HTTP 403 만으로는 이유를 알 수 없어 Code 를 문구와 로그에 남긴다
//   SignatureDoesNotMatch  발급 때와 다르게 보냄 (형식, 크기, 주소의 호스트)
//   AccessDenied           주소가 만료됐거나 권한 없음
//   RequestTimeTooSkewed   기기 시계가 서버와 많이 다름
//
// 주의: 이 파일은 fileTransfer.ts 와 fileTransfer.web.ts 가 함께 쓴다. 공용 코드를 fileTransfer.ts 에 두고
// fileTransfer.web.ts 에서 './fileTransfer' 로 가져오면 웹에서는 자기 자신을 불러온다 (docs/troubleshooting.md 18)
import { ApiError, NETWORK_ERROR } from '../api/errors';
import type { PreparedImage } from './imagePrep';

// 서버가 아니라 앱이 만들어 내는 코드
export const UPLOAD_FAILED = 'UPLOAD_FAILED';

// 플랫폼별 업로드 함수가 지켜야 하는 모양 (Java 의 인터페이스). fileTransfer.ts 와 fileTransfer.web.ts 가 구현한다
export type PutFile = (uploadUrl: string, image: PreparedImage) => Promise<void>;

// 응답 본문에서 <Code> 의 값을 꺼낸다. 없으면 null
export function parseStorageErrorCode(body: string | null | undefined): string | null {
  return tagValue(body, 'Code');
}

// 저장소가 거절한 응답을 예외로 바꾼다. 화면 문구에 HTTP 상태와 Code 가 들어간다
export function uploadFailure(status: number, body: string | null | undefined): ApiError {
  const code = parseStorageErrorCode(body);
  // 업로드 주소는 남기지 않는다. 주소에 서명이 들어 있어, 만료 전에는 그 주소만으로 파일을 올릴 수 있다
  console.warn(`[사진 업로드] 저장소가 거절했습니다. HTTP ${status}, Code=${code ?? '없음'}, Message=${tagValue(body, 'Message') ?? '없음'}`);
  const detail = code ? `HTTP ${status}, ${code}` : `HTTP ${status}`;
  return new ApiError(status, UPLOAD_FAILED, `사진을 올리지 못했습니다. (${detail})`);
}

// 올리려는 파일의 크기가 발급 때 보낸 크기와 다를 때. 그대로 올리면 저장소가 403 으로 거절하므로 올리지 않는다
export function sizeMismatch(expected: number, actual: number): ApiError {
  console.warn(`[사진 업로드] 올릴 파일의 크기가 발급 때와 다릅니다. 발급=${expected}, 실제=${actual}`);
  return new ApiError(0, UPLOAD_FAILED, '사진 크기가 달라졌습니다. 사진을 다시 골라 주세요.');
}

export function storageUnreachable(): ApiError {
  return new ApiError(0, NETWORK_ERROR, '사진 저장소에 연결할 수 없습니다.');
}

function tagValue(body: string | null | undefined, tag: string): string | null {
  if (!body) {
    return null;
  }
  const value = new RegExp(`<${tag}>([^<]*)</${tag}>`).exec(body)?.[1]?.trim();
  return value ? value : null;
}
