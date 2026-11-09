// AWS_API_ONLY 하나의 요청률만 건다. 계정·캐시·쓰기 계약은 화면 실행기와 공유한다.
export { options, home, search_results, popular, mine, content_detail, content_search,
  recommend_like, stored_like, search_like, save_board, edit_board, initial_contents,
  teardown } from './aws-screens.js';
