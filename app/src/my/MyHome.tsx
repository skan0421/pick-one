// 마이 탭: 닉네임, 포인트, 메뉴, 로그아웃.
//
// 포인트는 GET /points/balance 로 받는다 (docs/api.md 6.1).
// GET /members/me 의 pointBalance 는 서버가 항상 0 을 주므로 쓰지 않는다
import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { Button, Card, Divider, IconButton, List, Snackbar, Text } from 'react-native-paper';

import type { MemberSummary } from '../api/auth';
import { errorMessage } from '../api/errors';
import { getBalance } from '../api/points';
import { useAuth } from '../auth/AuthContext';
import { POINT_BALANCE_KEY } from '../feed/useFeed';
import { NicknameDialog } from './NicknameDialog';

export function MyHome() {
  const router = useRouter();
  const { member, signOut, updateMember } = useAuth();
  // 피드와 같은 키를 쓴다. 피드에서 투표해 잔액이 바뀌면 이 화면도 새 값을 받는다 (같은 캐시를 보는 두 화면)
  const balance = useQuery({ queryKey: POINT_BALANCE_KEY, queryFn: getBalance });

  const [editing, setEditing] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const [notice, setNotice] = useState<{ seq: number; text: string } | null>(null);

  function showNotice(text: string) {
    setNotice((current) => ({ seq: (current?.seq ?? 0) + 1, text }));
  }

  function onNicknameChanged(next: MemberSummary) {
    updateMember(next);
    setEditing(false);
    showNotice('닉네임을 바꿨어요.');
  }

  async function handleLogout() {
    setLoggingOut(true);
    // 로그아웃이 끝나면 상태가 signedOut 이 되어 로그인 화면으로 이동한다
    await signOut();
  }

  return (
    <View style={styles.screen}>
      <ScrollView contentContainerStyle={styles.body}>
        <Card style={styles.card}>
          <Card.Content style={styles.profile}>
            <View style={styles.grow}>
              <Text variant="labelMedium">닉네임</Text>
              <Text variant="headlineSmall" testID="my-nickname">
                {member?.nickname}
              </Text>
            </View>
            <Button mode="outlined" onPress={() => setEditing(true)} testID="my-nickname-edit">
              변경
            </Button>
          </Card.Content>
        </Card>

        <Card style={styles.card}>
          <Card.Content style={styles.points}>
            <View style={styles.row}>
              <Text variant="labelMedium">내 포인트</Text>
              <IconButton
                icon="refresh"
                size={18}
                onPress={() => void balance.refetch()}
                disabled={balance.isFetching}
                accessibilityLabel="포인트 새로고침"
                testID="my-point-refresh"
              />
            </View>
            {balance.data ? (
              <>
                <Text variant="displaySmall" testID="my-point-balance">
                  {balance.data.balance}P
                </Text>
                <Text variant="bodyMedium" testID="my-point-today">
                  오늘 적립 {balance.data.todayEarned} / {balance.data.dailyEarnLimit}P
                </Text>
                {balance.data.todayEarned >= balance.data.dailyEarnLimit && (
                  <Text variant="labelMedium" testID="my-point-limit">
                    오늘 적립 한도에 도달했어요. 투표는 계속할 수 있어요.
                  </Text>
                )}
              </>
            ) : balance.isError ? (
              <Text testID="my-point-error">{errorMessage(balance.error)}</Text>
            ) : (
              <Text variant="displaySmall">-</Text>
            )}
          </Card.Content>
        </Card>

        <Card style={styles.card}>
          {/* List.Item 은 한 줄짜리 메뉴다. right 에는 오른쪽 끝에 그릴 것을 넣는다 */}
          <List.Item
            title="포인트 내역"
            left={(props) => <List.Icon {...props} icon="format-list-bulleted" />}
            right={(props) => <List.Icon {...props} icon="chevron-right" />}
            onPress={() => router.push('/point-ledger')}
            testID="my-menu-ledger"
          />
          <Divider />
          <List.Item
            title="내가 투표한 고민"
            left={(props) => <List.Icon {...props} icon="vote-outline" />}
            right={(props) => <List.Icon {...props} icon="chevron-right" />}
            onPress={() => router.push('/my-votes')}
            testID="my-menu-votes"
          />
          <Divider />
          {/* 아래 두 메뉴는 자리만 있다. 누를 수 없고 "준비 중" 을 보여 준다 */}
          <List.Item
            title="지인에게 숨기기"
            description="준비 중"
            disabled
            left={(props) => <List.Icon {...props} icon="account-off-outline" />}
            style={styles.soon}
            testID="my-menu-hide"
          />
          <Divider />
          <List.Item
            title="차단 목록"
            description="준비 중"
            disabled
            left={(props) => <List.Icon {...props} icon="cancel" />}
            style={styles.soon}
            testID="my-menu-blocks"
          />
        </Card>

        <Button
          mode="outlined"
          onPress={handleLogout}
          loading={loggingOut}
          disabled={loggingOut}
          style={styles.card}
          testID="logout">
          로그아웃
        </Button>
      </ScrollView>

      {/* 열려 있을 때만 그린다. 닫았다 열면 새로 만들어져 입력칸이 지금 닉네임으로 돌아간다 */}
      {editing && member && (
        <NicknameDialog current={member.nickname} onDismiss={() => setEditing(false)} onChanged={onNicknameChanged} />
      )}

      <Snackbar
        key={notice?.seq ?? 0}
        visible={!!notice && notice.text !== ''}
        onDismiss={() => setNotice((current) => (current ? { seq: current.seq, text: '' } : null))}
        duration={2500}
        testID="my-notice">
        {notice?.text ?? ''}
      </Snackbar>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  body: { padding: 16, gap: 12 },
  card: { width: '100%', maxWidth: 640, alignSelf: 'center' },
  profile: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  points: { gap: 4 },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  grow: { flex: 1 },
  soon: { opacity: 0.5 },
});
