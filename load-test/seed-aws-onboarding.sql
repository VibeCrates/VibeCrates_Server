-- 취향 벡터가 없는 LOCAL 계정을 만든다. 해시는 기존 시딩 계정에서 가져와 비밀번호 규칙을 맞춘다.
-- 재실행해도 완료한 계정을 초기화하지 않는다. 부족한 잔량은 준비 단계에서 바로 막는다.
\set ON_ERROR_STOP on
INSERT INTO users (login_id, pwd, email, nickname, birth_date, role, gender, login_type, created_at, updated_at)
SELECT 'loadtest_new' || n, s.pwd, 'loadtest_new' || n || '@example.com',
       'loadtest_new' || n, DATE '1995-01-01', 'USER', 'OTHER', 'LOCAL', now(), now()
FROM generate_series(0, :new_users - 1) n
CROSS JOIN (SELECT pwd FROM users WHERE login_id = 'loadtest_seed') s
ON CONFLICT (login_id) DO NOTHING;
