-- Companies are the tenants of the platform: every user, location ping and metadata
-- document belongs to exactly one company.

CREATE TABLE companies
(
    id              CHAR(36)     NOT NULL,
    name            VARCHAR(150) NOT NULL,
    contact_email   VARCHAR(255) NULL,
    app_key         VARCHAR(64)  NOT NULL,
    app_secret_hash VARCHAR(100) NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_companies_app_key (app_key),
    UNIQUE KEY uk_companies_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
