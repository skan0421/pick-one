// 휴대폰 인증 화면. 가입 상태가 PENDING_PHONE 인 회원만 들어온다.
//   1) 번호 입력 → 인증번호 요청 (POST /phone-verifications)
//   2) 인증번호 입력 → 확인 (POST /phone-verifications/confirm) → ACTIVE
import { useState } from 'react';
import { ScrollView, StyleSheet } from 'react-native';
import { Button, HelperText, Text, TextInput } from 'react-native-paper';

import { ApiError, errorMessage } from '../api/errors';
import { confirmVerification, sendVerification } from '../api/phone';
import { useAuth } from '../auth/AuthContext';

export default function VerifyPhoneScreen() {
  const { signIn, signOut } = useAuth();
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  // 인증번호를 보낸 번호. 값이 있으면 인증번호 입력칸을 보여 준다
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const fieldError = (field: string) => (error instanceof ApiError ? error.fieldError(field) : undefined);
  const formError = error && !(error instanceof ApiError && error.fieldErrors.length > 0) ? errorMessage(error) : null;

  async function send() {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const target = phone.trim();
      const response = await sendVerification(target);
      setSentTo(target);
      setCode('');
      setNotice(
        `인증번호를 보냈습니다. ${Math.floor(response.expiresInSeconds / 60)}분 안에 입력해 주세요. ` +
          `다시 요청은 ${response.cooldownSeconds}초 뒤에 할 수 있습니다.`,
      );
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  async function confirm() {
    if (!sentTo) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const response = await confirmVerification(sentTo, code.trim());
      // 응답에 든 새 토큰으로 바꿔야 한다. 옛 토큰에는 PENDING_PHONE 이 적혀 있어 계속 403 이 난다
      await signIn(response);
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  return (
    <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
      <Text>가입을 마치려면 휴대폰 인증이 필요합니다.</Text>
      <Text variant="bodySmall" style={styles.hint}>
        로컬 서버는 문자를 실제로 보내지 않습니다. 인증번호는 서버 로그의 [SMS 본문] 줄에서 확인하세요.
      </Text>

      <TextInput
        label="휴대폰 번호"
        value={phone}
        onChangeText={setPhone}
        keyboardType="phone-pad"
        placeholder="01012345678"
        error={!!fieldError('phone')}
        testID="phone-number"
      />
      <HelperText type="error" visible={!!fieldError('phone')}>
        {fieldError('phone')}
      </HelperText>
      <Button mode={sentTo ? 'outlined' : 'contained'} onPress={send} disabled={busy} testID="phone-send">
        {sentTo ? '인증번호 다시 받기' : '인증번호 받기'}
      </Button>

      <HelperText type="info" visible={!!notice} testID="phone-notice">
        {notice}
      </HelperText>

      {/* 중괄호 안은 자바스크립트 식이다. "조건 && 화면" 은 조건이 참일 때만 화면을 그린다 */}
      {sentTo && (
        <>
          <TextInput
            label="인증번호 6자리"
            value={code}
            onChangeText={setCode}
            keyboardType="number-pad"
            maxLength={6}
            error={!!fieldError('code')}
            testID="phone-code"
          />
          <HelperText type="error" visible={!!fieldError('code')}>
            {fieldError('code')}
          </HelperText>
          <Button mode="contained" onPress={confirm} disabled={busy || code.trim().length !== 6} testID="phone-confirm">
            인증 확인
          </Button>
        </>
      )}

      <HelperText type="error" visible={!!formError} testID="phone-error">
        {formError}
      </HelperText>

      {/* 인증을 끝낼 수 없는 상황에서 화면에 갇히지 않도록 나가는 길을 둔다 */}
      <Button onPress={signOut} disabled={busy} testID="phone-logout">
        로그아웃
      </Button>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { padding: 24, width: '100%', maxWidth: 480, alignSelf: 'center' },
  hint: { marginTop: 8, marginBottom: 16 },
});
