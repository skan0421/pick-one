// 마이 탭 아래 목록들의 캐시 키 (Spring @Cacheable 의 key).
// 목록을 그리는 화면과, 그 목록을 낡게 만드는 화면(투표, 상단 노출)이 서로를 import 하지 않도록 키만 따로 둔다
export const POINT_LEDGER_KEY = ['points', 'ledger'];
export const MY_VOTES_KEY = ['my-votes'];
