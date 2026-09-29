// 로그인 화면 (POST /auth/login)
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ScrollView, StyleSheet } from 'react-native';
import { Button, HelperText, TextInput } from 'react-native-paper';

import { login } from '../../api/auth';
import { ApiError, errorMessage } from '../../api/errors';
import { useAuth } from '../../auth/AuthContext';

export default function LoginScreen() {
  const router = useRouter();
  const { signIn } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);

  // 입력칸별 오류는 VALIDATION_ERROR 의 errors 에서 찾는다
  const fieldError = (field: string) => (error instanceof ApiError ? error.fieldError(field) : undefined);
  // 입력칸에 속하지 않는 오류(비밀번호 불일치, 서버 연결 실패 등)는 버튼 위에 보여 준다
  const formError = error && !(error instanceof ApiError && error.fieldErrors.length > 0) ? errorMessage(error) : null;

  async function submit() {
    setSubmitting(true);
    setError(null);
    try {
      const response = await login({ email: email.trim(), password });
      // 상태가 바뀌면 최상위 레이아웃이 알맞은 화면(휴대폰 인증 또는 탭)으로 보낸다
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
        testID="login-email"
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
        testID="login-password"
      />
      <HelperText type="error" visible={!!fieldError('password')}>
        {fieldError('password')}
      </HelperText>

      <HelperText type="error" visible={!!formError} testID="login-error">
        {formError}
      </HelperText>

      <Button mode="contained" onPress={submit} loading={submitting} disabled={submitting} testID="login-submit">
        로그인
      </Button>
      <Button onPress={() => router.push('/signup')} disabled={submitting} testID="go-signup">
        이메일로 가입
      </Button>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { padding: 24, width: '100%', maxWidth: 480, alignSelf: 'center' },
});
