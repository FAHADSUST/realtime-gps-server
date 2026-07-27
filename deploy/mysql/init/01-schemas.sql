-- Runs once, on first boot of an empty MySQL data volume.
--
-- One instance, one schema per service: services never read each other's tables,
-- and each service's Flyway history stays independent.

CREATE DATABASE IF NOT EXISTS gps_id       CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS gps_history  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS gps_metadata CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Local development credentials only. Real deployments get per-service secrets.
CREATE USER IF NOT EXISTS 'gps_id'@'%'       IDENTIFIED BY 'gps_id_pw';
CREATE USER IF NOT EXISTS 'gps_history'@'%'  IDENTIFIED BY 'gps_history_pw';
CREATE USER IF NOT EXISTS 'gps_metadata'@'%' IDENTIFIED BY 'gps_metadata_pw';

GRANT ALL PRIVILEGES ON gps_id.*       TO 'gps_id'@'%';
GRANT ALL PRIVILEGES ON gps_history.*  TO 'gps_history'@'%';
GRANT ALL PRIVILEGES ON gps_metadata.* TO 'gps_metadata'@'%';

FLUSH PRIVILEGES;
