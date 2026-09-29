// 파일 크기 읽기와 저장소로 올리기 (휴대폰 앱). 웹은 fileTransfer.web.ts.
//
// 번들러(Metro)가 './fileTransfer' 를 플랫폼에 맞는 파일로 바꿔 준다 (auth/tokenStorage.ts 와 같은 방식).
// 두 파일은 같은 함수를 같은 시그니처로 내보내야 한다.
//
// 앱에서는 파일을 메모리로 읽지 않고(Blob 을 만들지 않고) 기기의 파일을 그대로 보낸다.
// 예전에는 fetch 에 Blob 을 넣어 올렸는데, 안드로이드에서 저장소가 403 으로 거절했다 (docs/troubleshooting.md 23).
// File.upload 는 파일 길이를 Content-Length 로 보낸다 (Java 의 RequestBody.create(file) 과 같다)
//
// 주의: 공용 코드는 storageError.ts 에 둔다. 여기에 두면 웹 파일이 가져올 수 없다 (docs/troubleshooting.md 18)
import { File } from 'expo-file-system';

import { TimeoutError, UPLOAD_TIMEOUT_MS, withTimeout } from '../api/timeout';
import type { PreparedImage } from './imagePrep';
import { type PutFile, sizeMismatch, storageTimeout, storageUnreachable, uploadFailure } from './storageError';

// 파일의 크기를 읽는다. 내용은 읽지 않는다
export async function readFile(uri: string): Promise<{ size: number }> {
  const file = new File(uri);
  if (!file.exists) {
    throw new Error(`파일이 없습니다: ${uri}`);
  }
  return { size: file.size };
}

// 발급받은 주소로 파일을 올린다. 우리 서버가 아니라 저장소(S3, 로컬은 MinIO)로 직접 간다.
// 로그인 토큰을 붙이면 안 되고, 서명이 인증을 대신한다
export const putFile: PutFile = async (uploadUrl: string, image: PreparedImage) => {
  const file = new File(image.uri);
  // 발급 때 보낸 크기는 서명에 들어 있다. 그 사이 파일이 바뀌었다면 올려도 거절된다
  const actual = file.exists ? file.size : 0;
  if (actual !== image.size) {
    throw sizeMismatch(image.size, actual);
  }

  let result: { status: number; body: string };
  try {
    // 파일을 보내는 시간이 들어가므로 API 요청(15초)보다 긴 제한 시간을 쓴다. 시간이 지나면 signal 로 전송을 취소한다
    result = await withTimeout(UPLOAD_TIMEOUT_MS, (signal) =>
      file.upload(uploadUrl, {
        // 기본값은 POST 다. 본문은 기본값(BINARY_CONTENT)대로 파일 내용 그 자체다
        httpMethod: 'PUT',
        // 발급 때 보낸 형식과 같아야 한다
        headers: { 'Content-Type': image.contentType },
        signal,
      }),
    );
  } catch (error) {
    throw error instanceof TimeoutError ? storageTimeout() : storageUnreachable();
  }
  // upload 는 403 같은 응답에도 예외를 던지지 않는다. 상태 코드를 직접 확인한다
  if (result.status < 200 || result.status >= 300) {
    throw uploadFailure(result.status, result.body);
  }
};
