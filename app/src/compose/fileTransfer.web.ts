// 파일 읽기와 저장소로 올리기 (웹). 휴대폰 앱은 fileTransfer.ts.
//
// 웹에는 기기의 파일 주소가 없다. 고른 사진은 브라우저 메모리에 있고(blob: 또는 data: 주소),
// fetch 로 읽어 Blob 으로 만든 뒤 그 Blob 을 올린다. Blob 은 파일 내용 덩어리다 (Java 의 byte[] 에 형식 정보가 붙은 것)
//
// 주의: 여기서 './fileTransfer' 를 가져오면 안 된다. 웹에서는 이 파일 자신을 가리킨다 (docs/troubleshooting.md 18).
// 공용 코드는 storageError.ts 에 있다
import { TimeoutError, UPLOAD_TIMEOUT_MS, withTimeout } from '../api/timeout';
import type { PreparedImage } from './imagePrep';
import { type PutFile, sizeMismatch, storageTimeout, storageUnreachable, uploadFailure } from './storageError';

export async function readFile(uri: string): Promise<{ size: number; body: Blob }> {
  const response = await fetch(uri);
  const body = await response.blob();
  return { size: body.size, body };
}

// 발급받은 주소로 파일을 올린다. 우리 서버가 아니라 저장소(S3, 로컬은 MinIO)로 직접 간다.
// 그래서 공통 클라이언트(api/client.ts)를 쓰지 않는다. 로그인 토큰을 붙이면 안 되고, 서명이 인증을 대신한다
export const putFile: PutFile = async (uploadUrl: string, image: PreparedImage) => {
  // 준비 단계에서 읽어 둔 내용을 그대로 올린다
  const body = image.body ?? (await readFile(image.uri)).body;
  if (body.size !== image.size) {
    throw sizeMismatch(image.size, body.size);
  }

  // 파일을 보내는 시간이 들어가므로 API 요청(15초)보다 긴 제한 시간을 쓴다. 응답 본문을 읽는 시간까지 잰다
  let result: { ok: boolean; status: number; text: string };
  try {
    result = await withTimeout(UPLOAD_TIMEOUT_MS, async (signal) => {
      const response = await fetch(uploadUrl, {
        method: 'PUT',
        // 발급 때 보낸 형식과 같아야 한다. Content-Length 는 브라우저가 body 의 크기로 붙인다
        headers: { 'Content-Type': image.contentType },
        body,
        signal,
      });
      const text = response.ok ? '' : await response.text().catch(() => '');
      return { ok: response.ok, status: response.status, text };
    });
  } catch (error) {
    throw error instanceof TimeoutError ? storageTimeout() : storageUnreachable();
  }
  if (!result.ok) {
    throw uploadFailure(result.status, result.text);
  }
};
