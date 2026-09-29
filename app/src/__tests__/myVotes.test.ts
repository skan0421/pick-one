// 내가 투표한 고민: 선택지와 득표를 짝짓는 테스트
import type { MyVoteItem } from '../api/votes';
import { votedOptions } from '../my/myVotes';

function item(overrides: Partial<MyVoteItem> = {}): MyVoteItem {
  return {
    voteId: 9001,
    votedAt: '2026-09-29T17:55:00',
    question: {
      id: 42,
      questionType: 'TEXT',
      content: '소개팅 첫 만남, 카페 vs 밥집?',
      status: 'ACTIVE',
      boosted: false,
      options: [
        { id: 101, sortOrder: 1, content: '카페' },
        { id: 102, sortOrder: 2, content: '밥집' },
      ],
      author: { nickname: '고민많은사람' },
      createdAt: '2026-09-27T17:30:00',
    },
    myOptionId: 101,
    result: {
      totalVotes: 39,
      options: [
        { optionId: 101, count: 26, percent: 66.7 },
        { optionId: 102, count: 13, percent: 33.3 },
      ],
    },
    ...overrides,
  };
}

describe('votedOptions', () => {
  it('선택지에 득표를 붙이고 내가 고른 것을 표시한다', () => {
    expect(votedOptions(item())).toEqual([
      { id: 101, sortOrder: 1, content: '카페', count: 26, percent: 66.7, mine: true },
      { id: 102, sortOrder: 2, content: '밥집', count: 13, percent: 33.3, mine: false },
    ]);
  });

  it('득표의 순서가 선택지와 달라도 id 로 짝짓는다', () => {
    const shuffled = item({
      result: {
        totalVotes: 39,
        options: [
          { optionId: 102, count: 13, percent: 33.3 },
          { optionId: 101, count: 26, percent: 66.7 },
        ],
      },
    });

    expect(votedOptions(shuffled).map((option) => [option.content, option.count])).toEqual([
      ['카페', 26],
      ['밥집', 13],
    ]);
  });

  it('선택지는 sortOrder 순으로 놓는다', () => {
    const base = item();
    const reversed = item({ question: { ...base.question, options: [...base.question.options].reverse() } });

    expect(votedOptions(reversed).map((option) => option.sortOrder)).toEqual([1, 2]);
  });

  it('득표에 없는 선택지는 0표로 둔다', () => {
    const partial = item({ result: { totalVotes: 26, options: [{ optionId: 101, count: 26, percent: 100 }] } });

    expect(votedOptions(partial)[1]).toMatchObject({ id: 102, count: 0, percent: 0, mine: false });
  });

  it('내가 고른 선택지는 하나뿐이다', () => {
    const second = item({ myOptionId: 102 });

    expect(votedOptions(second).map((option) => option.mine)).toEqual([false, true]);
  });

  it('사진형은 imageUrl 을 그대로 넘긴다', () => {
    const base = item();
    const image = item({
      question: {
        ...base.question,
        questionType: 'IMAGE',
        options: [
          { id: 101, sortOrder: 1, imageUrl: 'http://storage/a.jpg' },
          { id: 102, sortOrder: 2, imageUrl: 'http://storage/b.jpg' },
        ],
      },
    });

    expect(votedOptions(image).map((option) => option.imageUrl)).toEqual(['http://storage/a.jpg', 'http://storage/b.jpg']);
  });

  it('원본을 고치지 않는다', () => {
    const base = item();
    const original = item({ question: { ...base.question, options: [...base.question.options].reverse() } });

    votedOptions(original);

    expect(original.question.options.map((option) => option.sortOrder)).toEqual([2, 1]);
  });
});
