-- Every position the platform has ever accepted.
--
-- The unique key is doing two jobs at once. It is the idempotency mechanism - a redelivered batch
-- re-inserts the same rows and MySQL turns those into no-ops - and, because it starts with
-- (company_id, user_id, recorded_at), it is also the index every history query reads through. One
-- index, not two, on the table that grows fastest.
--
-- The cost: two genuinely distinct fixes for one user in the same millisecond collapse into one.
-- For GPS that is a deduplication, not a loss.

CREATE TABLE user_location
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    company_id  CHAR(36)    NOT NULL,
    user_id     CHAR(36)    NOT NULL,
    latitude    DOUBLE      NOT NULL,
    longitude   DOUBLE      NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    received_at DATETIME(3) NOT NULL,
    accuracy    DOUBLE      NULL,
    speed       DOUBLE      NULL,
    heading     DOUBLE      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_location_fix (company_id, user_id, recorded_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- When this table outgrows one machine the next step is RANGE partitioning on recorded_at, monthly,
-- so old partitions can be dropped instead of deleted. That requires recorded_at in every unique
-- key, which the key above already satisfies.
