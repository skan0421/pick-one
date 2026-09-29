// 내 고민 목록 캐시의 키 (Spring @Cacheable 의 key).
// 올리기 화면이 등록 후 이 캐시를 비우고, 내 고민 화면이 이 키로 목록을 받는다.
// 두 화면이 서로를 import 하지 않도록 키만 따로 둔다
export const MY_QUESTIONS_KEY = ['my-questions'];
