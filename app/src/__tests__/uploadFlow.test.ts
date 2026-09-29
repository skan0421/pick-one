// 사진 올리기 흐름 테스트: 발급 → PUT → 등록, 그리고 중간에 실패했을 때
import { ApiError, NETWORK_ERROR } from '../api/errors';
import type { QuestionResponse } from '../api/questions';
import type { PreparedImage } from '../compose/imagePrep';
import { submitImageQuestion, type SlotStatus, type UploadApi, type UploadSlot } from '../compose/uploadFlow';

function image(name: string, size: number, contentType = 'image/jpeg'): PreparedImage {
  return { uri: `file:///${name}`, contentType, size, body: { size, name } as unknown as Blob };
}

const CREATED: QuestionResponse = {
  id: 42,
  questionType: 'IMAGE',
  content: '뭐 입지',
  status: 'ACTIVE',
  options: [
    { id: 1, sortOrder: 1, imageUrl: 'http://storage/images/7/1.jpg' },
    { id: 2, sortOrder: 2, imageUrl: 'http://storage/images/7/2.jpg' },
  ],
  createdAt: '2026-09-30T12:00:00',
};

// 호출 순서를 기록하는 가짜 서버
function fakeApi() {
  const calls: string[] = [];
  let issued = 0;
  const issueUploadUrl = jest.fn(async (body: { contentType: string; size: number }) => {
    issued += 1;
    calls.push(`issue:${body.contentType}:${body.size}`);
    return {
      uploadUrl: `http://storage/put/${issued}?sig=x`,
      imageUrl: `http://storage/images/7/${issued}.jpg`,
      expiresAt: '2026-09-30T12:05:00',
    };
  });
  const putFile = jest.fn(async (uploadUrl: string, contentType: string, body: Blob) => {
    calls.push(`put:${uploadUrl}:${contentType}:${body.size}`);
  });
  const createQuestion = jest.fn(async () => {
    calls.push('create');
    return CREATED;
  });
  const api: UploadApi = { issueUploadUrl, putFile, createQuestion };
  return { api, calls, issueUploadUrl, putFile, createQuestion };
}

function slots(): UploadSlot[] {
  return [{ image: image('a', 1000) }, { image: image('b', 2000, 'image/png') }];
}

describe('submitImageQuestion', () => {
  it('사진마다 발급 → PUT 을 하고, 두 장이 끝난 뒤 등록한다', async () => {
    const { api, calls, createQuestion } = fakeApi();

    const outcome = await submitImageQuestion(api, '  뭐 입지 ', slots());

    expect(calls).toEqual([
      'issue:image/jpeg:1000',
      'put:http://storage/put/1?sig=x:image/jpeg:1000',
      'issue:image/png:2000',
      'put:http://storage/put/2?sig=x:image/png:2000',
      'create',
    ]);
    expect(createQuestion).toHaveBeenCalledWith({
      questionType: 'IMAGE',
      content: '뭐 입지',
      options: [{ imageUrl: 'http://storage/images/7/1.jpg' }, { imageUrl: 'http://storage/images/7/2.jpg' }],
    });
    expect(outcome).toEqual({ kind: 'created', question: CREATED });
  });

  it('발급 때 보낸 형식·크기와 PUT 의 형식·크기가 같다', async () => {
    const { api, issueUploadUrl, putFile } = fakeApi();
    const input = slots();

    await submitImageQuestion(api, '뭐 입지', input);

    input.forEach((slot, i) => {
      const issued = issueUploadUrl.mock.calls[i][0];
      const [, putType, putBody] = putFile.mock.calls[i];
      expect(issued).toEqual({ contentType: slot.image.contentType, size: slot.image.size });
      expect(putType).toBe(issued.contentType);
      expect(putBody.size).toBe(issued.size);
      expect(putBody).toBe(slot.image.body);
    });
  });

  it('칸마다 진행 상태를 알린다', async () => {
    const { api } = fakeApi();
    const statuses: [number, SlotStatus][] = [];

    await submitImageQuestion(api, '뭐 입지', slots(), (index, status) => statuses.push([index, status]));

    expect(statuses).toEqual([
      [0, 'uploading'],
      [0, 'done'],
      [1, 'uploading'],
      [1, 'done'],
    ]);
  });

  it('두 번째 사진의 PUT 이 실패하면 등록하지 않고, 올라간 첫 사진의 주소는 돌려준다', async () => {
    const { api, putFile, createQuestion } = fakeApi();
    putFile.mockImplementationOnce(async () => undefined);
    putFile.mockImplementationOnce(async () => {
      throw new ApiError(403, 'UPLOAD_FAILED', '사진을 올리지 못했습니다. (HTTP 403)');
    });
    const statuses: [number, SlotStatus][] = [];

    const outcome = await submitImageQuestion(api, '뭐 입지', slots(), (index, status) => statuses.push([index, status]));

    expect(createQuestion).not.toHaveBeenCalled();
    expect(outcome).toEqual({
      kind: 'uploadFailed',
      urls: ['http://storage/images/7/1.jpg', undefined],
      failures: [{ index: 1, message: '사진을 올리지 못했습니다. (HTTP 403)' }],
    });
    expect(statuses).toContainEqual([1, 'failed']);
  });

  it('첫 사진이 실패해도 두 번째는 올려 두고, 등록은 하지 않는다', async () => {
    const { api, putFile, createQuestion } = fakeApi();
    putFile.mockImplementationOnce(async () => {
      throw new ApiError(0, NETWORK_ERROR, '사진 저장소에 연결할 수 없습니다.');
    });

    const outcome = await submitImageQuestion(api, '뭐 입지', slots());

    expect(putFile).toHaveBeenCalledTimes(2);
    expect(createQuestion).not.toHaveBeenCalled();
    expect(outcome).toMatchObject({ kind: 'uploadFailed', urls: [undefined, 'http://storage/images/7/2.jpg'] });
  });

  it('발급이 거절되면(IMAGE_TOO_LARGE 등) PUT 도 등록도 하지 않는다', async () => {
    const { api, issueUploadUrl, putFile, createQuestion } = fakeApi();
    issueUploadUrl.mockRejectedValue(new ApiError(400, 'IMAGE_TOO_LARGE', '이미지는 5MB 이하여야 합니다.'));

    const outcome = await submitImageQuestion(api, '뭐 입지', slots());

    expect(putFile).not.toHaveBeenCalled();
    expect(createQuestion).not.toHaveBeenCalled();
    expect(outcome).toMatchObject({
      kind: 'uploadFailed',
      failures: [
        { index: 0, message: '이미지는 5MB 이하여야 합니다.' },
        { index: 1, message: '이미지는 5MB 이하여야 합니다.' },
      ],
    });
  });

  it('다시 시도할 때 이미 올라간 사진은 건너뛰고 실패한 사진만 올린다', async () => {
    const { api, calls } = fakeApi();
    const retry: UploadSlot[] = [
      { image: image('a', 1000), uploadedUrl: 'http://storage/images/7/old.jpg' },
      { image: image('b', 2000) },
    ];

    const outcome = await submitImageQuestion(api, '뭐 입지', retry);

    expect(calls).toEqual(['issue:image/jpeg:2000', 'put:http://storage/put/1?sig=x:image/jpeg:2000', 'create']);
    expect(api.createQuestion).toHaveBeenCalledWith(
      expect.objectContaining({
        options: [{ imageUrl: 'http://storage/images/7/old.jpg' }, { imageUrl: 'http://storage/images/7/1.jpg' }],
      }),
    );
    expect(outcome.kind).toBe('created');
  });

  it('등록이 거절되면 올라간 사진의 주소를 유지한다 (본문만 고쳐 다시 제출할 수 있게)', async () => {
    const { api, createQuestion } = fakeApi();
    const rejected = new ApiError(400, 'VALIDATION_ERROR', '입력값이 올바르지 않습니다.', [
      { field: 'content', reason: '고민 내용은 300자 이하여야 합니다.' },
    ]);
    createQuestion.mockRejectedValue(rejected);

    const outcome = await submitImageQuestion(api, '뭐 입지', slots());

    expect(outcome).toEqual({
      kind: 'rejected',
      urls: ['http://storage/images/7/1.jpg', 'http://storage/images/7/2.jpg'],
      error: rejected,
    });
  });

  it('IMAGE_URL_INVALID 로 거절되면 기억하던 주소를 버려 다음에는 처음부터 다시 올린다', async () => {
    const { api, createQuestion } = fakeApi();
    createQuestion.mockRejectedValue(new ApiError(400, 'IMAGE_URL_INVALID', '이미지 주소가 올바르지 않습니다.'));

    const outcome = await submitImageQuestion(api, '뭐 입지', slots());

    expect(outcome).toMatchObject({ kind: 'rejected', urls: [undefined, undefined] });
  });

  it('ApiError 가 아닌 예외도 던지지 않는다', async () => {
    const { api, putFile } = fakeApi();
    putFile.mockRejectedValue(new TypeError('예상 못 한 오류'));

    const outcome = await submitImageQuestion(api, '뭐 입지', slots());

    expect(outcome).toMatchObject({
      kind: 'uploadFailed',
      failures: [
        { index: 0, message: '예상 못 한 오류' },
        { index: 1, message: '예상 못 한 오류' },
      ],
    });
  });
});
