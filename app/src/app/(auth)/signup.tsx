// 이메일 가입 화면 (POST /auth/signup)
// 가입에 성공하면 회원은 PENDING_PHONE 상태가 되고 토큰을 바로 받는다. 이어서 휴대폰 인증 화면으로 넘어간다
import { useState } from 'react';
import { ScrollView, StyleSheet } from 'react-native';
import { Button, HelperText, TextInput } from 'react-native-paper';

import { signup } from '../../api/auth';
import { ApiError, errorMessage } from '../../api/errors';
import { useAuth } from '../../auth/AuthContext';

export default function SignupScreen() {
  const { signIn } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const fieldError = (field: string) => (error instanceof ApiError ? error.fieldError(field) : undefined);
  const formError = error && !(error instanceof ApiError && error.fieldErrors.length > 0) ? errorMessage(error) : null;

  async function submit() {
    setSubmitting(true);
    setError(null);
    try {
      // 입력값 검증은 서버가 한다. 화면은 서버가 돌려준 오류를 입력칸 아래에 보여 주기만 한다
      const response = await signup({ email: email.trim(), password, nickname: nickname.trim() });
      await signIn(response);
    } catch (e) {
      setError(e);
      setSubmitting(false);
    }
  }

  return (
    <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
      <TextInput
        label="이메일"
        value={email}
        onChangeText={setEmail}
        autoCapitalize="none"
        keyboardType="email-address"
        error={!!fieldError('email')}
        testID="signup-email"
      />
      <HelperText type="error" visible={!!fieldError('email')}>
        {fieldError('email')}
      </HelperText>

      <TextInput
        label="비밀번호"
        value={password}
        onChangeText={setPassword}
        secureTextEntry
        error={!!fieldError('password')}
        testID="signup-password"
      />
      <HelperText type={fieldError('password') ? 'error' : 'info'} visible>
        {fieldError('password') ?? '8~64자, 영문과 숫자를 모두 포함'}
      </HelperText>

      <TextInput
        label="닉네임"
        value={nickname}
        onChangeText={setNickname}
        autoCapitalize="none"
        error={!!fieldError('nickname')}
        testID="signup-nickname"
      />
      <HelperText type={fieldError('nickname') ? 'error' : 'info'} visible>
        {fieldError('nickname') ?? '2~30자, 한글·영문·숫자'}
      </HelperText>

      <HelperText type="error" visible={!!formError} testID="signup-error">
        {formError}
      </HelperText>

      <Button mode="contained" onPress={submit} loading={submitting} disabled={submitting} testID="signup-submit">
        가입하기
      </Button>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { padding: 24, width: '100%', maxWidth: 480, alignSelf: 'center' },
});
