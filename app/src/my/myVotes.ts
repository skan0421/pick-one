// 내가 투표한 고민 목록의 데이터 다루기. 순수 함수 (src/__tests__/myVotes.test.ts)
import type { OptionResponse } from '../api/questions';
import type { MyVoteItem } from '../api/votes';

// 화면이 그릴 선택지 한 줄: 선택지의 글·사진 + 득표 + 내가 고른 것인지
export type VotedOption = OptionResponse & {
  count: number;
  percent: number;
  mine: boolean;
};

// 서버는 선택지(question.options)와 득표(result.options)를 따로 준다. id 로 짝지어 한 줄로 만든다
// (SQL 의 LEFT JOIN 과 같다. 득표 쪽에 없는 선택지는 0표로 둔다)
export function votedOptions(item: MyVoteItem): VotedOption[] {
  const tallies = new Map(item.result.options.map((tally) => [tally.optionId, tally]));
  return [...item.question.options]
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((option) => {
      const tally = tallies.get(option.id);
      return {
        ...option,
        count: tally?.count ?? 0,
        percent: tally?.percent ?? 0,
        mine: option.id === item.myOptionId,
      };
    });
}
