CREATE TABLE users (
    user_id    INT PRIMARY KEY,
    username   VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE polls (
    poll_id    INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    creator_id INT NOT NULL REFERENCES users (user_id),
    question   VARCHAR(300) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE options (
    option_id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    poll_id   INT NOT NULL REFERENCES polls (poll_id),
    text      VARCHAR(100) NOT NULL,
    position  SMALLINT NOT NULL,
    UNIQUE (poll_id, option_id)
);

CREATE TABLE votes (
    poll_id    INT NOT NULL,
    user_id    INT NOT NULL REFERENCES users (user_id),
    option_id  INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (poll_id, user_id),                       
    FOREIGN KEY (poll_id, option_id) REFERENCES options (poll_id, option_id)
);
-- For the reconciler ("polls with votes in the last N seconds").
CREATE INDEX votes_created_at_idx ON votes (created_at);

CREATE TABLE idempotency_keys (
    user_id      INT NOT NULL,
    key          UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status_code  SMALLINT,
    response     JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key)                          
);

-- Test users for load tests (user_id 1..100000).
INSERT INTO users (user_id, username)
SELECT g, 'user' || g FROM generate_series(1, 100000) AS g;
