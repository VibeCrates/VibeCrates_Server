package com.crates.crates.entity;

import com.crates.crates.enumData.UserEventType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * 사용자 행동 로그 한 건(docs/board-schema.md 13장). 쌓기만 하고 고치지 않는다.
 *
 * <p>payload의 모양은 이벤트마다 다르다. 만드는 곳은 {@code UserEventLogger} 한 곳이다.</p>
 */
@Entity
@Table(name = "user_event_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 로그는 유저가 탈퇴해도 남아야 하므로 객체 연관관계(ManyToOne) 대신 ID만 들고 있는다.
    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserEventType eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    /**
     * DB가 넣는다(default now(), 트랜잭션 시작 시각). 한 트랜잭션에서 쌓인 이벤트는 시각이 같아 짝을 찾을 수 있다.
     * 앱에서 Instant.now()로 넣으면 호출마다 마이크로초가 달라 짝이 흐려진다. 저장 직후에는 읽지 않으므로 다시 조회하지 않는다.
     */
    @Column(nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public UserEventLog(Long userId, UserEventType eventType, Map<String, Object> payload)
    {
        this.userId = userId;
        this.eventType = eventType;
        this.payload = payload;
    }
}
