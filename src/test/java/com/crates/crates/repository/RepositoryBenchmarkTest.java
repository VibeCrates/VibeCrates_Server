package com.crates.crates.repository;

import com.crates.crates.enumData.AuthProvider;
import com.crates.crates.enumData.Rating;
import com.crates.crates.entity.board.Board;
import com.crates.crates.enumData.Visibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리포지토리 메서드 하나하나의 <b>DB 비용</b>을 재는 하네스.
 *
 * <p>k6(run-measure.sh)와 역할이 다르다. k6는 동시성 아래에서 end-to-end를 재고, 어느 쿼리가
 * 비싼지는 pg_stat_statements 상위권으로 <i>간접</i> 추적한다. 그 방식에는 구멍이 둘 있다.
 * 첫째, API 경로에 걸리지 않는 리포지토리 메서드는 통계에 행조차 생기지 않아 측정 대상에서
 * 통째로 빠진다. 둘째, psql에 SQL을 손으로 옮겨 적어 보완하면 Hibernate가 실제로 내보내는
 * SQL과 달라진다(enum을 리터럴로 쓰면 인덱스를 타지만 Hibernate는 바인드 파라미터로 보낸다 —
 * 보드 인덱스(V3)를 만들며 반복해서 물린 지점이다).</p>
 *
 * <p>여기서는 리포지토리 메서드를 <b>직접</b> 호출한다. 경로가 없어도 되고, 나가는 SQL은
 * 운영에서 나가는 것과 같다. 대신 단일 스레드라 커넥션 경합·락·비동기 재계산은 못 본다.</p>
 *
 * <h2>데이터 준비</h2>
 * <pre>
 *   psql ... &lt; load-test/cleanup.sql          # 이전 실행의 흔적 + 공간 회수
 *   ./load-test/seed.sh 3 1 0.6 1.0 1         # 6개월 누적: 하루 좋아요 / 하루 보드 수정 / 인기 보드 비율 / 지프 s / 하루 검색 좋아요
 *   VACUUM ANALYZE
 *   ./gradlew test --tests '*RepositoryBenchmarkTest' --rerun-tasks
 * </pre>
 * 준비 없이 돌리면 픽스처 단계에서 실패한다. <b>0행을 반환하는 쿼리를 재고 "빠르다"고
 * 결론 내리는 것</b>이 이 프로젝트에서 가장 자주 저지른 실수다.
 *
 * <h2>측정 방식</h2>
 * <p>{@code pg_stat_statements}에는 퍼센타일 컬럼이 없다(calls·total·min·max·mean·stddev뿐).
 * 그래서 <b>호출 한 번마다 {@code total_exec_time} 증분을 떠서</b> 표본을 모으고 p50/p95/p99를
 * 직접 계산한다. 이 값은 JDBC 왕복과 JPA 매핑을 제외한 <b>순수 DB 실행 시간</b>이다.
 * 그 둘을 보려고 Java wall-clock도 같이 잰다 — DB는 빠른데 wall-clock이 느리면 원인은
 * 엔티티 하이드레이션이나 N+1이다.</p>
 *
 * <p>쓰기 메서드는 트랜잭션이 롤백되므로 안전하다. 다만 같은 행을 200번 지우면 두 번째부터
 * 0행이 되므로, 반복마다 다른 대상을 쓴다.</p>
 *
 * <p>시더가 만들지 않는 데이터(리프레시 토큰, OAuth 계정)는 트랜잭션 안에서
 * 만들고 {@code ANALYZE}까지 돌린다. 통계를 갱신하지 않으면 플래너가 빈 테이블을 가정해
 * 엉뚱한 계획을 고른다. 이 행들도 롤백과 함께 사라진다.</p>
 */
@DataJpaTest(showSql = false)   // 로그 포매팅 비용이 측정 구간에 섞이지 않도록
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("benchmark")    // ddl-auto=none 을 고정한다. 빼면 개발 DB가 drop/create 된다.
class RepositoryBenchmarkTest {

    /**
     * 버리는 호출 횟수. 5를 넉넉히 넘겨야 한다 — pgjdbc의 {@code prepareThreshold} 기본값이 5라,
     * 5회째부터 서버사이드 prepared statement로 넘어가고 그때부터 PostgreSQL이 제네릭 플랜을
     * 고려하기 시작한다. 5회만 돌리면 우리가 계속 물렸던 그 영역에 닿기 직전에 멈춘다.
     */
    private static final int WARMUP = 30;

    /** 측정 표본 수. p99가 의미를 가지려면 최소 100은 필요하다. */
    private static final int ITERATIONS = 200;

    /** 전체 요약표를 위해 모은다. 테스트 순서와 무관하게 마지막에 한 번 정렬해 출력한다. */
    private static final List<Result> RESULTS = new ArrayList<>();

    private record Result(String method, int rows, double blocks,
                          double dbP50, double dbP95, double dbP99,
                          double javaP50, double javaP95, double javaP99, String note) {}

    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    @Autowired private BoardFeedbackRepository boardFeedbackRepository;
    @Autowired private BoardRepository boardRepository;
    @Autowired private BoardItemRepository boardItemRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private BookRepository bookRepository;
    @Autowired private MusicRepository musicRepository;
    @Autowired private OnboardingContentRepository onboardingContentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserVectorRepository userVectorRepository;
    @Autowired private UserRefreshTokenRepository userRefreshTokenRepository;

    // ── BoardFeedbackRepository ─────────────────────────────────────────────

    @Test
    @DisplayName("[성능] BoardFeedbackRepository")
    void benchmarkBoardFeedbackRepository() {
        Long userId = userWithMostLikes();
        List<Long> liked = likedBoardIds(userId, 300);
        // 계정당 좋아요가 20건 미만인 데이터셋에서도 돌아야 한다.
        List<Long> page = liked.subList(0, Math.min(20, liked.size()));

        benchmark("BoardFeedbackRepository.existsByBoardIdAndUserIdAndRating", i ->
                boardFeedbackRepository.existsByBoardIdAndUserIdAndRating(
                        liked.get(i % liked.size()), userId, Rating.LIKE) ? 1 : 0);

        benchmark("BoardFeedbackRepository.existsByUserIdAndRating", i ->
                boardFeedbackRepository.existsByUserIdAndRating(userId, Rating.LIKE) ? 1 : 0);

        benchmark("BoardFeedbackRepository.findLikedBoardsWithTime  (취향 벡터 재계산)", i ->
                boardFeedbackRepository.findLikedBoardsWithTime(userId, Rating.LIKE).size());

        benchmark("BoardFeedbackRepository.findLikedBoards  (보관함 좋아요 탭, 20건)", i ->
                boardFeedbackRepository.findLikedBoards(userId, Rating.LIKE, PageRequest.of(0, 20)).size());

        benchmark("BoardFeedbackRepository.findLikedBoardIds  (보드 20개 대조)", i ->
                boardFeedbackRepository.findLikedBoardIds(userId, Rating.LIKE, page).size());

        // 반복마다 다른 행을 지운다. 같은 행을 200번 지우면 두 번째부터 0행이라 측정이 안 된다.
        // 한 계정의 좋아요만으로는 231건(워밍업+표본)을 못 채울 수 있어 계정을 가로질러 모은다.
        List<long[]> pairs = feedbackPairs(WARMUP + 1 + ITERATIONS);
        benchmark("BoardFeedbackRepository.deleteFeedback  (쓰기·롤백)", i -> {
            long[] pair = pairs.get(i % pairs.size());
            return boardFeedbackRepository.deleteFeedback(pair[0], pair[1], Rating.LIKE);
        });
    }

    // ── BoardRepository ─────────────────────────────────────────────────────

    @Test
    @DisplayName("[성능] BoardRepository")
    void benchmarkBoardRepository() {
        Long userId = userWithMostLikes();
        List<Long> boards = likedBoardIds(userId, 300);
        String aiSignature = jdbcTemplate.queryForObject(
                "SELECT content_signature FROM board WHERE board_type = 'AI_RECOMMEND' "
                        + "AND deleted_at IS NULL ORDER BY id LIMIT 1", String.class);

        benchmark("BoardRepository.findPopularBoards  (인기 보드, 10건)", i ->
                boardRepository.findPopularBoards(10).size());

        benchmark("BoardRepository.findActiveAiBoardBySignature", i ->
                boardRepository.findActiveAiBoardBySignature(aiSignature).isPresent() ? 1 : 0);

        // 측정 계정이 실제로 만든 보드의 signature. 시더가 가입 시기만큼 사용자 보드를 만들어 둔다.
        Map<String, Object> customBoard = jdbcTemplate.queryForMap(
                "SELECT content_signature, title FROM board WHERE user_id = ? AND board_type = 'USER_CUSTOM' "
                        + "AND deleted_at IS NULL ORDER BY id LIMIT 1", userId);
        String customSignature = (String) customBoard.get("content_signature");
        String customTitle = (String) customBoard.get("title");

        benchmark("BoardRepository.findLikeCountById", i ->
                boardRepository.findLikeCountById(boards.get(i % boards.size())).isPresent() ? 1 : 0);

        benchmark("BoardRepository.incrementLikeCount  (쓰기·롤백)", i ->
                boardRepository.incrementLikeCount(boards.get(i % boards.size())));

        benchmark("BoardRepository.decrementLikeCount  (쓰기·롤백)", i ->
                boardRepository.decrementLikeCount(boards.get(i % boards.size())));

        benchmark("BoardRepository.findCreatedByMe  (보관함 만든 것 탭, 20건)", i ->
                boardRepository.findCreatedByMe(userId, PageRequest.of(0, 20)).size());

        benchmark("BoardRepository.findActiveUserBoard", i ->
                boardRepository.findActiveUserBoard(userId, customSignature, customTitle).isPresent() ? 1 : 0);

        benchmark("BoardRepository.findByIdForUpdate  (보드 수정·삭제, 잠금)", i ->
                boardRepository.findByIdForUpdate(boards.get(i % boards.size())).isPresent() ? 1 : 0);
    }

    /**
     * 추천·검색 보드의 첫 저장(INSERT ... ON CONFLICT DO NOTHING RETURNING id)과 검색 보드 조회.
     *
     * <p>검색 보드 조회는 시더가 만든 검색 보드(하루 검색 좋아요 1번, 6개월 114,000개)를 찾는다.</p>
     *
     * <p>저장은 반복마다 새 구성을 쓴다. 같은 구성을 다시 넣으면 충돌로 0행이 된다 — 그 경우(동시 저장에서 진 쪽)는
     * 따로 잰다.</p>
     */
    @Test
    @DisplayName("[성능] 추천·검색 보드 저장, 검색 보드 조회")
    void benchmarkGeneratedBoardInsert() {
        String existingAiSignature = jdbcTemplate.queryForObject(
                "SELECT content_signature FROM board WHERE board_type = 'AI_RECOMMEND' "
                        + "AND deleted_at IS NULL ORDER BY id LIMIT 1", String.class);

        benchmark("BoardRepository.insertAiBoard  (새 AI 보드 저장·쓰기·롤백)", i ->
                boardRepository.insertAiBoard("bench-ai-" + i, "bench-ai-sig-" + i).isPresent() ? 1 : 0, "픽스처");

        benchmark("BoardRepository.insertAiBoard  (이미 있는 구성 — 충돌, 0행)", i ->
                boardRepository.insertAiBoard("bench-ai-dup", existingAiSignature).isPresent() ? 1 : 0, "0 허용");

        benchmark("BoardRepository.insertSearchBoard  (새 검색 보드 저장·쓰기·롤백)", i ->
                boardRepository.insertSearchBoard("bench-search-" + i, "bench-search-sig-" + i).isPresent() ? 1 : 0, "픽스처");

        List<String[]> searchBoards = jdbcTemplate.query(
                "SELECT title, content_signature FROM board WHERE board_type = 'SEARCH_RECOMMEND' "
                        + "AND title LIKE 'loadtest-search-%' ORDER BY id LIMIT 300",
                (rs, n) -> new String[]{rs.getString("title"), rs.getString("content_signature")});
        assertThat(searchBoards).as("시딩한 검색 보드가 없습니다 — seed.sh의 하루 검색 좋아요를 확인하세요").isNotEmpty();

        benchmark("BoardRepository.findSearchBoard  (저장된 검색 보드 찾기)", i -> {
            String[] board = searchBoards.get(i % searchBoards.size());
            return boardRepository.findSearchBoard(board[0], board[1]).isPresent() ? 1 : 0;
        });

        // 같은 구성을 다른 검색어로 찾는다. 검색할 때마다 부르는데 대부분은 아직 저장되지 않은 검색어다.
        benchmark("BoardRepository.findSearchBoard  (저장 전 검색어 — 없음)", i -> {
            String[] board = searchBoards.get(i % searchBoards.size());
            return boardRepository.findSearchBoard("bench-search-none-" + i, board[1]).isPresent() ? 1 : 0;
        }, "0 허용");
    }

    /**
     * 보드 삭제 — 주인의 소프트 삭제, 좋아요가 0이 된 보드의 완전 삭제(콘텐츠 먼저, 그다음 보드).
     *
     * <p>완전 삭제는 좋아요가 0인 AI 보드가 대상이다. 시딩한 AI 보드는 만든 사람의 좋아요가 있어 1 이상이므로,
     * 그 좋아요만 받은 보드 231개의 좋아요를 트랜잭션 안에서 지워 "마지막 좋아요를 취소한 직후" 상태를 만든다.
     * 운영과 같은 순서로 콘텐츠를 먼저 지우고(외래 키에 CASCADE가 없다) 같은 보드를 지운다.</p>
     */
    @Test
    @DisplayName("[성능] 보드 삭제")
    void benchmarkBoardDelete() {
        int n = WARMUP + 1 + ITERATIONS;
        List<Long> owned = jdbcTemplate.queryForList(
                "SELECT id FROM board WHERE board_type = 'USER_CUSTOM' AND deleted_at IS NULL ORDER BY id LIMIT ?",
                Long.class, n);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        benchmark("BoardRepository.softDelete  (주인 삭제·쓰기·롤백)", i ->
                boardRepository.softDelete(owned.get(i % owned.size()), now));

        List<Long> unliked = jdbcTemplate.queryForList(
                "SELECT id FROM board WHERE board_type = 'AI_RECOMMEND' AND like_count = 1 ORDER BY id LIMIT ?",
                Long.class, n);
        assertThat(unliked).as("좋아요가 1개인 AI 보드가 %d개 필요합니다", n).hasSize(n);
        String ids = unliked.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbcTemplate.update("DELETE FROM board_feedback WHERE board_id IN (" + ids + ")");
        jdbcTemplate.update("UPDATE board SET like_count = 0 WHERE id IN (" + ids + ")");

        benchmark("BoardItemRepository.deleteItemsIfUnliked  (완전 삭제 ① 콘텐츠·쓰기·롤백)", i ->
                boardItemRepository.deleteItemsIfUnliked(unliked.get(i)));

        benchmark("BoardRepository.deleteIfUnliked  (완전 삭제 ② 보드·쓰기·롤백)", i ->
                boardRepository.deleteIfUnliked(unliked.get(i)));
    }

    /**
     * 보관함 "전체" 탭 — 재작성 전후를 같은 실행 안에서 나란히 잰다.
     *
     * <p>before/after를 따로 돌리면 뒤에 도는 쪽이 따뜻한 캐시의 덕을 본다. 같은 트랜잭션에서
     * 두 쿼리를 모두 부르면 그 차이가 원천적으로 없다.</p>
     *
     * <p>기존 쿼리는 운영 코드에서 지웠으므로 여기서 네이티브로 되살린다. 비교를 재현할 수
     * 있게 하려고 남기는 것이지, 되돌리려는 것이 아니다.</p>
     *
     * <p>측정 계정은 시더가 만든 그대로 쓴다 — 가장 오래 쓴 사용자(6개월 시나리오에서 좋아요 720 /
     * 만든 보드 180)다. 예전에는 "내가 만든 보드"를 2,000·20,000개 트랜잭션 안에서 몰아 넣고 롤백했는데,
     * 그 규모는 실제 가정에서 나온 값이 아니었고 롤백 뒤에도 board 인덱스에 빈 페이지가 남았다.</p>
     *
     * <p>11페이지(OFFSET 200)는 좋아요와 만든 보드를 합쳐 220건이 넘을 때만 의미가 있다.</p>
     */
    @Test
    @DisplayName("[성능] 보관함 전체 탭 — 측정 계정")
    void benchmarkMyBoards() {
        Long userId = userWithMostLikes();
        String label = " (좋아요 " + countLikes(userId) + " / 만든 보드 " + countOwned(userId) + ")";

        benchmark("findMyBoards 기존 JPQL  1페이지" + label, i ->
                legacyMyBoards(userId, 20, 0).size());

        benchmark("findMyBoards UNION ALL  1페이지" + label, i ->
                boardRepository.findMyBoards(userId, Rating.LIKE.name(), 20, 0, 20).size());

        benchmark("findMyBoards 기존 JPQL  11페이지" + label, i ->
                legacyMyBoards(userId, 20, 200).size());

        benchmark("findMyBoards UNION ALL  11페이지" + label, i ->
                boardRepository.findMyBoards(userId, Rating.LIKE.name(), 20, 200, 220).size());
    }

    /** 재작성 전 JPQL이 Hibernate를 거쳐 나가던 SQL 그대로. */
    @SuppressWarnings("unchecked")
    private List<Board> legacyMyBoards(Long userId, int size, int offset) {
        return entityManager.createNativeQuery("""
                select b1_0.* from board b1_0
                 left join users u1_0 on u1_0.id = b1_0.user_id
                 left join board_feedback bf1_0
                        on bf1_0.board_id = b1_0.id and bf1_0.user_id = ?1 and bf1_0.rating = ?2
                 where b1_0.deleted_at is null and (u1_0.id = ?1 or bf1_0.id is not null)
                 order by coalesce(bf1_0.created_at, b1_0.created_at) desc, b1_0.id desc
                 fetch first ?3 rows only offset ?4 rows
                """, Board.class)
                .setParameter(1, userId)
                .setParameter(2, Rating.LIKE.name())
                .setParameter(3, size)
                .setParameter(4, offset)
                .getResultList();
    }

    // ── BoardItemRepository ─────────────────────────────────────────────────

    @Test
    @DisplayName("[성능] BoardItemRepository")
    void benchmarkBoardItemRepository() {
        Long userId = userWithMostLikes();
        List<Long> boards = likedBoardIds(userId, 300);
        List<Long> page = boards.subList(0, Math.min(20, boards.size()));

        benchmark("BoardItemRepository.findContentIdsByBoardId", i ->
                boardItemRepository.findContentIdsByBoardId(boards.get(i % boards.size())).size());

        benchmark("BoardItemRepository.findContentIdsByBoardIds  (보드 20개)", i ->
                boardItemRepository.findContentIdsByBoardIds(page).size());
    }

    // ── ContentRepository ───────────────────────────────────────────────────

    @Test
    @DisplayName("[성능] ContentRepository")
    void benchmarkContentRepository() {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM content ORDER BY id LIMIT 256", Long.class);
        String keyword = jdbcTemplate.queryForObject(
                "SELECT left(title, 6) FROM content WHERE length(title) >= 6 ORDER BY id LIMIT 1", String.class);

        benchmark("ContentRepository.findContentIdsAfter  (커서 페이징, 256건)", i ->
                contentRepository.findContentIdsAfter(0L, PageRequest.of(0, 256)).size());

        benchmark("ContentRepository.findContentsByIds  (256건)", i ->
                contentRepository.findContentsByIds(ids).size());

        benchmark("ContentRepository.findContentSummaryById", i ->
                contentRepository.findContentSummaryById(ids.get(i % ids.size())).isPresent() ? 1 : 0);

        benchmark("ContentRepository.searchContentIdsByTitle  (pg_trgm, 20건)", i ->
                contentRepository.searchContentIdsByTitle(keyword, 20, 0).size());

        // 벡터 CSV 적재가 batch-size(기본 256)줄마다 부른다. 기동 때만 돈다.
        List<String> bookKeys = jdbcTemplate.queryForList(
                "SELECT source_key FROM content WHERE dtype = 'BOOK' ORDER BY id LIMIT 256", String.class);
        benchmark("ContentRepository.findIdsBySourceKeys  (벡터 적재, 256건)", i ->
                contentRepository.findIdsBySourceKeys("BOOK", bookKeys).size());
    }

    // ── OnboardingContentRepository ─────────────────────────────────────────

    @Test
    @DisplayName("[성능] OnboardingContentRepository")
    void benchmarkOnboardingContentRepository() {
        // 가입 화면이 탭 하나를 열 때 부르는 쿼리. 종류마다 행 수가 같아(200/200/195) 한 종류로 잰다.
        // 선택 검증은 최대 선택 수(10개)로 잰다. 인기 상위부터 고르는 것이 흔한 경우라 순위 앞쪽을 쓴다.
        List<Long> picked = jdbcTemplate.queryForList(
                "SELECT content_id FROM onboarding_content ORDER BY popularity_rank, content_id LIMIT 10", Long.class);
        assertThat(picked).as("onboarding_content가 비어 있다 — V9 시딩을 확인할 것").hasSize(10);

        benchmark("OnboardingContentRepository.findContentIdsByDtype  (가입 후보 탭, 200건)", i ->
                onboardingContentRepository.findContentIdsByDtype("BOOK").size());

        benchmark("OnboardingContentRepository.countByContentIdIn  (선택 검증, 10건)", i ->
                (int) onboardingContentRepository.countByContentIdIn(picked));
    }

    // ── Movie / Book / Music ────────────────────────────────────────────────

    @Test
    @DisplayName("[성능] Movie / Book / Music 상세")
    void benchmarkContentDetailRepositories() {
        // JOINED 상속이라 자식 테이블의 PK는 id가 아니라 content_id다.
        List<Long> movies = jdbcTemplate.queryForList(
                "SELECT content_id FROM movie ORDER BY content_id LIMIT 200", Long.class);
        List<Long> books = jdbcTemplate.queryForList(
                "SELECT content_id FROM book ORDER BY content_id LIMIT 200", Long.class);
        List<Long> musics = jdbcTemplate.queryForList(
                "SELECT content_id FROM music ORDER BY content_id LIMIT 200", Long.class);

        if (!movies.isEmpty()) {
            benchmark("MovieRepository.findMovieDetailById", i ->
                    movieRepository.findMovieDetailById(movies.get(i % movies.size())).isPresent() ? 1 : 0);
        }
        if (!books.isEmpty()) {
            benchmark("BookRepository.findBookDetailById", i ->
                    bookRepository.findBookDetailById(books.get(i % books.size())).isPresent() ? 1 : 0);
        }
        if (!musics.isEmpty()) {
            benchmark("MusicRepository.findMusicDetailById", i ->
                    musicRepository.findMusicDetailById(musics.get(i % musics.size())).isPresent() ? 1 : 0);
        }
    }

    // ── UserRepository / UserVectorRepository ───────────────────────────────

    @Test
    @DisplayName("[성능] UserRepository, UserVectorRepository")
    void benchmarkUserRepositories() {
        Long userId = userWithMostLikes();
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT login_id, email, nickname FROM users WHERE id = ?", userId);
        String loginId = (String) user.get("login_id");
        String email = (String) user.get("email");
        String nickname = (String) user.get("nickname");

        benchmark("UserRepository.findByIdAndDeletedAtIsNull", i ->
                userRepository.findByIdAndDeletedAtIsNull(userId).isPresent() ? 1 : 0);

        benchmark("UserRepository.findByLoginId  (로그인)", i ->
                userRepository.findByLoginId(loginId).isPresent() ? 1 : 0);

        benchmark("UserRepository.findByEmail", i ->
                userRepository.findByEmail(email).isPresent() ? 1 : 0);

        benchmark("UserRepository.existsByLoginId  (가입 중복 확인)", i ->
                userRepository.existsByLoginId(loginId) ? 1 : 0);

        benchmark("UserRepository.existsByEmail", i ->
                userRepository.existsByEmail(email) ? 1 : 0);

        benchmark("UserRepository.existsByNickname", i ->
                userRepository.existsByNickname(nickname) ? 1 : 0);

        String providerId = seedOauthUser();
        benchmark("UserRepository.findByProviderAndProviderId  (소셜 로그인)", i ->
                userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, providerId).isPresent() ? 1 : 0, "픽스처");
    }

    /**
     * 백필이 고칠 대상을 찾는 쿼리.
     *
     * <p>이 쿼리 하나가 백필 대상 조회와 {@code stale.users} 게이지를 겸한다.</p>
     *
     * <p>세 상태를 나눠 잰다. <b>정상(0명)이 가장 중요하다</b> — 10분마다 도는 것은 그 경우이고,
     * "조건에 맞는 행이 없다"를 증명하려면 어떤 LIMIT 을 둬도 전부 봐야 하기 때문이다
     * (같은 조건에 LIMIT 상한을 씌워봤지만 통하지 않았다 — 블록이 926에서 30,095로 늘었다).</p>
     *
     * <p>전원 밀림은 최악의 경우다. {@code ORDER BY uv.updatedAt} 을 받쳐줄 인덱스가 없어
     * 정렬 입력이 밀린 사용자 수만큼 커지는지 확인한다.</p>
     */
    @Test
    @DisplayName("[성능] UserVectorRepository.findStaleUserIds — 백필 대상 조회")
    void benchmarkFindStaleUserIds() {
        int users = jdbcTemplate.queryForObject("SELECT count(*) FROM user_vector", Integer.class);

        freshenAllVectors();
        benchmark("findStaleUserIds  (밀린 사용자 0명 — 10분마다 도는 정상 경우)", i ->
                staleUserIds(100).size(), "0 허용");

        makeStale(100);
        benchmark("findStaleUserIds  (밀린 사용자 100명 = LIMIT 과 같음)", i ->
                staleUserIds(100).size());

        makeStale(users);
        benchmark("findStaleUserIds  (전원 밀림 " + users + "명 — 최악)", i ->
                staleUserIds(100).size());
    }

    /** 취향 벡터 저장. 좋아요마다 도는 재계산의 마지막 단계다. 반복마다 다른 사용자를 쓴다. */
    @Test
    @DisplayName("[성능] UserVectorRepository.updateVector")
    void benchmarkUpdateVector() {
        List<Long> userIds = jdbcTemplate.queryForList(
                "SELECT user_id FROM user_vector ORDER BY user_id LIMIT ?", Long.class, WARMUP + 1 + ITERATIONS);
        float[] vector = new float[768];
        Arrays.fill(vector, 0.036f);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        benchmark("UserVectorRepository.updateVector  (재계산 저장·쓰기·롤백)", i ->
                userVectorRepository.updateVector(userIds.get(i % userIds.size()), vector, now));
    }

    private List<Long> staleUserIds(int limit) {
        return userVectorRepository.findStaleUserIds(
                Rating.LIKE, java.time.LocalDateTime.now().minusMinutes(5), PageRequest.of(0, limit));
    }

    /** 전원을 최신 상태로. 밀린 사용자 0명이라는 출발점을 만든다. */
    private void freshenAllVectors() {
        jdbcTemplate.update("UPDATE user_vector SET updated_at = now()");
        jdbcTemplate.execute("ANALYZE user_vector");
    }

    /** 좋아요가 있는 사용자 n명의 벡터를 과거로 돌려 밀린 상태로 만든다. */
    private void makeStale(int count) {
        jdbcTemplate.update("""
                UPDATE user_vector SET updated_at = now() - interval '10 days'
                 WHERE user_id IN (SELECT DISTINCT user_id FROM board_feedback
                                    WHERE rating = 'LIKE' ORDER BY user_id LIMIT ?)
                """, count);
        jdbcTemplate.execute("ANALYZE user_vector");
    }

    // ── UserRefreshTokenRepository ──────────────────────────────────────────

    @Test
    @DisplayName("[성능] UserRefreshTokenRepository")
    void benchmarkUserRefreshTokenRepository() {
        List<Long> userIds = seedRefreshTokens(5);
        List<String> jtis = jdbcTemplate.queryForList(
                "SELECT jti FROM user_refresh_tokens ORDER BY id LIMIT 400", String.class);

        benchmark("UserRefreshTokenRepository.findByUserIdOrderByExpiresAtAsc", i ->
                userRefreshTokenRepository.findByUserIdOrderByExpiresAtAsc(
                        userIds.get(i % userIds.size())).size(), "픽스처");

        benchmark("UserRefreshTokenRepository.findByJti  (토큰 재발급)", i ->
                userRefreshTokenRepository.findByJti(jtis.get(i % jtis.size())).isPresent() ? 1 : 0, "픽스처");

        // 쓰기 셋은 반복마다 다른 대상을 쓴다.
        benchmark("UserRefreshTokenRepository.deleteByJti  (쓰기·롤백)", i -> {
            userRefreshTokenRepository.deleteByJti(jtis.get(i % jtis.size()));
            return 1;
        }, "픽스처");

        benchmark("UserRefreshTokenRepository.deleteByUserId  (로그아웃·쓰기·롤백)", i -> {
            userRefreshTokenRepository.deleteByUserId(userIds.get(i % userIds.size()));
            return 1;
        }, "픽스처");

        benchmark("UserRefreshTokenRepository.deleteByExpiresAtBefore  (스케줄러·쓰기·롤백)", i -> {
            userRefreshTokenRepository.deleteByExpiresAtBefore(java.time.LocalDateTime.now().minusYears(10));
            return 1;
        }, "픽스처·0행");
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    /**
     * 측정 계정 — 좋아요를 가장 많이 가진 부하테스트 계정. 6개월 시나리오에서는 가장 오래 쓴
     * 1개월차 가입자다(좋아요 720 / 만든 보드 180, 검색 보드가 없던 이전 환경에서는 540 / 180). 하드코딩한 id를 쓰면 안 된다 —
     * seed.sh는 매번 새 계정을 만들고 cleanup.sql이 지우므로 id가 고정되지 않는다.
     */
    private Long userWithMostLikes() {
        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT f.user_id
                  FROM board_feedback f
                  JOIN users u ON u.id = f.user_id
                 WHERE u.login_id LIKE 'loadtest_u%' AND f.rating = 'LIKE'
                 GROUP BY f.user_id
                 ORDER BY count(*) DESC, f.user_id
                 LIMIT 1
                """, Long.class);

        assertThat(ids)
                .as("좋아요를 가진 loadtest 계정이 없습니다. "
                        + "먼저 ./load-test/seed.sh 를 실행하세요")
                .isNotEmpty();
        return ids.get(0);
    }

    /** 지울 (board_id, user_id) 쌍. 반복마다 다른 행을 지우기 위해 계정을 가로질러 모은다. */
    private List<long[]> feedbackPairs(int limit) {
        List<long[]> pairs = jdbcTemplate.query(
                "SELECT board_id, user_id FROM board_feedback WHERE rating = 'LIKE' "
                        + "ORDER BY board_id, user_id LIMIT ?",
                (rs, n) -> new long[]{rs.getLong("board_id"), rs.getLong("user_id")}, limit);
        assertThat(pairs)
                .as("지울 좋아요가 %d건 필요한데 %d건뿐입니다 — seed.sh 규모를 키우세요", limit, pairs.size())
                .hasSizeGreaterThanOrEqualTo(limit);
        return pairs;
    }

    private List<Long> likedBoardIds(Long userId, int limit) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT board_id FROM board_feedback WHERE user_id = ? AND rating = 'LIKE' "
                        + "ORDER BY board_id LIMIT ?", Long.class, userId, limit);
        assertThat(ids).as("좋아요한 보드가 없습니다").isNotEmpty();
        return ids;
    }

    private int countLikes(Long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM board_feedback WHERE user_id = ? AND rating = 'LIKE'", Integer.class, userId);
        return n == null ? 0 : n;
    }

    private int countOwned(Long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM board WHERE user_id = ? AND board_type = 'USER_CUSTOM' AND deleted_at IS NULL",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    private List<Long> seedRefreshTokens(int perUser) {
        jdbcTemplate.update("""
                INSERT INTO user_refresh_tokens (user_id, jti, expires_at)
                SELECT u.id, 'bench-' || u.id || '-' || g, now() + (g || ' days')::interval
                  FROM users u CROSS JOIN generate_series(1, ?) g
                 WHERE u.login_id LIKE 'loadtest_u%'
                """, perUser);
        jdbcTemplate.execute("ANALYZE user_refresh_tokens");
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT user_id FROM user_refresh_tokens ORDER BY user_id LIMIT 400", Long.class);
    }

    private String seedOauthUser() {
        String providerId = "bench-google-1";
        jdbcTemplate.update("""
                INSERT INTO users (login_id, email, nickname, role, gender, login_type, provider, provider_id, created_at)
                VALUES (NULL, 'bench-oauth@example.com', 'bench-oauth', 'USER', 'OTHER', 'OAUTH', 'GOOGLE', ?, now())
                ON CONFLICT (provider, provider_id) DO NOTHING
                """, providerId);
        jdbcTemplate.execute("ANALYZE users");
        return providerId;
    }

    // ── 하네스 ──────────────────────────────────────────────────────────────

    /** 한 스냅샷: [호출 수, DB 실행 시간(ms), 읽은 블록 수]. */
    private static final int CALLS = 0, EXEC_MS = 1, BLOCKS = 2;

    private void benchmark(String label, IntUnaryOperator call) {
        benchmark(label, call, "");
    }

    private void benchmark(String label, IntUnaryOperator call, String note) {
        // 1) 워밍업. 버린다. prepared statement 전환과 버퍼 캐시 적재가 여기서 끝난다.
        int rows = 0;
        for (int i = 0; i < WARMUP; i++) {
            entityManager.clear();
            rows = call.applyAsInt(i);
        }

        if (!note.contains("0 허용") && !note.contains("0행")) {
            assertThat(rows)
                    .as("%s 가 0행을 반환합니다. 데이터가 없는 쿼리를 재면 '빠르다'는 결론만 나옵니다", label)
                    .isPositive();
        }

        // 2) 이 메서드가 어떤 queryid를 쓰는지 한 번 호출해서 알아낸다.
        //    (페이징이나 컬렉션 조회처럼 한 번의 호출이 쿼리 여러 개를 내보내는 경우가 있다.)
        Map<Long, double[]> before = snapshotAll();
        entityManager.clear();
        call.applyAsInt(WARMUP);
        Map<Long, double[]> after = snapshotAll();

        List<Long> targets = after.keySet().stream()
                .filter(id -> after.get(id)[CALLS] > before.getOrDefault(id, new double[3])[CALLS])
                .collect(Collectors.toList());

        assertThat(targets)
                .as("pg_stat_statements가 %s 의 쿼리를 잡지 못했습니다", label)
                .isNotEmpty();

        int callsPerInvocation = (int) Math.round(targets.stream()
                .mapToDouble(id -> after.get(id)[CALLS] - before.getOrDefault(id, new double[3])[CALLS])
                .sum());

        // 3) 측정. 호출마다 증분을 떠서 표본을 만든다.
        List<Double> dbMs = new ArrayList<>(ITERATIONS);
        List<Double> javaMs = new ArrayList<>(ITERATIONS);
        double blocks = 0;
        int contaminated = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            // 1차 캐시가 남아 있으면 두 번째 호출부터 하이드레이션이 공짜가 되어
            // wall-clock이 실제보다 빠르게 나온다.
            entityManager.clear();

            double[] s0 = snapshot(targets);
            long t0 = System.nanoTime();
            call.applyAsInt(WARMUP + 1 + i);
            long t1 = System.nanoTime();
            double[] s1 = snapshot(targets);

            // 같은 DB를 앱이 함께 쓰고 있으면 증분에 남의 호출이 섞인다. 호출 수가 예상과
            // 다르면 그 표본은 버린다 — 섞인 값을 평균에 넣느니 몇 개 잃는 편이 낫다.
            if (Math.round(s1[CALLS] - s0[CALLS]) != callsPerInvocation) {
                contaminated++;
                continue;
            }
            dbMs.add(s1[EXEC_MS] - s0[EXEC_MS]);
            javaMs.add((t1 - t0) / 1_000_000.0);
            blocks += s1[BLOCKS] - s0[BLOCKS];
        }

        assertThat(dbMs)
                .as("%s: 쓸 수 있는 표본이 없습니다(오염 %d건)", label, contaminated)
                .isNotEmpty();

        report(label, rows, callsPerInvocation, dbMs, javaMs, blocks, contaminated, targets, note);
    }

    /** 전체 통계 스냅샷. 자기 자신(pg_stat_statements를 읽는 쿼리)은 뺀다. */
    private Map<Long, double[]> snapshotAll() {
        Map<Long, double[]> map = new HashMap<>();
        jdbcTemplate.query("""
                SELECT queryid, calls, total_exec_time, shared_blks_hit + shared_blks_read AS blks
                  FROM pg_stat_statements
                 WHERE queryid IS NOT NULL
                   AND query NOT LIKE '%pg_stat_statements%'
                """, rs -> {
            map.put(rs.getLong("queryid"),
                    new double[]{rs.getDouble("calls"), rs.getDouble("total_exec_time"), rs.getDouble("blks")});
        });
        return map;
    }

    /** 대상 queryid들의 누적 합. 호출마다 두 번 부르므로 좁게 조회한다. */
    private double[] snapshot(List<Long> queryIds) {
        String ids = queryIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(calls), 0)                              AS calls,
                       coalesce(sum(total_exec_time), 0)                    AS exec_ms,
                       coalesce(sum(shared_blks_hit + shared_blks_read), 0) AS blks
                  FROM pg_stat_statements
                 WHERE queryid IN (%s)
                """.formatted(ids),
                (rs, n) -> new double[]{rs.getDouble("calls"), rs.getDouble("exec_ms"), rs.getDouble("blks")});
    }

    // ── 출력 ────────────────────────────────────────────────────────────────

    private void report(String label, int rows, int callsPerInvocation,
                        List<Double> dbMs, List<Double> javaMs, double blocks,
                        int contaminated, List<Long> targets, String note) {

        double[] db = sorted(dbMs);
        double[] java = sorted(javaMs);
        int n = db.length;

        String line = "─".repeat(100);
        System.out.println("\n" + line);
        System.out.printf("📊 %s%s%n", label, note.isEmpty() ? "" : "   [" + note + "]");
        System.out.println(line);
        System.out.printf("   표본 %d회 (워밍업 %d회 버림%s)   반환 %d행   호출당 쿼리 %d개   호출당 블록 %.1f%n",
                n, WARMUP,
                contaminated > 0 ? ", 오염 " + contaminated + "회 버림" : "",
                rows, callsPerInvocation, blocks / n);
        System.out.println();
        System.out.printf("   %-16s %9s %9s %9s %9s %9s%n", "", "p50", "p95", "p99", "max", "평균");
        System.out.printf("   %-16s %8.3f㎳ %8.3f㎳ %8.3f㎳ %8.3f㎳ %8.3f㎳%n", "DB 실행",
                pct(db, 50), pct(db, 95), pct(db, 99), db[n - 1], mean(db));
        System.out.printf("   %-16s %8.3f㎳ %8.3f㎳ %8.3f㎳ %8.3f㎳ %8.3f㎳%n", "Java 왕복",
                pct(java, 50), pct(java, 95), pct(java, 99), java[n - 1], mean(java));

        System.out.println("\n   실행된 SQL:");
        for (Long id : targets) {
            String sql = jdbcTemplate.queryForObject(
                    "SELECT left(regexp_replace(query, '\\s+', ' ', 'g'), 400) FROM pg_stat_statements WHERE queryid = ?",
                    String.class, id);
            System.out.printf("     [%d] %s%n", id, sql);
        }
        System.out.println(line);

        RESULTS.add(new Result(label, rows, blocks / n,
                pct(db, 50), pct(db, 95), pct(db, 99),
                pct(java, 50), pct(java, 95), pct(java, 99), note));
    }

    /** 모든 테스트가 끝난 뒤 한 장으로 본다. 느린 것부터. */
    @AfterAll
    static void summary() {
        if (RESULTS.isEmpty()) return;
        List<Result> sorted = RESULTS.stream()
                .sorted((a, b) -> Double.compare(b.dbP95, a.dbP95))
                .toList();

        String line = "═".repeat(132);
        System.out.println("\n\n" + line);
        System.out.printf("📋 리포지토리 메서드 성능 요약 — DB p95 내림차순 (%d개)%n", sorted.size());
        System.out.println(line);
        System.out.printf("%-62s %7s %9s %9s %9s %9s %9s%n",
                "메서드", "행", "블록", "DB p50", "DB p95", "DB p99", "Java p95");
        System.out.println("─".repeat(132));
        for (Result r : sorted) {
            System.out.printf("%-62s %7d %9.1f %8.3f㎳ %8.3f㎳ %8.3f㎳ %8.3f㎳%n",
                    trim(r.method, 62), r.rows, r.blocks, r.dbP50, r.dbP95, r.dbP99, r.javaP95);
        }
        System.out.println(line);
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static double[] sorted(List<Double> values) {
        double[] a = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        return a;
    }

    /** nearest-rank 퍼센타일. 표본이 적을 때 보간법보다 해석이 단순하다. */
    private static double pct(double[] sorted, double p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.min(Math.max(rank, 1), sorted.length) - 1];
    }

    private static double mean(double[] values) {
        return Arrays.stream(values).average().orElse(Double.NaN);
    }
}
