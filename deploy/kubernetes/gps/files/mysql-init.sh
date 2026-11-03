#!/bin/bash
# Runs once, when MySQL first starts on an empty volume: one schema and one user per service, with
# the passwords from the gps-secrets Secret. The local stack uses deploy/mysql/init instead.
set -euo pipefail

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS gps_id      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS gps_history CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'gps_id'@'%'      IDENTIFIED BY '${GPS_ID_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'gps_history'@'%' IDENTIFIED BY '${GPS_HISTORY_DB_PASSWORD}';

GRANT ALL PRIVILEGES ON gps_id.*      TO 'gps_id'@'%';
GRANT ALL PRIVILEGES ON gps_history.* TO 'gps_history'@'%';
SQL
