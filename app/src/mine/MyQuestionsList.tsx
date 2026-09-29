// 내 고민 목록 (GET /members/me/questions, docs/api.md 4.4) 과 삭제 (DELETE /questions/{id}, 4.5).
//
// 목록은 useInfiniteQuery 로 받는다. "쪽을 이어 받는 목록"을 캐시에 쌓아 주는 도구다.
// 피드는 카드 위치·투표 단계가 얽혀 있어 직접 만든 상태 기계를 썼지만,
// 여기는 받아서 보여 주기만 하므로 라이브러리가 주는 것으로 충분하다
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { FlatList, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Button, Dialog, IconButton, Portal, Snackbar, Text } from 'react-native-paper';

import { errorMessage } from '../api/errors';
import { deleteQuestion, getMyQuestions, type MyQuestion } from '../api/questions';
import { MyQuestionCard } from './MyQuestionCard';
import { flattenPages, isAlreadyDeleted, nextCursorOf, removeQuestion, type MyQuestionPages } from './myQuestions';
import { MY_QUESTIONS_KEY } from './queryKeys';

export function MyQuestionsList() {
  const queryClient = useQueryClient();
  const list = useInfiniteQuery({
    queryKey: MY_QUESTIONS_KEY,
    // pageParam 은 이번에 받을 쪽의 커서다. 첫 쪽은 undefined
    queryFn: ({ pageParam }) => getMyQuestions(pageParam),
    initialPageParam: undefined as string | undefined,
    // 방금 받은 쪽을 보고 다음 커서를 정한다. undefined 를 돌려주면 "더 없음"이다
    getNextPageParam: nextCursorOf,
  });

  // 삭제 확인 창에 올라와 있는 고민. 없으면 창이 닫혀 있다
  const [target, setTarget] = useState<MyQuestion | null>(null);
  const [deleting, setDeleting] = useState(false);
  // 잠깐 보여 주는 안내. seq 는 안내마다 하나씩 늘어나는 번호다.
  // 앞 안내가 떠 있을 때 새 안내가 오면 번호가 바뀌어 새로 뜬다. 번호가 없으면 앞 안내의 타이머가 새 안내까지 일찍 닫는다
  const [notice, setNotice] = useState<{ seq: number; text: string } | null>(null);
  // 시각 표시의 기준. 목록을 새로 받을 때마다 다시 읽는다
  const [now, setNow] = useState(() => Date.now());

  const items = flattenPages(list.data);
  // isRefetching 은 다음 쪽을 받을 때도 true 라서, 새로고침 표시에는 그 경우를 뺀다
  const refreshing = list.isRefetching && !list.isFetchingNextPage;

  function showNotice(text: string) {
    setNotice((current) => ({ seq: (current?.seq ?? 0) + 1, text }));
  }

  function dismissNotice() {
    // 번호는 남기고 글만 비운다. 번호까지 지우면 다음 안내가 다시 1번이 되어 새 안내로 구분되지 않는다
    setNotice((current) => (current ? { seq: current.seq, text: '' } : null));
  }

  function refresh() {
    setNow(Date.now());
    void list.refetch();
  }

  function loadMore() {
    // 목록이 짧으면 화면에 그려지자마자 "끝에 닿음"이 불린다. 받을 것이 있고 받는 중이 아닐 때만 요청한다
    if (list.hasNextPage && !list.isFetchingNextPage) {
      void list.fetchNextPage();
    }
  }

  async function confirmDelete() {
    if (!target || deleting) {
      return;
    }
    setDeleting(true);
    try {
      await deleteQuestion(target.id);
    } catch (error) {
      if (!isAlreadyDeleted(error)) {
        // 목록은 그대로 두고 알리기만 한다
        showNotice(errorMessage(error));
        setDeleting(false);
        setTarget(null);
        return;
      }
    }
    // 목록을 다시 받지 않고 캐시에서 그 고민만 뺀다. 보던 위치가 유지된다
    queryClient.setQueryData<MyQuestionPages>(MY_QUESTIONS_KEY, (data) => removeQuestion(data, target.id));
    showNotice('삭제했습니다.');
    setDeleting(false);
    setTarget(null);
  }

  if (list.isPending) {
    return (
      <View style={styles.center}>
        <ActivityIndicator testID="mine-loading" />
      </View>
    );
  }

  if (list.isError && items.length === 0) {
    return (
      <View style={styles.center}>
        <Text testID="mine-error">{errorMessage(list.error)}</Text>
        <Button mode="contained" onPress={refresh} testID="mine-retry">
          다시 시도
        </Button>
      </View>
    );
  }

  return (
    <View style={styles.screen}>
      <FlatList
        testID="mine-list"
        data={items}
        keyExtractor={(item) => String(item.id)}
        renderItem={({ item }) => <MyQuestionCard question={item} now={now} onDelete={setTarget} />}
        contentContainerStyle={styles.list}
        // 당겨서 새로고침. 웹 브라우저에는 이 동작이 없어서 위쪽에 새로고침 버튼을 함께 둔다
        onRefresh={refresh}
        refreshing={refreshing}
        // 끝에서 화면 절반 높이만큼 남았을 때 다음 쪽을 받기 시작한다
        onEndReached={loadMore}
        onEndReachedThreshold={0.5}
        ListHeaderComponent={
          <View style={styles.header}>
            <Text variant="labelLarge" testID="mine-count">
              {items.length}건{list.hasNextPage ? ' 이상' : ''}
            </Text>
            <IconButton
              icon="refresh"
              onPress={refresh}
              disabled={refreshing}
              accessibilityLabel="새로고침"
              testID="mine-refresh"
            />
          </View>
        }
        ListEmptyComponent={
          <Text style={styles.empty} testID="mine-empty">
            아직 올린 고민이 없어요
          </Text>
        }
        ListFooterComponent={
          list.isFetchingNextPage ? (
            <ActivityIndicator style={styles.footer} testID="mine-loading-more" />
          ) : list.isFetchNextPageError ? (
            <Button onPress={loadMore} testID="mine-more-retry">
              더 불러오지 못했어요. 다시 시도
            </Button>
          ) : null
        }
      />

      {/* Portal 은 안의 내용을 화면 맨 위 층에 그린다. 확인 창이 목록에 가려지지 않는다 */}
      <Portal>
        <Dialog visible={!!target} onDismiss={() => !deleting && setTarget(null)} testID="mine-delete-dialog">
          <Dialog.Title>고민을 삭제할까요?</Dialog.Title>
          <Dialog.Content>
            <Text variant="bodyMedium" numberOfLines={3}>
              {target?.content}
            </Text>
            <Text variant="bodySmall" style={styles.warning}>
              삭제하면 되돌릴 수 없고 피드에서도 사라집니다.
            </Text>
          </Dialog.Content>
          <Dialog.Actions>
            <Button onPress={() => setTarget(null)} disabled={deleting} testID="mine-delete-cancel">
              취소
            </Button>
            <Button onPress={confirmDelete} loading={deleting} disabled={deleting} testID="mine-delete-confirm">
              삭제
            </Button>
          </Dialog.Actions>
        </Dialog>
      </Portal>

      <Snackbar
        key={notice?.seq ?? 0}
        visible={!!notice && notice.text !== ''}
        onDismiss={dismissNotice}
        duration={2500}
        testID="mine-notice">
        {notice?.text ?? ''}
      </Snackbar>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 16, padding: 24 },
  list: { padding: 16, gap: 12 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    width: '100%',
    maxWidth: 640,
    alignSelf: 'center',
  },
  empty: { textAlign: 'center', paddingVertical: 48 },
  footer: { paddingVertical: 16 },
  warning: { marginTop: 12, opacity: 0.7 },
});
