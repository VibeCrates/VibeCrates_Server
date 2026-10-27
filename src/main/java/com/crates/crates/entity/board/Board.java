package com.crates.crates.entity.board;

import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.contents.Content;
import com.crates.crates.entity.user.User;
import com.crates.crates.enumData.BoardType;
import com.crates.crates.enumData.Visibility;
import jakarta.persistence.*;
import lombok.*;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Getter // 무한루프를 방지하기 위해
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@EqualsAndHashCode(of = "id")
public class Board {

    /**
     * 보드 하나에 담기는 콘텐츠 수. 서비스 규칙상 고정값이다.
     * 보드를 만들어 내려보내는 쪽(검색/추천)과 저장하는 쪽(좋아요)이 같은 값을 봐야
     * "8건으로 보여줬는데 저장은 거절당하는" 상황이 생기지 않는다.
     */
    public static final int ITEMS_PER_BOARD = 8;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 이 보드가 귀속되는 사용자.
     *
     * <p>살아 있는 USER_CUSTOM일 때만 값이 있다. AI_RECOMMEND·SEARCH_RECOMMEND는 소유자가 없는 전역 공용 보드라
     * null이며, 그래서 한 사용자가 그 보드를 고쳐도 다른 사용자에게 영향이 가지 않도록
     * 원본을 고치지 않고 그 사람의 보드로 새로 저장한다. 주인이 지운 USER_CUSTOM도 null이다(BoardRepository.softDelete).
     * 이 규칙은 ck_board_owner(V3)가 DB에서 강제한다.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private String title;

    @Enumerated(EnumType.STRING)
    private BoardType boardType;

    /** 인기 보드 노출 여부. PRIVATE은 USER_CUSTOM만 가질 수 있다(ck_board_visibility). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Visibility visibility;

    /**
     * 콘텐츠 8건의 id를 오름차순 정렬해 콤마로 이은 문자열. 이 보드의 "내용 정체성"이다.
     *
     * <p>정렬하므로 배치 순서(slotNo)가 바뀌어도 값이 변하지 않는다. 즉 순서만 바꾼 수정은
     * 새 보드를 만들지 않는다. 해시하지 않는 이유는, AI_RECOMMEND에 unique 제약이 걸려 있어
     * 해시 충돌이 곧 서로 다른 보드의 오병합이 되기 때문이다.</p>
     */
    @Column(nullable = false, length = 160)
    private String contentSignature;

    private LocalDateTime createdAt;
    private LocalDateTime deletedAt; // 소프트 삭제(Soft Delete)용

    private Long likeCount;

    // 🌟 양방향 매핑 (Board -> BoardItem)
    @OneToMany(
            mappedBy = "board",          // 1. "BoardItem 엔티티의 'board' 필드가 관계의 주인이다"라고 선언, 누가 외래키의 주인인지 명시
            cascade = CascadeType.ALL,   // 2. Board를 저장/삭제할 때 BoardItem도 함께 저장/삭제됨
            orphanRemoval = true         // 3. 리스트에서 BoardItem을 빼기만 해도 DB에서 DELETE 쿼리가 날아감
    )
    @ToString.Exclude   // 무한루프 방지
    @Builder.Default    // @Builder가 리스트 초기화 식을 무시하는 것을 방지하기 위해
    private List<BoardItem> boardItems = new ArrayList<>(); // NullPointerException 방지를 위한 초기화

    /**
     * 보드의 콘텐츠를 통째로 교체한다.
     *
     * <p>보드는 8건 고정이라 개별 추가/삭제가 성립하지 않는다(7건이나 9건인 상태가 생긴다).
     * 전달된 <b>순서가 곧 배치 순서</b>(slotNo 1~8)가 되고, contentSignature도 여기서 함께 갱신된다.</p>
     *
     * <p>signature 갱신을 서비스에 맡기지 않는 이유: 호출을 한 번만 빠뜨려도 unique 제약과
     * 인기 보드 중복 제거가 조용히 틀어진다. 즉시 에러가 나지 않고 나중에 이상한 증상으로만
     * 드러나는 종류라, 파생 관계를 엔티티가 직접 보장한다.</p>
     */
    public void replaceContents(List<Content> contents)
    {
        validateContents(contents);

        // orphanRemoval=true라 리스트를 비우면 기존 BoardItem은 DELETE된다.
        this.boardItems.clear();
        for (int index = 0; index < contents.size(); index++)
        {
            this.boardItems.add(BoardItem.builder()
                    .board(this)
                    .content(contents.get(index))
                    .slotNo(index + 1)
                    .build());
        }

        this.contentSignature = signatureOf(contents.stream().map(Content::getId).toList());
    }

    /** 제목 변경. USER_CUSTOM 보드만 사용자가 직접 바꿀 수 있다(BoardService가 판단). */
    public void updateTitle(String title)
    {
        this.title = normalizeTitle(title);
    }

    /** 주인이 지운 보드인지. 지우는 것은 BoardRepository.softDelete가 한다. */
    public boolean isDeleted()
    {
        return deletedAt != null;
    }

    /**
     * 저장·비교에 쓰는 제목. 유니코드 NFC로 바꾸고 앞뒤 공백만 걷어낸다.
     *
     * <p>제목은 사용자의 표현이라 대소문자와 문장 안의 공백은 그대로 둔다("Jazz"와 "jazz"는 다른 제목이다).
     * NFC는 화면에 똑같이 보이는 한글이 기기(macOS·iOS 입력의 NFD)에 따라 다른 바이트로 들어와
     * 같은 제목이 두 보드로 갈라지는 것만 막는다. 같은 제목인지는 이 값으로 판단하므로
     * (uk_board_user_title_signature, uk_board_search_title_signature) 저장하는 모든 경로가 이 메서드를 거쳐야 한다.</p>
     *
     * <p>{@code @NotBlank}는 ASCII 공백만 걸러서 전각 공백(U+3000)만 있는 제목이 통과한다. strip 후 비면 여기서 막는다.</p>
     */
    public static String normalizeTitle(String title)
    {
        String normalized = title == null ? "" : Normalizer.normalize(title, Normalizer.Form.NFC).strip();
        if (normalized.isEmpty())
        {
            throw new BusinessException("보드 제목을 입력해주세요.");
        }
        return normalized;
    }

    /**
     * 정렬된 content id 문자열을 만든다.
     *
     * <p>저장 전에 "같은 구성의 보드가 이미 있는지" 찾아볼 때도 반드시 이 메서드로 계산해야
     * 저장된 값과 비교가 성립한다.</p>
     */
    public static String signatureOf(Collection<Long> contentIds)
    {
        return contentIds.stream()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private void validateContents(List<Content> contents)
    {
        if (contents.size() != ITEMS_PER_BOARD)
        {
            throw new BusinessException(
                    "보드에는 콘텐츠 " + ITEMS_PER_BOARD + "건이 필요합니다. 전달: " + contents.size());
        }

        // 같은 콘텐츠가 두 번 들어가면 signature가 "1,1,2,..."가 되어 8건 규칙이 무너지고
        // 슬롯 하나가 중복 포스터로 채워진다. DB의 uk_board_item_content와 같은 규칙이다.
        Set<Long> distinctIds = contents.stream()
                .map(Content::getId)
                .collect(Collectors.toSet());
        if (distinctIds.size() != contents.size())
        {
            throw new BusinessException("보드에 같은 콘텐츠를 두 번 담을 수 없습니다.");
        }
    }
}
