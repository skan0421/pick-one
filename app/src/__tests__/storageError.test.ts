// 저장소가 업로드를 거절했을 때의 응답(XML) 읽기 테스트
import { ApiError } from '../api/errors';
import { parseStorageErrorCode, sizeMismatch, UPLOAD_FAILED, uploadFailure } from '../compose/storageError';

// MinIO 가 실제로 돌려주는 모양
function errorXml(code: string, message: string): string {
  return (
    '<?xml version="1.0" encoding="UTF-8"?>\n' +
    `<Error><Code>${code}</Code><Message>${message}</Message><Key>images/7/a.jpg</Key>` +
    '<BucketName>pickone</BucketName><Resource>/pickone/images/7/a.jpg</Resource>' +
    '<RequestId>17F0A1B2C3D4E5F6</RequestId><HostId>dd9025bab4ad</HostId></Error>'
  );
}

let warn: jest.SpyInstance;

beforeEach(() => {
  // 로그 내용을 검사하고, 테스트 출력에는 찍히지 않게 한다 (Mockito 의 spy)
  warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  warn.mockRestore();
});

describe('parseStorageErrorCode', () => {
  it.each(['SignatureDoesNotMatch', 'AccessDenied', 'RequestTimeTooSkewed', 'MissingContentLength'])(
    '403 응답 본문에서 Code %s 를 꺼낸다',
    (code) => {
      expect(parseStorageErrorCode(errorXml(code, '설명'))).toBe(code);
    },
  );

  it('Code 앞뒤의 공백과 줄바꿈은 뺀다', () => {
    expect(parseStorageErrorCode('<Error>\n  <Code>\n    AccessDenied\n  </Code>\n</Error>')).toBe('AccessDenied');
  });

  it.each([
    ['빈 본문', ''],
    ['없음', undefined],
    ['null', null],
    ['XML 이 아닌 본문', 'Forbidden'],
    ['HTML 오류 페이지', '<html><body><h1>403 Forbidden</h1></body></html>'],
    ['Code 가 빈 XML', '<Error><Code></Code></Error>'],
    ['Code 가 없는 XML', '<Error><Message>거절</Message></Error>'],
  ])('%s 이면 null', (_name, body) => {
    expect(parseStorageErrorCode(body)).toBeNull();
  });
});

describe('uploadFailure', () => {
  it('문구에 HTTP 상태와 Code 를 넣는다', () => {
    const error = uploadFailure(403, errorXml('SignatureDoesNotMatch', 'The request signature we calculated does not match'));

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(403);
    expect(error.code).toBe(UPLOAD_FAILED);
    expect(error.message).toBe('사진을 올리지 못했습니다. (HTTP 403, SignatureDoesNotMatch)');
  });

  it('Code 를 알 수 없으면 HTTP 상태만 넣는다', () => {
    expect(uploadFailure(502, '<html>Bad Gateway</html>').message).toBe('사진을 올리지 못했습니다. (HTTP 502)');
    expect(uploadFailure(403, '').message).toBe('사진을 올리지 못했습니다. (HTTP 403)');
  });

  it('로그에 상태·Code·Message 를 남긴다', () => {
    uploadFailure(403, errorXml('SignatureDoesNotMatch', 'The request signature we calculated does not match'));

    expect(warn).toHaveBeenCalledTimes(1);
    const logged = String(warn.mock.calls[0][0]);
    expect(logged).toContain('HTTP 403');
    expect(logged).toContain('Code=SignatureDoesNotMatch');
    expect(logged).toContain('Message=The request signature we calculated does not match');
  });

  it('본문을 통째로 로그에 남기지 않는다 (Code 와 Message 만)', () => {
    uploadFailure(403, errorXml('AccessDenied', 'Request has expired'));

    const logged = String(warn.mock.calls[0][0]);
    expect(logged).not.toContain('images/7/a.jpg');
    expect(logged).not.toContain('RequestId');
  });
});

describe('sizeMismatch', () => {
  it('발급 때 크기와 실제 크기를 로그에 남기고, 다시 고르라고 안내한다', () => {
    const error = sizeMismatch(1000, 998);

    expect(error.code).toBe(UPLOAD_FAILED);
    expect(error.message).toBe('사진 크기가 달라졌습니다. 사진을 다시 골라 주세요.');
    expect(String(warn.mock.calls[0][0])).toContain('발급=1000, 실제=998');
  });
});
