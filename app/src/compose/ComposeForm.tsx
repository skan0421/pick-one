// 고민 올리기 입력 화면 (POST /questions, docs/api.md 4.1).
//
// 이 파일은 입력값을 들고 있다가 제출할 뿐이고, 규칙은 다른 파일에 있다.
//   composeRules.ts  입력 규칙, 서버 오류를 입력칸으로 나누기   (Spring 의 Validator)
//   imagePrep.ts     사진을 변환할지, 얼마나 줄일지
//   uploadFlow.ts    발급 → 올리기 → 등록의 순서
//   composeKey.ts    등록 요청에 붙일 Idempotency-Key 규칙
//   createFlow.ts    키를 붙여 등록하고, 결과에 따라 키를 유지하거나 버림
//   imageTools.ts    사진 고르기·변환 (기기 기능)
//   fileTransfer.ts  파일 크기 읽기·저장소로 올리기 (기기 기능. 웹은 fileTransfer.web.ts)
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useRef, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { Button, HelperText, IconButton, ProgressBar, SegmentedButtons, Text, TextInput } from 'react-native-paper';

import { errorMessage } from '../api/errors';
import { createQuestion, type CreateQuestionRequest, type QuestionType } from '../api/questions';
import { issueUploadUrl } from '../api/uploads';
import { MY_QUESTIONS_KEY } from '../mine/queryKeys';
import { newUuid } from '../mine/uuid';
import { ComposeKey } from './composeKey';
import {
  addOption,
  canAddOption,
  canRemoveOption,
  CONTENT_MAX,
  errorsFromServer,
  hasErrors,
  NO_ERRORS,
  OPTION_MAX,
  removeOption,
  toTextRequest,
  validateImageDraft,
  validateTextDraft,
  type ComposeErrors,
} from './composeRules';
import { createWithKey, type CreateApi } from './createFlow';
import { prepareImage } from './imagePrep';
import { EMPTY_SLOT, ImageSlots, type ImageSlot } from './ImageSlots';
import { putFile } from './fileTransfer';
import { imageTools, pickImage } from './imageTools';
import { submitImageQuestion, type UploadApi, type UploadSlot } from './uploadFlow';

const EMPTY_OPTIONS = ['', ''];
const EMPTY_SLOTS: ImageSlot[] = [EMPTY_SLOT, EMPTY_SLOT];

// 유형별 안내 문구 (docs/planning.md 의 콘텐츠 규칙)
const GUIDES: Record<QuestionType, string> = {
  TEXT: '카톡 멘트는 캡처 대신 보낼 문장만 적어주세요',
  IMAGE: '사진형은 얼굴 대신 옷만 보이게',
};

// 등록 요청의 키. 화면 밖에 두어, 결과를 모르는 실패 뒤 다른 탭에 다녀와도 같은 키가 나가게 한다 (mine/MyQuestionsList.tsx 의 boostKeys 와 같다)
const composeKey = new ComposeKey(newUuid);
const createApi: CreateApi = { createQuestion };

// 글형·사진형 모두 이 함수로 등록한다. 키는 여기서만 붙는다
function create(body: CreateQuestionRequest) {
  return createWithKey(createApi, composeKey, body);
}

// 사진형은 사진을 다 올린 뒤 등록한다. 발급과 PUT 에는 키가 붙지 않는다
const uploadApi: UploadApi = { issueUploadUrl, putFile, createQuestion: create };

export function ComposeForm() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [type, setType] = useState<QuestionType>('TEXT');
  const [content, setContent] = useState('');
  const [options, setOptions] = useState<string[]>(EMPTY_OPTIONS);
  const [slots, setSlots] = useState<ImageSlot[]>(EMPTY_SLOTS);
  const [errors, setErrors] = useState<ComposeErrors>(NO_ERRORS);
  const [submitting, setSubmitting] = useState(false);
  // 제출 잠금. submitting 은 화면이 다시 그려진 뒤에야 버튼을 막으므로, 그 사이에 한 번 더 눌리면 요청이 두 번 나간다.
  // useRef 의 값은 바꾸는 즉시 반영된다 (Java 의 AtomicBoolean.compareAndSet 자리. 단일 스레드라 일반 값으로 충분하다)
  const locked = useRef(false);
  // 칸마다 "몇 번째로 고른 사진인지". 사진 준비가 끝났을 때 그 사이 다른 사진을 골랐다면 늦게 끝난 쪽을 버린다
  const pickSeq = useRef([0, 0]);

  function changeType(next: string) {
    setType(next as QuestionType);
    // 유형을 바꾸면 선택지 칸의 뜻이 달라지므로 선택지 오류를 지운다. 본문과 입력해 둔 값은 남긴다
    setErrors((current) => ({ ...current, options: [], optionList: undefined, form: undefined }));
  }

  function changeContent(value: string) {
    setContent(value);
    // 고치기 시작한 칸의 오류는 지운다. 다른 칸의 오류는 남긴다
    setErrors((current) => ({ ...current, content: undefined, form: undefined }));
  }

  function changeOption(index: number, value: string) {
    setOptions((current) => current.map((option, i) => (i === index ? value : option)));
    clearOptionError(index);
  }

  function changeOptionCount(next: string[]) {
    setOptions(next);
    // 칸 수가 바뀌면 칸별 오류의 위치가 어긋나므로 선택지 오류를 모두 지운다
    setErrors((current) => ({ ...current, options: [], optionList: undefined }));
  }

  function clearOptionError(index: number) {
    setErrors((current) => ({
      ...current,
      options: current.options.map((error, i) => (i === index ? undefined : error)),
      optionList: undefined,
      form: undefined,
    }));
  }

  function setOptionError(index: number, message: string) {
    setErrors((current) => {
      const next = [...current.options];
      next[index] = message;
      return { ...current, options: next };
    });
  }

  function updateSlot(index: number, change: (slot: ImageSlot) => ImageSlot) {
    setSlots((current) => current.map((slot, i) => (i === index ? change(slot) : slot)));
  }

  async function pick(index: number) {
    // 웹에서는 누른 그 순간에 선택 창을 열어야 하므로 다른 일을 하기 전에 먼저 부른다
    let picked;
    try {
      picked = await pickImage();
    } catch (error) {
      // 고를 수 없는 파일. 칸에 있던 사진은 그대로 둔다
      setOptionError(index, errorMessage(error));
      return;
    }
    if (!picked) {
      return;
    }
    const seq = (pickSeq.current[index] += 1);
    clearOptionError(index);
    updateSlot(index, (slot) => ({ ...slot, preparing: true }));
    try {
      // 서버가 받는 형식·크기로 맞춘다 (아이폰 HEIC → JPEG, 큰 사진은 축소)
      const image = await prepareImage(picked, imageTools);
      if (pickSeq.current[index] === seq) {
        // 새 사진이므로 앞 사진의 "올리기 완료" 기록은 버린다
        updateSlot(index, () => ({ image, preparing: false }));
      }
    } catch (error) {
      if (pickSeq.current[index] === seq) {
        updateSlot(index, (slot) => ({ ...slot, preparing: false }));
        setOptionError(index, errorMessage(error));
      }
    }
  }

  async function submit() {
    if (locked.current) {
      return;
    }
    // 1. 앱에서 먼저 검사한다. 오류가 있으면 서버에 보내지 않는다
    const local =
      type === 'TEXT'
        ? validateTextDraft(content, options)
        : validateImageDraft(
            content,
            slots.map((slot) => !!slot.image && !slot.preparing),
          );
    if (hasErrors(local)) {
      setErrors(local);
      return;
    }

    locked.current = true;
    setSubmitting(true);
    setErrors(NO_ERRORS);
    const created = type === 'TEXT' ? await submitText() : await submitImages();
    setSubmitting(false);
    locked.current = false;
    if (!created) {
      return;
    }

    // 3. 성공. 입력을 비우고 내 고민 탭으로 간다.
    // resetQueries 는 내 고민 목록 캐시를 버리고 첫 쪽부터 다시 받게 한다 (@CacheEvict).
    // 목록은 최신순이므로 방금 올린 고민이 맨 위에 온다
    setContent('');
    setOptions(EMPTY_OPTIONS);
    setSlots(EMPTY_SLOTS);
    setType('TEXT');
    void queryClient.resetQueries({ queryKey: MY_QUESTIONS_KEY });
    router.navigate('/my-questions');
  }

  // 돌려주는 값: 등록됐으면 true
  async function submitText(): Promise<boolean> {
    try {
      await create(toTextRequest(content, options));
      return true;
    } catch (error) {
      // 2. 서버가 거절했다면 서버의 오류를 해당 입력칸 아래에 보여 준다
      setErrors(errorsFromServer(error, options.length));
      return false;
    }
  }

  async function submitImages(): Promise<boolean> {
    const uploadSlots: UploadSlot[] = slots.map((slot) => ({
      image: slot.image!, // 위 검증에서 두 칸 모두 사진이 있는 것을 확인했다
      uploadedUrl: slot.uploadedUrl,
    }));
    setSlots((current) => current.map((slot) => ({ ...slot, status: slot.uploadedUrl ? 'done' : 'waiting' })));

    const outcome = await submitImageQuestion(uploadApi, content, uploadSlots, (index, status) =>
      updateSlot(index, (slot) => ({ ...slot, status })),
    );
    if (outcome.kind === 'created') {
      return true;
    }

    // 올라간 사진의 주소를 기억해 둔다. 다시 시도할 때 그 사진은 건너뛴다
    setSlots((current) =>
      current.map((slot, i) => ({ ...slot, uploadedUrl: outcome.urls[i], status: outcome.urls[i] ? 'done' : undefined })),
    );
    if (outcome.kind === 'uploadFailed') {
      const next: ComposeErrors = { options: slots.map(() => undefined) };
      for (const failure of outcome.failures) {
        next.options[failure.index] = failure.message;
      }
      next.form = '사진을 올리지 못해 등록하지 않았습니다. 다시 시도해 주세요.';
      setErrors(next);
    } else {
      setErrors(errorsFromServer(outcome.error, slots.length));
    }
    return false;
  }

  const contentLength = content.trim().length;
  const preparing = slots.some((slot) => slot.preparing);
  const uploaded = slots.filter((slot) => slot.status === 'done').length;

  return (
    <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
      <SegmentedButtons
        value={type}
        onValueChange={changeType}
        buttons={[
          { value: 'TEXT', label: '글형', icon: 'format-text', disabled: submitting, testID: 'compose-type-text' },
          { value: 'IMAGE', label: '사진형', icon: 'image-multiple', disabled: submitting, testID: 'compose-type-image' },
        ]}
        style={styles.type}
      />

      <TextInput
        label="고민"
        value={content}
        onChangeText={changeContent}
        multiline
        numberOfLines={4}
        disabled={submitting}
        error={!!errors.content}
        testID="compose-content"
      />
      <View style={styles.underInput}>
        <HelperText type="error" visible={!!errors.content} style={styles.grow} testID="compose-content-error">
          {errors.content}
        </HelperText>
        <HelperText type={contentLength > CONTENT_MAX ? 'error' : 'info'} testID="compose-content-count">
          {contentLength} / {CONTENT_MAX}
        </HelperText>
      </View>

      <Text variant="bodySmall" style={styles.guide} testID="compose-guide">
        {GUIDES[type]}
      </Text>

      {type === 'TEXT' ? (
        <>
          {options.map((option, index) => (
            // 선택지는 추가·삭제로 순서가 바뀔 수 있지만 칸 자체를 구분할 id 가 없어 위치를 key 로 쓴다
            <View key={index}>
              <View style={styles.optionRow}>
                <TextInput
                  label={`선택지 ${index + 1}`}
                  value={option}
                  onChangeText={(value) => changeOption(index, value)}
                  disabled={submitting}
                  error={!!errors.options[index]}
                  style={styles.grow}
                  testID={`compose-option-${index + 1}`}
                />
                <IconButton
                  icon="close"
                  onPress={() => changeOptionCount(removeOption(options, index))}
                  disabled={submitting || !canRemoveOption(options)}
                  accessibilityLabel={`선택지 ${index + 1} 삭제`}
                  testID={`compose-option-remove-${index + 1}`}
                />
              </View>
              <View style={styles.underInput}>
                <HelperText
                  type="error"
                  visible={!!errors.options[index]}
                  style={styles.grow}
                  testID={`compose-option-error-${index + 1}`}>
                  {errors.options[index]}
                </HelperText>
                <HelperText type={option.trim().length > OPTION_MAX ? 'error' : 'info'}>
                  {option.trim().length} / {OPTION_MAX}
                </HelperText>
              </View>
            </View>
          ))}
          <Button
            icon="plus"
            onPress={() => changeOptionCount(addOption(options))}
            disabled={submitting || !canAddOption(options)}
            testID="compose-option-add">
            선택지 추가
          </Button>
        </>
      ) : (
        <>
          <ImageSlots slots={slots} errors={errors.options} disabled={submitting} onPick={pick} />
          {submitting && (
            <View style={styles.progress} testID="compose-upload-progress">
              <Text variant="labelMedium">
                사진 올리는 중 {uploaded} / {slots.length}
              </Text>
              <ProgressBar progress={uploaded / slots.length} />
            </View>
          )}
        </>
      )}

      <HelperText type="error" visible={!!errors.optionList} testID="compose-option-list-error">
        {errors.optionList}
      </HelperText>

      <HelperText type="error" visible={!!errors.form} testID="compose-form-error">
        {errors.form}
      </HelperText>
      <Button
        mode="contained"
        onPress={submit}
        loading={submitting}
        // 사진을 준비하는 중에도 막는다. 준비가 끝나야 올릴 크기를 알 수 있다
        disabled={submitting || (type === 'IMAGE' && preparing)}
        testID="compose-submit">
        올리기
      </Button>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { padding: 24, width: '100%', maxWidth: 640, alignSelf: 'center' },
  type: { marginBottom: 16 },
  underInput: { flexDirection: 'row', alignItems: 'flex-start' },
  grow: { flex: 1 },
  guide: { opacity: 0.7, marginBottom: 12 },
  optionRow: { flexDirection: 'row', alignItems: 'center' },
  progress: { gap: 6, marginTop: 8 },
});
