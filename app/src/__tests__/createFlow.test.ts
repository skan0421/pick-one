// 고민 등록 흐름 테스트: 결과별 처리와, 다시 시도할 때 어떤 키가 나가는지 (docs/troubleshooting.md 24)
//
// 앞쪽은 서버 호출을 가짜로 바꿔 흐름만 본다.
// 맨 뒤 "실제 클라이언트" 묶음은 fetch 만 가짜로 바꾸고 나머지(createQuestion → request)는 실제 코드로 엮어,
// 서버로 나가는 헤더를 직접 본다. 15초는 가짜 시계로 돌린다
import { ApiError, NETWORK_ERROR } from '../api/errors';
import { createQuestion, type CreateQuestionRequest, type QuestionResponse } from '../api/questions';
import { setTokens } from '../auth/session';
import { ComposeKey } from '../compose/composeKey';
import { errorsFromServer } from '../compose/composeRules';
import {
  createWithKey,
  isUnknownOutcome,
  KEY_CONFLICT_MESSAGE,
  RETRY_HINT,
  type CreateApi,
} from '../compose/createFlow';
import type { PreparedImage } from '../compose/imagePrep';
import { submitImageQuestion, type UploadApi, type UploadSlot } from '../compose/uploadFlow';

jest.mock('../auth/tokenStorage', () => ({
  loadTokens: jest.fn(async () => null),
  saveTokens: jest.fn(async () => undefined),
  clearTokens: jest.fn(async () => undefined),
}));

const TEXT: CreateQuestionRequest = {
  questionType: 'TEXT',
  content: '카페 vs 밥집',
  options: [{ content: '카페' }, { content: '밥집' }],
};

const CREATED: QuestionResponse = {
  id: 42,
  questionType: 'TEXT',
  content: '카페 vs 밥집',
  status: 'ACTIVE',
  options: [
    { id: 1, sortOrder: 1, content: '카페' },
    { id: 2, sortOrder: 2, content: '밥집' },
  ],
  createdAt: '2026-09-30T12:00:00',
};

const NETWORK = new ApiError(0, NETWORK_ERROR, '서버에 연결할 수 없습니다.');
const TIMEOUT = new ApiError(0, NETWORK_ERROR, '서버가 응답하지 않습니다. 네트워크 연결을 확인해 주세요.');

function apiError(status: number, code: string, message = code): ApiError {
  return new ApiError(status, code, message);
}

function setup() {
  let made = 0;
  const key = new ComposeKey(() => `key-${(made += 1)}`);
  const create = jest.fn(async (_body: CreateQuestionRequest, _key: string) => CREATED);
  const api: CreateApi = { createQuestion: create };
  // 서버로 나간 키를 순서대로 꺼낸다
  const sentKeys = () => create.mock.calls.map(([, sent]) => sent);
  return { api, key, create, sentKeys };
}

// 실패해야 하는 요청에서 던져진 에러를 꺼낸다
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

describe('createWithKey', () => {
  it('키를 붙여 등록하고, 성공하면 응답을 돌려주고 키를 버린다', async () => {
    const { api, key, create } = setup();

    const question = await createWithKey(api, key, TEXT);

    expect(create).toHaveBeenCalledWith(TEXT, 'key-1');
    expect(question).toBe(CREATED);
    expect(key.hasPending()).toBe(false);
  });

  it('네트워크 오류 뒤에 다시 시도하면 같은 키가 나간다', async () => {
    const { api, key, create, sentKeys } = setup();
    create.mockRejectedValueOnce(NETWORK);

    await failure(createWithKey(api, key, TEXT));
    const question = await createWithKey(api, key, TEXT);

    expect(question).toBe(CREATED);
    expect(sentKeys()).toEqual(['key-1', 'key-1']);
  });

  it('시간 초과 뒤에 다시 시도하면 같은 키가 나간다', async () => {
    const { api, key, create, sentKeys } = setup();
    create.mockRejectedValueOnce(TIMEOUT);

    await failure(createWithKey(api, key, TEXT));
    await createWithKey(api, key, TEXT);

    expect(sentKeys()).toEqual(['key-1', 'key-1']);
  });

  it('서버 오류(5xx) 뒤에 다시 시도하면 같은 키가 나간다', async () => {
    const { api, key, create, sentKeys } = setup();
    create.mockRejectedValueOnce(apiError(500, 'INTERNAL_ERROR'));
    create.mockRejectedValueOnce(apiError(502, 'UNKNOWN_ERROR'));

    await failure(createWithKey(api, key, TEXT));
    await failure(createWithKey(api, key, TEXT));
    await createWithKey(api, key, TEXT);

    expect(sentKeys()).toEqual(['key-1', 'key-1', 'key-1']);
  });

  it('여러 번 실패해도 계속 같은 키이고, 성공해야 키를 버린다', async () => {
    const { api, key, create, sentKeys } = setup();
    create.mockRejectedValueOnce(NETWORK);
    create.mockRejectedValueOnce(TIMEOUT);
    create.mockRejectedValueOnce(new TypeError('예상 못 한 오류'));

    await failure(createWithKey(api, key, TEXT));
    await failure(createWithKey(api, key, TEXT));
    await failure(createWithKey(api, key, TEXT));
    expect(key.hasPending()).toBe(true);
    await createWithKey(api, key, TEXT);

    expect(sentKeys()).toEqual(['key-1', 'key-1', 'key-1', 'key-1']);
    expect(key.hasPending()).toBe(false);
  });

  it('성공한 뒤에 올리는 고민은 새 키로 나간다', async () => {
    const { api, key, sentKeys } = setup();

    await createWithKey(api, key, TEXT);
    await createWithKey(api, key, { ...TEXT, content: '다음 고민' });

    expect(sentKeys()).toEqual(['key-1', 'key-2']);
  });

  it('결과를 모르는 실패의 안내에는 한 번만 등록된다는 말이 들어간다', async () => {
    const { api, key, create } = setup();
    create.mockRejectedValueOnce(TIMEOUT);

    const error = await failure(createWithKey(api, key, TEXT));

    expect(error.code).toBe(NETWORK_ERROR);
    expect(error.status).toBe(0);
    expect(error.message).toBe(`서버가 응답하지 않습니다. 네트워크 연결을 확인해 주세요. ${RETRY_HINT}`);
    // 화면에서는 폼 전체의 오류로 보인다
    expect(errorsFromServer(error, 2).form).toBe(error.message);
  });

  it('ApiError 가 아닌 예외도 결과를 모르는 실패로 다룬다', async () => {
    const { api, key, create } = setup();
    create.mockRejectedValueOnce(new TypeError('예상 못 한 오류'));

    const error = await failure(createWithKey(api, key, TEXT));

    expect(error.message).toBe(`예상 못 한 오류 ${RETRY_HINT}`);
    expect(key.hasPending()).toBe(true);
  });

  it('서버가 입력을 거절하면 안내를 바꾸지 않고, 고친 내용을 같은 키로 보낸다', async () => {
    const { api, key, create, sentKeys } = setup();
    const rejected = new ApiError(400, 'VALIDATION_ERROR', '입력값이 올바르지 않습니다.', [
      { field: 'content', reason: '고민 내용은 300자 이하여야 합니다.' },
    ]);
    create.mockRejectedValueOnce(rejected);

    const error = await failure(createWithKey(api, key, TEXT));
    await createWithKey(api, key, { ...TEXT, content: '줄인 내용' });

    // 등록된 것이 없으므로 "한 번만 등록돼요" 안내를 붙이지 않는다
    expect(error).toBe(rejected);
    expect(errorsFromServer(error, 2).content).toBe('고민 내용은 300자 이하여야 합니다.');
    expect(sentKeys()).toEqual(['key-1', 'key-1']);
  });

  it.each([
    ['QUESTION_OPTION_COUNT_INVALID', 400],
    ['IMAGE_URL_INVALID', 400],
    ['SIGNUP_INCOMPLETE', 403],
    ['AUTH_INVALID_TOKEN', 401],
  ])('%s (%d) 는 분명한 거절이라 안내를 바꾸지 않고 키를 유지한다', async (code, status) => {
    const { api, key, create } = setup();
    const rejected = apiError(status, code);
    create.mockRejectedValueOnce(rejected);

    expect(await failure(createWithKey(api, key, TEXT))).toBe(rejected);
    expect(key.use()).toBe('key-1');
  });

  it('IDEMPOTENCY_KEY_CONFLICT 는 키를 버리고 내 고민을 확인하라고 안내한다. 다시 누르면 새 키가 나간다', async () => {
    const { api, key, create, sentKeys } = setup();
    create.mockRejectedValueOnce(TIMEOUT); // 서버는 등록했지만 응답이 오지 않았다
    create.mockRejectedValueOnce(apiError(409, 'IDEMPOTENCY_KEY_CONFLICT', '같은 키로 다른 요청이 처리되었습니다.'));
    const edited = { ...TEXT, content: '고친 내용' };

    await failure(createWithKey(api, key, TEXT));
    const conflict = await failure(createWithKey(api, key, edited));
    expect(key.hasPending()).toBe(false);
    await createWithKey(api, key, edited);

    expect(conflict.code).toBe('IDEMPOTENCY_KEY_CONFLICT');
    expect(conflict.message).toBe(KEY_CONFLICT_MESSAGE);
    expect(errorsFromServer(conflict, 2).form).toBe(KEY_CONFLICT_MESSAGE);
    expect(sentKeys()).toEqual(['key-1', 'key-1', 'key-2']);
  });
});

describe('isUnknownOutcome', () => {
  it('서버에 닿지 못했거나 응답이 없었거나 서버 오류면 결과를 모른다', () => {
    expect(isUnknownOutcome(NETWORK)).toBe(true);
    expect(isUnknownOutcome(TIMEOUT)).toBe(true);
    expect(isUnknownOutcome(apiError(500, 'INTERNAL_ERROR'))).toBe(true);
    expect(isUnknownOutcome(apiError(503, 'UNKNOWN_ERROR'))).toBe(true);
    expect(isUnknownOutcome(new Error('?'))).toBe(true);
    expect(isUnknownOutcome('문자열 예외')).toBe(true);
  });

  it('서버가 4xx 로 답했으면 등록되지 않은 것이 분명하다', () => {
    expect(isUnknownOutcome(apiError(400, 'VALIDATION_ERROR'))).toBe(false);
    expect(isUnknownOutcome(apiError(403, 'SIGNUP_INCOMPLETE'))).toBe(false);
    expect(isUnknownOutcome(apiError(409, 'IDEMPOTENCY_KEY_CONFLICT'))).toBe(false);
    expect(isUnknownOutcome(apiError(429, 'RATE_LIMITED'))).toBe(false);
  });
});

describe('사진형: 업로드가 끝난 뒤 등록 요청에만 키가 붙는다', () => {
  function image(name: string, size: number): PreparedImage {
    return { uri: `file:///${name}`, contentType: 'image/jpeg', size, body: { size, name } as unknown as Blob };
  }

  function slots(): UploadSlot[] {
    return [{ image: image('a', 1000) }, { image: image('b', 2000) }];
  }

  function setupUpload() {
    const base = setup();
    const calls: string[] = [];
    let issued = 0;
    const issueUploadUrl = jest.fn(async () => {
      issued += 1;
      calls.push(`issue:${issued}`);
      return {
        uploadUrl: `http://storage/put/${issued}?sig=x`,
        imageUrl: `http://storage/images/7/${issued}.jpg`,
        expiresAt: '2026-09-30T12:05:00',
      };
    });
    const putFile = jest.fn(async (uploadUrl: string) => {
      calls.push(`put:${uploadUrl}`);
    });
    base.create.mockImplementation(async (_body, sent) => {
      calls.push(`create:${sent}`);
      return CREATED;
    });
    // 화면(ComposeForm)과 같은 방식으로 엮는다
    const uploadApi: UploadApi = {
      issueUploadUrl,
      putFile,
      createQuestion: (body) => createWithKey(base.api, base.key, body),
    };
    return { ...base, uploadApi, calls, issueUploadUrl, putFile };
  }

  it('발급과 PUT 이 모두 끝난 뒤에 키를 만들어 등록 요청에 붙인다', async () => {
    const { uploadApi, key, calls, issueUploadUrl, putFile } = setupUpload();
    putFile.mockImplementation(async (uploadUrl: string) => {
      // 사진을 올리는 동안에는 아직 키가 없다
      expect(key.hasPending()).toBe(false);
      calls.push(`put:${uploadUrl}`);
    });

    const outcome = await submitImageQuestion(uploadApi, '뭐 입지', slots());

    expect(outcome.kind).toBe('created');
    expect(calls).toEqual([
      'issue:1',
      'put:http://storage/put/1?sig=x',
      'issue:2',
      'put:http://storage/put/2?sig=x',
      'create:key-1',
    ]);
    expect(issueUploadUrl).toHaveBeenCalledTimes(2);
  });

  it('사진을 올리지 못하면 등록 요청이 나가지 않고 키도 만들지 않는다', async () => {
    const { uploadApi, key, create, putFile } = setupUpload();
    putFile.mockRejectedValueOnce(NETWORK);

    const outcome = await submitImageQuestion(uploadApi, '뭐 입지', slots());

    expect(outcome.kind).toBe('uploadFailed');
    expect(create).not.toHaveBeenCalled();
    expect(key.hasPending()).toBe(false);
  });

  it('등록 요청이 시간 초과되면, 다시 시도에서 사진은 다시 올리지 않고 같은 키로 등록만 다시 보낸다', async () => {
    const { uploadApi, create, sentKeys, issueUploadUrl, putFile } = setupUpload();
    create.mockRejectedValueOnce(TIMEOUT);

    const first = await submitImageQuestion(uploadApi, '뭐 입지', slots());
    if (first.kind !== 'rejected') {
      throw new Error(`rejected 여야 합니다: ${first.kind}`);
    }
    expect((first.error as ApiError).message).toContain(RETRY_HINT);
    // 화면은 올라간 사진의 주소를 기억해 두었다가 다시 넘긴다
    const retrySlots = slots().map((slot, index) => ({ ...slot, uploadedUrl: first.urls[index] }));
    const second = await submitImageQuestion(uploadApi, '뭐 입지', retrySlots);

    expect(second.kind).toBe('created');
    expect(issueUploadUrl).toHaveBeenCalledTimes(2);
    expect(putFile).toHaveBeenCalledTimes(2);
    expect(sentKeys()).toEqual(['key-1', 'key-1']);
    // 두 번 모두 같은 내용이 나간다 (같은 키 + 같은 내용이어야 서버가 처음 결과를 돌려준다)
    expect(create.mock.calls[1][0]).toEqual(create.mock.calls[0][0]);
  });

  it('사진 올리기에 실패한 뒤 다시 시도해 등록하면 그때 처음 키를 만든다', async () => {
    const { uploadApi, putFile, sentKeys } = setupUpload();
    putFile.mockRejectedValueOnce(NETWORK);

    const first = await submitImageQuestion(uploadApi, '뭐 입지', slots());
    if (first.kind !== 'uploadFailed') {
      throw new Error(`uploadFailed 여야 합니다: ${first.kind}`);
    }
    const retrySlots = slots().map((slot, index) => ({ ...slot, uploadedUrl: first.urls[index] }));
    const second = await submitImageQuestion(uploadApi, '뭐 입지', retrySlots);

    expect(second.kind).toBe('created');
    expect(sentKeys()).toEqual(['key-1']);
  });
});

describe('실제 클라이언트: 서버로 나가는 Idempotency-Key 헤더', () => {
  type Sent = { url: string; method: string; key: string | undefined; body: unknown; signal: AbortSignal | undefined };
  type FakeResponse = { ok: boolean; status: number; json: () => Promise<unknown>; text: () => Promise<string> };

  const KEY_1 = '11111111-1111-4111-8111-111111111111';
  const KEY_2 = '22222222-2222-4222-8222-222222222222';
  const api: CreateApi = { createQuestion };

  const sent: Sent[] = [];
  // 서버가 등록한 고민의 수와, 키별로 기억해 둔 처음 결과 (서버의 Idempotency-Key 처리를 흉내 낸다)
  let registered = 0;
  let processed: Map<string, unknown>;
  // 앞에서부터 몇 번의 요청에 응답하지 않을지
  let silentCount = 0;
  // 응답하지 않는 요청도 서버에는 닿아 처리되는가 (응답만 유실된 경우)
  let processSilently = false;
  let key: ComposeKey;

  function respond(status: number, body: unknown): FakeResponse {
    const text = JSON.stringify(body);
    return { ok: status >= 200 && status < 300, status, json: async () => JSON.parse(text), text: async () => text };
  }

  // 같은 키가 다시 오면 새로 등록하지 않고 처음 결과를 돌려준다
  function register(idempotencyKey: string): unknown {
    const before = processed.get(idempotencyKey);
    if (before !== undefined) {
      return before;
    }
    registered += 1;
    const result = { ...CREATED, id: 100 + registered };
    processed.set(idempotencyKey, result);
    return result;
  }

  beforeEach(async () => {
    jest.useFakeTimers();
    process.env.EXPO_PUBLIC_API_URL = 'http://test-server:8080';
    sent.length = 0;
    registered = 0;
    processed = new Map();
    silentCount = 0;
    processSilently = false;
    const made = [KEY_1, KEY_2];
    key = new ComposeKey(() => made.shift() ?? 'no-more-keys');
    await setTokens({ accessToken: 'access', refreshToken: 'refresh' });

    globalThis.fetch = jest.fn(async (url: string, init: RequestInit) => {
      const headers = (init.headers ?? {}) as Record<string, string>;
      const call: Sent = {
        url,
        method: init.method ?? 'GET',
        key: headers['Idempotency-Key'],
        body: typeof init.body === 'string' ? JSON.parse(init.body) : undefined,
        signal: init.signal ?? undefined,
      };
      sent.push(call);
      if (sent.length <= silentCount) {
        if (processSilently && call.key) {
          register(call.key);
        }
        return new Promise<FakeResponse>(() => undefined);
      }
      return respond(201, register(call.key ?? ''));
    }) as unknown as typeof fetch;
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  // 올리기를 누르고 15초가 지날 때까지 기다린다
  async function pressAndWaitForTimeout(): Promise<ApiError> {
    const pending = failure(createWithKey(api, key, TEXT));
    await jest.advanceTimersByTimeAsync(15_000);
    return pending;
  }

  it('POST /questions 에 Idempotency-Key 헤더와 본문을 싣는다', async () => {
    await createWithKey(api, key, TEXT);

    expect(sent).toHaveLength(1);
    expect(sent[0].url).toBe('http://test-server:8080/api/v1/questions');
    expect(sent[0].method).toBe('POST');
    expect(sent[0].key).toBe(KEY_1);
    expect(sent[0].body).toEqual(TEXT);
  });

  it('15초 동안 응답이 없으면 다시 시도 안내로 끝나고 키를 기억한다', async () => {
    silentCount = 1;

    const error = await pressAndWaitForTimeout();

    expect(error.code).toBe(NETWORK_ERROR);
    expect(error.message).toBe(`서버가 응답하지 않습니다. 네트워크 연결을 확인해 주세요. ${RETRY_HINT}`);
    expect(sent[0].signal?.aborted).toBe(true);
    expect(key.hasPending()).toBe(true);
  });

  it('시간 초과 뒤 다시 시도하면 같은 Idempotency-Key 가 나간다', async () => {
    silentCount = 1;

    await pressAndWaitForTimeout();
    await createWithKey(api, key, TEXT);

    expect(sent.map((call) => call.key)).toEqual([KEY_1, KEY_1]);
    expect(sent.map((call) => call.body)).toEqual([TEXT, TEXT]);
  });

  it('서버가 등록했는데 응답만 오지 않았어도, 다시 시도에서 고민은 한 번만 등록된다', async () => {
    silentCount = 1;
    processSilently = true;

    await pressAndWaitForTimeout();
    expect(registered).toBe(1); // 앱은 실패로 알지만 서버는 이미 등록했다

    const question = await createWithKey(api, key, TEXT);

    expect(question.id).toBe(101);
    expect(registered).toBe(1);
    expect(sent.map((call) => call.key)).toEqual([KEY_1, KEY_1]);
  });

  it('연달아 시간 초과되어도 계속 같은 키가 나가고, 성공한 뒤에는 새 키가 나간다', async () => {
    silentCount = 2;

    await pressAndWaitForTimeout();
    await pressAndWaitForTimeout();
    await createWithKey(api, key, TEXT);
    await createWithKey(api, key, { ...TEXT, content: '다음 고민' });

    expect(sent.map((call) => call.key)).toEqual([KEY_1, KEY_1, KEY_1, KEY_2]);
    expect(registered).toBe(2);
  });
});
