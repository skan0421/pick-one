// 피드 탭. 고민 카드를 한 장씩 보여 주고 투표를 받는다 (docs/api.md 4.2, 5.1, 5.2, 6.1).
//
// 이 파일은 조립만 한다. Spring 에 빗대면 컨트롤러 자리다.
//   상태와 규칙   src/feed/feedState.ts
//   서버와 엮기   src/feed/feedController.ts, useFeed.ts
//   카드 그리기   src/feed/QuestionCard.tsx
import { useQuery } from '@tanstack/react-query';
import { useFocusEffect } from 'expo-router';
import { useCallback, useRef } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Button, Snackbar, Text } from 'react-native-paper';

import { getBalance } from '../../api/points';
import { useAuth } from '../../auth/AuthContext';
import { feedVersion } from '../../feed/feedRefresh';
import { QuestionCard } from '../../feed/QuestionCard';
import { SwipeArea } from '../../feed/SwipeArea';
import { canSwipeToChoose, optionForSwipe, type SwipeAxis, type SwipeDirection } from '../../feed/swipe';
import { POINT_BALANCE_KEY, useFeed } from '../../feed/useFeed';

const NOTICE_DURATION_MS = 2500;

export default function FeedScreen() {
  const { member } = useAuth();
  // key 가 바뀌면 React 는 그 아래를 버리고 새로 만든다.
  // 다른 계정으로 바뀌었을 때 이전 계정이 보던 카드가 남지 않게 한다
  return <Feed key={member?.id ?? 0} />;
}

function Feed() {
  const { state, card, controller } = useFeed();
  const balance = useQuery({ queryKey: POINT_BALANCE_KEY, queryFn: getBalance });

  // 지금 스와이프 중인지. useRef 는 바뀌어도 화면을 다시 그리지 않는 값이다 (화면 객체의 필드).
  // 버튼 위에서 스와이프를 시작하면 손을 떼는 순간 그 버튼도 눌린 것으로 처리될 수 있어, 스와이프 중의 누름은 무시한다
  const swiping = useRef(false);

  // 이 탭이 다시 보일 때, 그 사이 다른 화면이 "피드를 새로 받아야 한다"고 표시했으면 새로 받는다 (feed/feedRefresh.ts)
  const seenVersion = useRef(feedVersion());
  useFocusEffect(
    useCallback(() => {
      if (seenVersion.current !== feedVersion()) {
        seenVersion.current = feedVersion();
        controller.refresh();
      }
    }, [controller]),
  );

  function choose(optionId: number) {
    if (!swiping.current) {
      void controller.choose(optionId);
    }
  }

  function onSwipe(direction: SwipeDirection) {
    if (!card) {
      return;
    }
    if (direction === 'up') {
      controller.next(); // 결과를 보는 중이 아니면 무시된다
      return;
    }
    const optionId = optionForSwipe(card, direction);
    if (optionId !== undefined) {
      void controller.choose(optionId);
    }
  }

  function refresh() {
    controller.refresh();
    void balance.refetch();
  }

  // 결과를 보는 중에는 위로, 선택지가 2개인 글형을 고르는 중에는 좌우로 밀 수 있다
  let axis: SwipeAxis = 'none';
  if (card && state.phase.kind === 'result') {
    axis = 'vertical';
  } else if (card && state.phase.kind === 'choosing' && canSwipeToChoose(card)) {
    axis = 'horizontal';
  }

  return (
    <View style={styles.screen}>
      <View style={styles.pointBar}>
        <Text variant="titleMedium" testID="point-balance">
          내 포인트 {balance.data ? `${balance.data.balance}P` : '-'}
        </Text>
        {balance.data && (
          <Text variant="labelMedium" testID="point-today">
            오늘 적립 {balance.data.todayEarned} / {balance.data.dailyEarnLimit}P
          </Text>
        )}
      </View>

      {card ? (
        <SwipeArea axis={axis} onSwipe={onSwipe} onActiveChange={(active) => (swiping.current = active)}>
          {/* 카드는 보통 한 화면에 들어온다. 작은 화면에서 넘칠 때만 스크롤된다 */}
          <ScrollView contentContainerStyle={styles.cardArea}>
            {/* key 를 카드 id 로 두어 카드가 바뀌면 새로 그린다. 앞 카드의 사진 오류 표시 같은 것이 남지 않는다 */}
            <QuestionCard
              key={card.id}
              card={card}
              phase={state.phase}
              onChoose={choose}
              onNext={() => controller.next()}
            />
          </ScrollView>
        </SwipeArea>
      ) : (
        <View style={styles.center}>
          {/* 보여 줄 카드가 없을 때: 받는 중 / 받기 실패 / 다 봄 */}
          {state.load === 'error' ? (
            <>
              <Text testID="feed-error">{state.loadError}</Text>
              <Button mode="contained" onPress={() => controller.retryLoad()} testID="feed-retry">
                다시 시도
              </Button>
            </>
          ) : state.load === 'idle' && !state.hasNext ? (
            <>
              <Text variant="titleMedium" testID="feed-empty">
                지금은 고를 고민이 없어요
              </Text>
              <Button mode="contained" onPress={refresh} testID="feed-refresh">
                새로고침
              </Button>
            </>
          ) : (
            <ActivityIndicator testID="feed-loading" />
          )}
        </View>
      )}

      {/* 잠깐 떴다 사라지는 안내. 포인트 적립, 넘어간 카드, 투표 실패를 알린다.
          기본 위치는 화면 아래인데 "다음" 버튼을 가리므로 위쪽에 띄운다 */}
      <Snackbar
        key={state.notice?.seq ?? 0}
        visible={!!state.notice}
        onDismiss={() => controller.dismissNotice()}
        duration={NOTICE_DURATION_MS}
        wrapperStyle={styles.noticeWrapper}
        testID="feed-notice">
        {state.notice?.text ?? ''}
      </Snackbar>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  pointBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    width: '100%',
    maxWidth: 640,
    alignSelf: 'center',
    paddingHorizontal: 16,
    paddingTop: 12,
  },
  // flexGrow: 내용이 화면보다 작으면 화면 높이만큼 늘어나 카드가 가운데 오고, 크면 내용만큼 길어져 스크롤된다
  cardArea: { flexGrow: 1, justifyContent: 'center', padding: 16 },
  noticeWrapper: { top: 0, bottom: 'auto' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 16, padding: 24 },
});
