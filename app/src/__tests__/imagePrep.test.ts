// 사진 준비 테스트: 변환할지 말지, 얼마나 줄일지. 기기 기능은 가짜로 넣는다
import {
  ImagePrepError,
  MAX_UPLOAD_BYTES,
  normalizeMimeType,
  planConversion,
  prepareImage,
  resizeTarget,
  type ImageTools,
  type PickedImage,
  type Resize,
} from '../compose/imagePrep';

const MB = 1024 * 1024;

// 크기만 의미가 있는 가짜 파일 내용
function content(size: number) {
  return { size, body: { size } as Blob };
}

// uri 별 파일 크기를 정해 두는 가짜 도구. toJpeg 는 "converted-1", "converted-2" ... 를 차례로 돌려준다
function fakeTools(sizes: Record<string, number>) {
  const conversions: { uri: string; resize: Resize | undefined; quality: number }[] = [];
  const tools: ImageTools = {
    read: jest.fn(async (uri: string) => {
      if (!(uri in sizes)) {
        throw new Error('읽을 수 없음');
      }
      return content(sizes[uri]);
    }),
    toJpeg: jest.fn(async (uri: string, resize: Resize | undefined, quality: number) => {
      conversions.push({ uri, resize, quality });
      return { uri: `converted-${conversions.length}` };
    }),
  };
  return { tools, conversions };
}

function picked(overrides: Partial<PickedImage> = {}): PickedImage {
  return { uri: 'original', width: 1200, height: 900, mimeType: 'image/jpeg', fileName: 'a.jpg', ...overrides };
}

async function failure(promise: Promise<unknown>): Promise<ImagePrepError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ImagePrepError) {
      return error;
    }
    throw error;
  }
  throw new Error('실패해야 하는데 성공했습니다.');
}

describe('normalizeMimeType', () => {
  it.each([
    ['image/jpeg', 'image/jpeg'],
    ['IMAGE/JPEG', 'image/jpeg'],
    ['image/jpg', 'image/jpeg'],
    ['image/heic', 'image/heic'],
  ])('%s → %s', (input, expected) => {
    expect(normalizeMimeType(input, 'x.bin')).toBe(expected);
  });

  it.each([
    ['IMG_0001.HEIC', 'image/heic'],
    ['photo.JPG', 'image/jpeg'],
    ['a.b.png', 'image/png'],
    ['c.webp', 'image/webp'],
  ])('형식을 모르면 파일 이름 %s 의 확장자로 짐작한다', (fileName, expected) => {
    expect(normalizeMimeType(undefined, fileName)).toBe(expected);
    expect(normalizeMimeType('', fileName)).toBe(expected);
    expect(normalizeMimeType(null, fileName)).toBe(expected);
  });

  it('형식도 확장자도 모르면 undefined', () => {
    expect(normalizeMimeType(undefined, undefined)).toBeUndefined();
    expect(normalizeMimeType(null, 'noextension')).toBeUndefined();
    expect(normalizeMimeType(undefined, 'file.xyz')).toBeUndefined();
  });
});

describe('planConversion (형식 변환 판단)', () => {
  const small = { width: 1200, height: 900, size: 300 * 1024 };

  it.each(['image/jpeg', 'image/png', 'image/webp'])('%s 이고 작으면 그대로 올린다', (mimeType) => {
    expect(planConversion({ ...small, mimeType })).toEqual({ convert: false });
  });

  it.each(['image/heic', 'image/heif', 'image/gif', 'image/bmp', undefined])(
    '서버가 받지 않는 형식(%s)은 변환한다',
    (mimeType) => {
      expect(planConversion({ ...small, mimeType })).toEqual({ convert: true, reason: 'format' });
    },
  );

  it('정확히 5MB 는 그대로, 1바이트라도 넘으면 변환한다', () => {
    expect(planConversion({ ...small, mimeType: 'image/jpeg', size: MAX_UPLOAD_BYTES })).toEqual({ convert: false });
    expect(planConversion({ ...small, mimeType: 'image/jpeg', size: MAX_UPLOAD_BYTES + 1 })).toEqual({
      convert: true,
      reason: 'size',
    });
  });

  it('긴 변이 1600 을 넘으면 용량이 작아도 줄인다', () => {
    expect(planConversion({ mimeType: 'image/jpeg', width: 1600, height: 1200, size: MB })).toEqual({ convert: false });
    expect(planConversion({ mimeType: 'image/jpeg', width: 1200, height: 1601, size: MB })).toEqual({
      convert: true,
      reason: 'dimension',
    });
  });

  it('크기를 모르는 사진(0 x 0)은 형식과 용량만 본다', () => {
    expect(planConversion({ mimeType: 'image/png', width: 0, height: 0, size: MB })).toEqual({ convert: false });
  });
});

describe('resizeTarget (긴 변 기준 축소)', () => {
  it('가로가 길면 가로를, 세로가 길면 세로를 맞춘다', () => {
    expect(resizeTarget(4000, 3000, 1600)).toEqual({ width: 1600 });
    expect(resizeTarget(3000, 4000, 1600)).toEqual({ height: 1600 });
    expect(resizeTarget(2000, 2000, 1600)).toEqual({ width: 1600 });
  });

  it('이미 작으면 줄이지 않는다 (키우지 않는다)', () => {
    expect(resizeTarget(1600, 1200, 1600)).toBeUndefined();
    expect(resizeTarget(800, 600, 1600)).toBeUndefined();
    expect(resizeTarget(0, 0, 1600)).toBeUndefined();
  });
});

describe('prepareImage', () => {
  it('작은 JPEG 는 변환 없이 읽은 크기 그대로 올린다', async () => {
    const { tools, conversions } = fakeTools({ original: 345_678 });

    const prepared = await prepareImage(picked(), tools);

    expect(conversions).toEqual([]);
    expect(prepared).toMatchObject({ uri: 'original', contentType: 'image/jpeg', size: 345_678 });
    expect(prepared.body?.size).toBe(345_678);
  });

  it('PNG 와 WebP 는 형식을 유지한다', async () => {
    const { tools } = fakeTools({ original: 1000 });
    expect((await prepareImage(picked({ mimeType: 'image/png' }), tools)).contentType).toBe('image/png');
    expect((await prepareImage(picked({ mimeType: 'image/webp' }), tools)).contentType).toBe('image/webp');
  });

  it('HEIC 는 JPEG 로 바꾸고, 큰 사진이면 긴 변을 1600 으로 줄인다', async () => {
    const { tools, conversions } = fakeTools({ original: 3 * MB, 'converted-1': 600_000 });

    const prepared = await prepareImage(
      picked({ mimeType: 'image/heic', fileName: 'IMG_1.HEIC', width: 4032, height: 3024 }),
      tools,
    );

    expect(conversions).toEqual([{ uri: 'original', resize: { width: 1600 }, quality: 0.8 }]);
    expect(prepared).toMatchObject({ uri: 'converted-1', contentType: 'image/jpeg', size: 600_000 });
  });

  it('작은 HEIC 는 크기는 그대로 두고 형식만 바꾼다', async () => {
    const { tools, conversions } = fakeTools({ original: 200_000, 'converted-1': 150_000 });

    await prepareImage(picked({ mimeType: 'image/heic', width: 800, height: 600 }), tools);

    expect(conversions).toEqual([{ uri: 'original', resize: undefined, quality: 0.8 }]);
  });

  it('형식을 알려 주지 않아도 파일 이름이 .heic 면 변환한다', async () => {
    const { tools, conversions } = fakeTools({ original: 200_000, 'converted-1': 150_000 });

    const prepared = await prepareImage(picked({ mimeType: undefined, fileName: 'IMG_2.heic' }), tools);

    expect(conversions).toHaveLength(1);
    expect(prepared.contentType).toBe('image/jpeg');
  });

  it('5MB 를 넘는 PNG 는 JPEG 로 바뀐다', async () => {
    const { tools } = fakeTools({ original: 9 * MB, 'converted-1': 800_000 });

    const prepared = await prepareImage(picked({ mimeType: 'image/png', width: 1500, height: 1500 }), tools);

    expect(prepared).toMatchObject({ contentType: 'image/jpeg', size: 800_000 });
  });

  it('줄여도 5MB 를 넘으면 다음 단계로 더 줄인다. 항상 원본에서 다시 만든다', async () => {
    const { tools, conversions } = fakeTools({
      original: 30 * MB,
      'converted-1': 7 * MB,
      'converted-2': MAX_UPLOAD_BYTES + 1,
      'converted-3': MAX_UPLOAD_BYTES,
    });

    const prepared = await prepareImage(picked({ width: 6000, height: 4000 }), tools);

    expect(conversions).toEqual([
      { uri: 'original', resize: { width: 1600 }, quality: 0.8 },
      { uri: 'original', resize: { width: 1280 }, quality: 0.7 },
      { uri: 'original', resize: { width: 1024 }, quality: 0.6 },
    ]);
    expect(prepared).toMatchObject({ uri: 'converted-3', size: MAX_UPLOAD_BYTES });
  });

  it('끝까지 줄여도 5MB 를 넘으면 TOO_LARGE', async () => {
    const { tools, conversions } = fakeTools({
      original: 30 * MB,
      'converted-1': 9 * MB,
      'converted-2': 8 * MB,
      'converted-3': 7 * MB,
      'converted-4': 6 * MB,
    });

    const error = await failure(prepareImage(picked({ width: 6000, height: 4000 }), tools));

    expect(error.reason).toBe('TOO_LARGE');
    expect(conversions).toHaveLength(4);
  });

  it('기기가 풀지 못하는 형식이면 UNSUPPORTED (예: HEIC 를 모르는 브라우저)', async () => {
    const { tools } = fakeTools({ original: 2 * MB });
    (tools.toJpeg as jest.Mock).mockRejectedValue(new Error('decode failed'));

    const error = await failure(prepareImage(picked({ mimeType: 'image/heic' }), tools));

    expect(error.reason).toBe('UNSUPPORTED');
    expect(error.message).toContain('변환할 수 없는 사진 형식');
  });

  it('파일을 읽을 수 없거나 비어 있으면 UNREADABLE', async () => {
    expect((await failure(prepareImage(picked({ uri: 'missing' }), fakeTools({}).tools))).reason).toBe('UNREADABLE');
    expect((await failure(prepareImage(picked(), fakeTools({ original: 0 }).tools))).reason).toBe('UNREADABLE');
  });

  it('선택 창이 알려 준 크기가 아니라 실제로 읽은 크기를 쓴다', async () => {
    const { tools } = fakeTools({ original: 123_456 });
    // fileSize 같은 값은 PickedImage 에 아예 없다. size 는 read 의 결과에서만 온다
    const prepared = await prepareImage(picked(), tools);
    expect(prepared.size).toBe(prepared.body?.size);
  });

  it('앱처럼 내용 없이 크기만 읽어도 준비된다. 크기는 변환한 뒤의 파일 기준이다', async () => {
    const sizes: Record<string, number> = { original: 9 * MB, 'converted-1': 700_000 };
    const tools: ImageTools = {
      read: jest.fn(async (uri: string) => ({ size: sizes[uri] })),
      toJpeg: jest.fn(async () => ({ uri: 'converted-1' })),
    };

    const prepared = await prepareImage(picked({ width: 4000, height: 3000 }), tools);

    expect(prepared).toEqual({ uri: 'converted-1', contentType: 'image/jpeg', size: 700_000, body: undefined });
  });
});
