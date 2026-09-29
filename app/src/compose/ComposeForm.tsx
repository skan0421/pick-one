// 고민 올리기 입력 화면 (POST /questions, docs/api.md 4.1).
//
// 이 파일은 입력값을 들고 있다가 제출할 뿐이고, 규칙은 composeRules.ts 에 있다.
// Spring 에 빗대면 이 파일이 컨트롤러 + 폼 객체, composeRules.ts 가 Validator 다
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useRef, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { Button, HelperText, IconButton, Text, TextInput } from 'react-native-paper';

import { createQuestion } from '../api/questions';
import { MY_QUESTIONS_KEY } from '../mine/queryKeys';
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
  validateTextDraft,
  type ComposeErrors,
} from './composeRules';

const EMPTY_OPTIONS = ['', ''];

export function ComposeForm() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [content, setContent] = useState('');
  const [options, setOptions] = useState<string[]>(EMPTY_OPTIONS);
  const [errors, setErrors] = useState<ComposeErrors>(NO_ERRORS);
  const [submitting, setSubmitting] = useState(false);
  // 제출 잠금. submitting 은 화면이 다시 그려진 뒤에야 버튼을 막으므로, 그 사이에 한 번 더 눌리면 요청이 두 번 나간다.
  // useRef 의 값은 바꾸는 즉시 반영된다 (Java 의 AtomicBoolean.compareAndSet 자리. 단일 스레드라 일반 값으로 충분하다)
  const locked = useRef(false);

  function changeContent(value: string) {
    setContent(value);
    // 고치기 시작한 칸의 오류는 지운다. 다른 칸의 오류는 남긴다
    setErrors((current) => ({ ...current, content: undefined, form: undefined }));
  }

  function changeOption(index: number, value: string) {
    setOptions((current) => current.map((option, i) => (i === index ? value : option)));
    setErrors((current) => ({
      ...current,
      options: current.options.map((error, i) => (i === index ? undefined : error)),
      optionList: undefined,
      form: undefined,
    }));
  }

  function changeOptionCount(next: string[]) {
    setOptions(next);
    // 칸 수가 바뀌면 칸별 오류의 위치가 어긋나므로 선택지 오류를 모두 지운다
    setErrors((current) => ({ ...current, options: [], optionList: undefined }));
  }

  async function submit() {
    if (locked.current) {
      return;
    }
    // 1. 앱에서 먼저 검사한다. 오류가 있으면 서버에 보내지 않는다
    const local = validateTextDraft(content, options);
    if (hasErrors(local)) {
      setErrors(local);
      return;
    }

    locked.current = true;
    setSubmitting(true);
    setErrors(NO_ERRORS);
    try {
      await createQuestion(toTextRequest(content, options));
    } catch (error) {
      // 2. 서버가 거절했다면 서버의 오류를 해당 입력칸 아래에 보여 준다
      setErrors(errorsFromServer(error, options.length));
      setSubmitting(false);
      locked.current = false;
      return;
    }

    // 3. 성공. 입력을 비우고 내 고민 탭으로 간다.
    // resetQueries 는 내 고민 목록 캐시를 버리고 첫 쪽부터 다시 받게 한다 (@CacheEvict).
    // 목록은 최신순이므로 방금 올린 고민이 맨 위에 온다
    setContent('');
    setOptions(EMPTY_OPTIONS);
    setSubmitting(false);
    locked.current = false;
    void queryClient.resetQueries({ queryKey: MY_QUESTIONS_KEY });
    router.navigate('/my-questions');
  }

  const contentLength = content.trim().length;

  return (
    <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
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
        카톡 멘트는 캡처 대신 보낼 문장만 적어주세요
      </Text>

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
      <HelperText type="error" visible={!!errors.optionList} testID="compose-option-list-error">
        {errors.optionList}
      </HelperText>

      <HelperText type="error" visible={!!errors.form} testID="compose-form-error">
        {errors.form}
      </HelperText>
      <Button mode="contained" onPress={submit} loading={submitting} disabled={submitting} testID="compose-submit">
        올리기
      </Button>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { padding: 24, width: '100%', maxWidth: 640, alignSelf: 'center' },
  underInput: { flexDirection: 'row', alignItems: 'flex-start' },
  grow: { flex: 1 },
  guide: { opacity: 0.7, marginBottom: 12 },
  optionRow: { flexDirection: 'row', alignItems: 'center' },
});
