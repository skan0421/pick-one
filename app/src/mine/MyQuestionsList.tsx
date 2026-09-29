// 내 고민 목록 (GET /members/me/questions, docs/api.md 4.4), 삭제 (DELETE /questions/{id}, 4.5),
// 상단 노출 (POST /questions/{id}/boosts, 6.3).
//
// 목록은 useInfiniteQuery 로 받는다. "쪽을 이어 받는 목록"을 캐시에 쌓아 주는 도구다.
// 피드는 카드 위치·투표 단계가 얽혀 있어 직접 만든 상태 기계를 썼지만,
// 여기는 받아서 보여 주기만 하므로 라이브러리가 주는 것으로 충분하다
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { FlatList, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Button, Dialog, IconButton, Portal, Snackbar, Text } from 'react-native-paper';

import { errorMessage } from '../api/errors';
import { boostQuestion, getBalance, type PointBalance } from '../api/points';
import { deleteQuestion, getMyQuestions, type MyQuestion } from '../api/questions';
import { markFeedStale } from '../feed/feedRefresh';
import { POINT_BALANCE_KEY } from '../feed/useFeed';
import { POINT_LEDGER_KEY } from '../my/queryKeys';
import { BoostDialog } from './BoostDialog';
import { previewBoost, submitBoost, type BoostApi } from './boostFlow';
import { BoostKeys } from './boostKeys';
import { MyQuestionCard } from './MyQuestionCard';
import {
  applyBoost,
  flattenPages,
  isAlreadyDeleted,
  nextCursorOf,
  removeQuestion,
  type MyQuestionPages,
} from './myQuestions';
import { MY_QUESTIONS_KEY } from './queryKeys';
import { newUuid } from './uuid';

const boostApi: BoostApi = { boost: boostQuestion };
// 상단 노출 요청의 키 저장소. 화면 밖(모듈)에 두어, 확인 창을 닫거나 다른 탭에 다녀와도 끝나지 않은 키가 남는다.
// 앱을 완전히 종료하면 사라진다 (앱 범위의 싱글턴 빈)
const boostKeys = new BoostKeys(newUuid);

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

  // 상단 노출 확인 창에 올라와 있는 고민. 없으면 창이 닫혀 있다
  const [boostTarget, setBoostTarget] = useState<MyQuestion | null>(null);
  const [boosting, setBoosting] = useState(false);
  const [boostError, setBoostError] = useState<string | undefined>(undefined);
  // 요청 잠금. boosting 은 화면이 다시 그려진 뒤에야 버튼을 막으므로, 그 사이에 한 번 더 눌리면 요청이 두 번 나간다.
  // useRef 의 값은 바꾸는 즉시 반영된다 (docs/troubleshooting.md 20)
  const boostLocked = useRef(false);
  // 확인 창에 보여 줄 잔액. 피드·마이 탭과 같은 캐시를 쓴다
  const balance = useQuery({ queryKey: POINT_BALANCE_KEY, queryFn: getBalance });
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

  function openBoost(question: MyQuestion) {
    setNow(Date.now());
    setBoostError(undefined);
    setBoostTarget(question);
    // 다른 기기에서 포인트를 썼을 수 있으므로 창을 열 때 잔액을 다시 받는다
    void balance.refetch();
  }

  async function confirmBoost() {
    if (!boostTarget || boostLocked.current) {
      return;
    }
    boostLocked.current = true;
    setBoosting(true);
    setBoostError(undefined);
    // 키를 정하고 보내는 일은 boostFlow 가 한다. 실패해서 다시 누르면 같은 키가 나간다
    const outcome = await submitBoost(boostApi, boostKeys, boostTarget.id);
    setBoosting(false);
    boostLocked.current = false;

    switch (outcome.kind) {
      case 'boosted': {
        const { response } = outcome;
        // 잔액: 응답에 든 값으로 바로 바꾸고, 오늘 적립 등 나머지는 서버에서 다시 받는다
        queryClient.setQueryData<PointBalance>(POINT_BALANCE_KEY, (data) =>
          data ? { ...data, balance: response.balanceAfter } : data,
        );
        void queryClient.invalidateQueries({ queryKey: POINT_BALANCE_KEY });
        void queryClient.invalidateQueries({ queryKey: POINT_LEDGER_KEY });
        // 내 고민 목록: 다시 받지 않고 그 고민의 끝나는 시각만 바꾼다. 보던 위치가 유지된다
        queryClient.setQueryData<MyQuestionPages>(MY_QUESTIONS_KEY, (data) =>
          applyBoost(data, response.questionId, response.boostedUntil),
        );
        markFeedStale();
        setNow(Date.now());
        setBoostTarget(null);
        showNotice(`상단 노출을 시작했어요. -${response.cost}P`);
        return;
      }
      case 'closed':
      case 'gone':
        // 목록이 낡았다. 창을 닫고 새로 받는다
        setBoostTarget(null);
        showNotice(outcome.message);
        refresh();
        return;
      case 'insufficient':
        // 창은 그대로 두고 잔액을 다시 받는다. 받은 잔액이 모자라면 확인 버튼이 막힌다
        setBoostError(outcome.message);
        void balance.refetch();
        return;
      default:
        // retry, keyConflict: 창을 그대로 두어 바로 다시 시도할 수 있게 한다
        setBoostError(outcome.message);
    }
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
        renderItem={({ item }) => <MyQuestionCard question={item} now={now} onDelete={setTarget} onBoost={openBoost} />}
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

      <BoostDialog
        question={boostTarget}
        preview={boostTarget ? previewBoost(boostTarget, balance.data?.balance, now) : null}
        now={now}
        busy={boosting}
        error={boostError}
        onConfirm={confirmBoost}
        onDismiss={() => setBoostTarget(null)}
      />

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
