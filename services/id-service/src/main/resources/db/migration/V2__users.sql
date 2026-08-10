-- Users belong to exactly one company. Usernames are unique per company, not globally:
-- two companies must be able to have a "driver-1" without knowing about each other.

CREATE TABLE users
(
    id            CHAR(36)     NOT NULL,
    company_id    CHAR(36)     NOT NULL,
    username      VARCHAR(150) NOT NULL,
    display_name  VARCHAR(150) NULL,
    password_hash VARCHAR(100) NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_company_username (company_id, username),
    CONSTRAINT fk_users_company FOREIGN KEY (company_id) REFERENCES companies (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
