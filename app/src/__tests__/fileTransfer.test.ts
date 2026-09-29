// 플랫폼별 업로드 테스트: 앱은 기기의 파일을 그대로 올리고(File.upload), 웹은 fetch 로 Blob 을 올린다.
//
// '../compose/fileTransfer' 는 번들러가 플랫폼에 맞는 파일로 바꿔 준다. 테스트(jest-expo)의 플랫폼은 앱이라
// 접미사 없이 가져오면 앱 파일이 온다. 웹 파일은 '.web' 을 붙여 직접 가져온다.
// 웹 파일을 여기서 가져오기 때문에 타입 검사(tsc)도 웹 파일을 검사하게 된다
//
// 린트는 접미사 없는 경로도 웹 파일로 풀이해 "같은 파일을 두 번 가져온다"고 경고한다. 실제로는 다른 파일이라 이 규칙만 끈다
/* eslint-disable import/no-duplicates */
import { ApiError, NETWORK_ERROR } from '../api/errors';
import * as nativeTransfer from '../compose/fileTransfer';
import * as webTransfer from '../compose/fileTransfer.web';
import type { PreparedImage } from '../compose/imagePrep';
import { UPLOAD_FAILED } from '../compose/storageError';

type FakeFile = { exists: boolean; size: number };
type UploadCall = {
  uri: string;
  url: string;
  options: { httpMethod?: string; headers?: Record<string, string>; signal?: AbortSignal };
};

// 가짜 기기 파일. jest.mock 안에서 쓰는 변수는 이름이 mock 으로 시작해야 한다
const mockFiles: Record<string, FakeFile> = {};
const mockUploads: UploadCall[] = [];
let mockUpload: (call: UploadCall) => Promise<{ status: number; body: string; headers: Record<string, string> }>;

jest.mock('expo-file-system', () => ({
  File: class {
    uri: string;

    constructor(uri: string) {
      this.uri = uri;
    }

    get exists() {
      return mockFiles[this.uri]?.exists ?? false;
    }

    get size() {
      return mockFiles[this.uri]?.size ?? 0;
    }

    upload(url: string, options: UploadCall['options'] = {}) {
      const call = { uri: this.uri, url, options };
      mockUploads.push(call);
      return mockUpload(call);
    }
  },
}));

const UPLOAD_URL = 'http://storage:9000/pickone/images/7/a.jpg?X-Amz-Signature=abc';
const SIGNATURE_XML =
  '<?xml version="1.0" encoding="UTF-8"?><Error><Code>SignatureDoesNotMatch</Code>' +
  '<Message>The request signature we calculated does not match the signature you provided.</Message></Error>';

let fetchMock: jest.Mock;
let warn: jest.SpyInstance;

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) {
      return error;
    }
    throw error;
  }
  throw new Error('실패해야 하는데 성공했습니다.');
}

function blob(size: number): Blob {
  return { size } as Blob;
}

function fakeResponse(status: number, text = '') {
  return { ok: status >= 200 && status < 300, status, text: async () => text };
}

beforeEach(() => {
  Object.keys(mockFiles).forEach((uri) => delete mockFiles[uri]);
  mockUploads.length = 0;
  mockUpload = async () => ({ status: 200, body: '', headers: {} });
  fetchMock = jest.fn(async () => fakeResponse(200));
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  warn.mockRestore();
  // 제한 시간 테스트가 켠 가짜 시계를 되돌린다
  jest.useRealTimers();
});

describe('앱 (fileTransfer.ts)', () => {
  const image: PreparedImage = { uri: 'file:///cache/a.jpg', contentType: 'image/jpeg', size: 345_678 };

  beforeEach(() => {
    mockFiles[image.uri] = { exists: true, size: 345_678 };
  });

  it('접미사 없이 가져오면 앱 파일이 온다 (웹 파일과 다른 구현이다)', () => {
    expect(nativeTransfer.putFile).not.toBe(webTransfer.putFile);
    expect(nativeTransfer.readFile).not.toBe(webTransfer.readFile);
  });

  it('크기는 파일에서 읽고, 내용을 Blob 으로 읽지 않는다', async () => {
    const read = await nativeTransfer.readFile(image.uri);

    expect(read).toEqual({ size: 345_678 });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('없는 파일은 읽기에 실패한다', async () => {
    await expect(nativeTransfer.readFile('file:///cache/missing.jpg')).rejects.toThrow('파일이 없습니다');
  });

  it('기기의 파일을 PUT 으로 올린다. fetch 는 쓰지 않는다', async () => {
    await nativeTransfer.putFile(UPLOAD_URL, image);

    expect(mockUploads).toEqual([
      {
        uri: image.uri,
        url: UPLOAD_URL,
        // signal 은 제한 시간이 지났을 때 전송을 취소하는 신호다
        options: { httpMethod: 'PUT', headers: { 'Content-Type': 'image/jpeg' }, signal: expect.any(AbortSignal) },
      },
    ]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('60초 안에 끝나지 않으면 전송을 취소하고 NETWORK_ERROR 로 실패한다', async () => {
    jest.useFakeTimers();
    mockUpload = () => new Promise(() => undefined);

    const pending = failure(nativeTransfer.putFile(UPLOAD_URL, image));
    await jest.advanceTimersByTimeAsync(60_000);
    const error = await pending;

    expect(error.status).toBe(0);
    expect(error.code).toBe(NETWORK_ERROR);
    expect(error.message).toBe('사진 저장소가 응답하지 않습니다. 네트워크 연결을 확인해 주세요.');
    expect(mockUploads[0].options.signal?.aborted).toBe(true);
  });

  it('API 요청의 제한 시간(15초)을 넘겨도 올리기는 계속한다', async () => {
    jest.useFakeTimers();
    // 45초 걸리는 업로드
    mockUpload = async () => {
      await new Promise((resolve) => setTimeout(resolve, 45_000));
      return { status: 200, body: '', headers: {} };
    };

    const pending = nativeTransfer.putFile(UPLOAD_URL, image);
    await jest.advanceTimersByTimeAsync(45_000);

    await expect(pending).resolves.toBeUndefined();
    expect(mockUploads[0].options.signal?.aborted).toBe(false);
    expect(jest.getTimerCount()).toBe(0);
  });

  it('로그인 토큰 등 다른 헤더를 붙이지 않는다 (서명이 인증을 대신한다)', async () => {
    await nativeTransfer.putFile(UPLOAD_URL, { ...image, contentType: 'image/png' });

    expect(mockUploads[0].options.headers).toEqual({ 'Content-Type': 'image/png' });
  });

  it('403 이면 응답 본문의 Code 를 문구에 넣는다', async () => {
    mockUpload = async () => ({ status: 403, body: SIGNATURE_XML, headers: {} });

    const error = await failure(nativeTransfer.putFile(UPLOAD_URL, image));

    expect(error.status).toBe(403);
    expect(error.code).toBe(UPLOAD_FAILED);
    expect(error.message).toBe('사진을 올리지 못했습니다. (HTTP 403, SignatureDoesNotMatch)');
  });

  it('로그에 업로드 주소(서명)를 남기지 않는다', async () => {
    mockUpload = async () => ({ status: 403, body: SIGNATURE_XML, headers: {} });

    await failure(nativeTransfer.putFile(UPLOAD_URL, image));

    const logged = warn.mock.calls.map((args) => args.join(' ')).join('\n');
    expect(logged).toContain('Code=SignatureDoesNotMatch');
    expect(logged).not.toContain('X-Amz-Signature');
    expect(logged).not.toContain('storage:9000');
  });

  it('파일 크기가 발급 때와 다르면 올리지 않는다', async () => {
    mockFiles[image.uri] = { exists: true, size: 345_000 };

    const error = await failure(nativeTransfer.putFile(UPLOAD_URL, image));

    expect(mockUploads).toEqual([]);
    expect(error.code).toBe(UPLOAD_FAILED);
    expect(error.message).toContain('사진 크기가 달라졌습니다');
  });

  it('파일이 사라졌으면 올리지 않는다', async () => {
    delete mockFiles[image.uri];

    const error = await failure(nativeTransfer.putFile(UPLOAD_URL, image));

    expect(mockUploads).toEqual([]);
    expect(error.code).toBe(UPLOAD_FAILED);
  });

  it('저장소에 닿지 못하면 NETWORK_ERROR', async () => {
    mockUpload = async () => {
      throw new Error('Failed to connect to /100.101.102.103:9000');
    };

    const error = await failure(nativeTransfer.putFile(UPLOAD_URL, image));

    expect(error.status).toBe(0);
    expect(error.code).toBe(NETWORK_ERROR);
  });
});

describe('웹 (fileTransfer.web.ts)', () => {
  const body = blob(345_678);
  const image: PreparedImage = { uri: 'blob:http://localhost:8081/1234', contentType: 'image/jpeg', size: 345_678, body };

  it('읽으면 크기와 내용(Blob)을 함께 돌려준다', async () => {
    fetchMock.mockResolvedValueOnce({ blob: async () => body });

    const read = await webTransfer.readFile(image.uri);

    expect(fetchMock).toHaveBeenCalledWith(image.uri);
    expect(read.size).toBe(345_678);
    expect(read.body).toBe(body);
  });

  it('준비 때 읽어 둔 Blob 을 fetch 로 PUT 한다. 기기 파일 기능은 쓰지 않는다', async () => {
    await webTransfer.putFile(UPLOAD_URL, image);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe(UPLOAD_URL);
    expect(init.method).toBe('PUT');
    expect(init.headers).toEqual({ 'Content-Type': 'image/jpeg' });
    expect(init.body).toBe(body);
    expect(mockUploads).toEqual([]);
  });

  it('403 이면 응답 본문의 Code 를 문구에 넣는다', async () => {
    fetchMock.mockResolvedValueOnce(fakeResponse(403, SIGNATURE_XML));

    const error = await failure(webTransfer.putFile(UPLOAD_URL, image));

    expect(error.status).toBe(403);
    expect(error.message).toBe('사진을 올리지 못했습니다. (HTTP 403, SignatureDoesNotMatch)');
  });

  it('응답 본문을 읽지 못해도 HTTP 상태는 알려 준다', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 403,
      text: async () => {
        throw new Error('본문 없음');
      },
    });

    const error = await failure(webTransfer.putFile(UPLOAD_URL, image));

    expect(error.message).toBe('사진을 올리지 못했습니다. (HTTP 403)');
  });

  it('내용의 크기가 발급 때와 다르면 올리지 않는다', async () => {
    const error = await failure(webTransfer.putFile(UPLOAD_URL, { ...image, size: 345_000 }));

    expect(fetchMock).not.toHaveBeenCalled();
    expect(error.message).toContain('사진 크기가 달라졌습니다');
  });

  it('60초 안에 끝나지 않으면 전송을 취소하고 NETWORK_ERROR 로 실패한다', async () => {
    jest.useFakeTimers();
    fetchMock.mockImplementationOnce(() => new Promise(() => undefined));

    const pending = failure(webTransfer.putFile(UPLOAD_URL, image));
    await jest.advanceTimersByTimeAsync(59_999);
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(false);
    await jest.advanceTimersByTimeAsync(1);
    const error = await pending;

    expect(error.code).toBe(NETWORK_ERROR);
    expect(error.message).toBe('사진 저장소가 응답하지 않습니다. 네트워크 연결을 확인해 주세요.');
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
  });

  it('저장소에 닿지 못하면 NETWORK_ERROR', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));

    const error = await failure(webTransfer.putFile(UPLOAD_URL, image));

    expect(error.code).toBe(NETWORK_ERROR);
  });
});
