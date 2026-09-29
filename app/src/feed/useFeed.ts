// 피드 진행자(FeedController)를 화면에 연결하는 훅.
// 화면은 이 훅이 주는 state 를 그리고, 사용자의 동작을 controller 에 전달하기만 한다
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, useSyncExternalStore } from 'react';

import type { PointBalance } from '../api/points';
import { getFeed } from '../api/questions';
import { getResults, vote } from '../api/votes';
import { MY_VOTES_KEY, POINT_LEDGER_KEY } from '../my/queryKeys';
import { FeedController } from './feedController';
import { currentCard } from './feedState';

// 포인트 잔액 캐시의 키 (Spring @Cacheable 의 key)
export const POINT_BALANCE_KEY = ['points', 'balance'];

export function useFeed() {
  const queryClient = useQueryClient();

  // useState 에 함수를 넘기면 화면이 처음 그려질 때 한 번만 실행된다.
  // 화면이 살아 있는 동안 같은 객체를 계속 쓴다 (화면 범위의 싱글턴 빈)
  const [controller] = useState(
    () =>
      new FeedController(
        { getFeed: (cursor) => getFeed(cursor), vote, getResults },
        {
          // 투표하면 잔액이 바뀐다. 캐시를 무효로 표시하면 잔액을 쓰는 화면이 알아서 다시 받아 온다 (@CacheEvict)
          onVoteSettled: () => {
            void queryClient.invalidateQueries({ queryKey: POINT_BALANCE_KEY });
            // 포인트 내역과 내가 투표한 고민에도 한 줄이 늘었다
            void queryClient.invalidateQueries({ queryKey: POINT_LEDGER_KEY });
            void queryClient.invalidateQueries({ queryKey: MY_VOTES_KEY });
          },
          dailyLimit: () => queryClient.getQueryData<PointBalance>(POINT_BALANCE_KEY)?.dailyEarnLimit,
        },
      ),
  );

  // React 밖에 있는 상태를 구독한다. controller 의 상태가 바뀌면 화면이 다시 그려진다
  const state = useSyncExternalStore(controller.subscribe, controller.getState, controller.getState);

  // 화면이 나타나면 첫 쪽을 받고, 사라지면 진행 중이던 요청의 응답을 버린다.
  // useEffect 가 돌려주는 함수는 화면이 사라질 때 실행된다 (@PostConstruct 와 @PreDestroy)
  useEffect(() => {
    controller.start();
    return () => controller.dispose();
  }, [controller]);

  return { state, card: currentCard(state), controller };
}
