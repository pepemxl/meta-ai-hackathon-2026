-- pp-coach-tracker: esquema de la API (Node y PHP usan el mismo).
-- Solo CREATE TABLE IF NOT EXISTS: se puede correr varias veces.
-- Las fechas del teléfono (consentimiento) se guardan en UTC.

CREATE TABLE IF NOT EXISTS users (
  id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(190) NOT NULL,
  name VARCHAR(160) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS matches (
  id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  coach_id VARCHAR(64) NOT NULL,
  client_match_id VARCHAR(160) NOT NULL,
  home VARCHAR(160) NOT NULL,
  away VARCHAR(160) NOT NULL,
  home_color VARCHAR(160) NOT NULL,
  away_color VARCHAR(160) NOT NULL,
  match_date VARCHAR(160) NOT NULL,
  venue VARCHAR(160) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_matches_client (coach_id, client_match_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clips (
  id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  coach_id VARCHAR(64) NOT NULL,
  client_clip_id VARCHAR(160) NOT NULL,
  match_id INT UNSIGNED NOT NULL,
  title VARCHAR(160) NOT NULL,
  duration_ms INT UNSIGNED NOT NULL,
  chunk_count SMALLINT UNSIGNED NOT NULL,
  chunk_seconds SMALLINT UNSIGNED NOT NULL,
  overlap_ms SMALLINT UNSIGNED NOT NULL,
  -- lines | no_lines | off | NULL (docs/prompts/13-cancha-en-metros.md)
  pitch_mode VARCHAR(16) NULL,
  -- static | moving | NULL: la cámara quieta todo el video (una homografía) o con movimiento y zoom
  camera VARCHAR(16) NULL,
  -- Cuándo borró el video el cron de retención (bin/retention.php), o NULL.
  video_deleted_at DATETIME NULL,
  -- Puntos de la cancha que marcó el entrenador (JSON), o NULL.
  calibration TEXT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'registered',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_clips_client (coach_id, client_clip_id),
  CONSTRAINT fk_clips_match FOREIGN KEY (match_id) REFERENCES matches (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clip_chunks (
  clip_id INT UNSIGNED NOT NULL,
  number SMALLINT UNSIGNED NOT NULL,
  sha256 CHAR(64) NOT NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (clip_id, number),
  CONSTRAINT fk_chunks_clip FOREIGN KEY (clip_id) REFERENCES clips (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS jobs (
  id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  clip_id INT UNSIGNED NOT NULL,
  type VARCHAR(20) NOT NULL DEFAULT 'segment',
  status VARCHAR(20) NOT NULL DEFAULT 'queued',
  error VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at DATETIME NULL,
  finished_at DATETIME NULL,
  KEY idx_jobs_status (status, id),
  CONSTRAINT fk_jobs_clip FOREIGN KEY (clip_id) REFERENCES clips (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- La usa la API PHP para el límite de intentos de login (Node lo lleva en memoria).
CREATE TABLE IF NOT EXISTS login_failures (
  id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(190) NOT NULL,
  ip VARCHAR(45) NOT NULL,
  at DATETIME NOT NULL,
  KEY idx_login_email (email, at),
  KEY idx_login_ip (ip, at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS consent_events (
  id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  coach_id VARCHAR(64) NOT NULL,
  action VARCHAR(10) NOT NULL,
  version VARCHAR(20) NOT NULL,
  at DATETIME NOT NULL,
  received_at DATETIME NOT NULL,
  ip VARCHAR(45) NULL,
  app_version VARCHAR(40) NULL,
  UNIQUE KEY uq_consent_event (coach_id, action, version, at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clip_consent_events (
  clip_id INT UNSIGNED NOT NULL PRIMARY KEY,
  event_id INT UNSIGNED NOT NULL,
  CONSTRAINT fk_cce_clip FOREIGN KEY (clip_id) REFERENCES clips (id) ON DELETE CASCADE,
  CONSTRAINT fk_cce_event FOREIGN KEY (event_id) REFERENCES consent_events (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clip_analyses (
  clip_id INT UNSIGNED NOT NULL PRIMARY KEY,
  job_id INT UNSIGNED NOT NULL,
  segmenter VARCHAR(40) NULL,
  analyst VARCHAR(40) NULL,
  result MEDIUMTEXT NOT NULL,
  created_at DATETIME NOT NULL,
  CONSTRAINT fk_analyses_clip FOREIGN KEY (clip_id) REFERENCES clips (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
