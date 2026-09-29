// 마이 탭. 닉네임과 로그아웃 버튼만 있다
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Button, Text } from 'react-native-paper';

import { useAuth } from '../../auth/AuthContext';

export default function MyScreen() {
  const { member, signOut } = useAuth();
  const [busy, setBusy] = useState(false);

  async function handleLogout() {
    setBusy(true);
    // 로그아웃이 끝나면 상태가 signedOut 이 되어 로그인 화면으로 이동한다
    await signOut();
  }

  return (
    <View style={styles.center}>
      <Text variant="headlineSmall">마이</Text>
      <Text testID="my-nickname">{member?.nickname}</Text>
      <Button mode="outlined" onPress={handleLogout} loading={busy} disabled={busy} testID="logout">
        로그아웃
      </Button>
    </View>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 16 },
});
