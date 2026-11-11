-- =====================================================================
-- BD1 — PostgreSQL — Base del MODELO A (malaria)
-- Ejecutar como superusuario con psql:
--     psql -U postgres -f bd1_postgresql.sql
-- Vuelve a crear la base desde cero.
-- =====================================================================
SET client_encoding = 'UTF8';
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cubo') THEN
    CREATE ROLE cubo LOGIN PASSWORD 'cubo123';
  END IF;
END $$;

DROP DATABASE IF EXISTS cubo_modelo_a WITH (FORCE);  -- PostgreSQL 13+: cierra conexiones abiertas
CREATE DATABASE cubo_modelo_a OWNER cubo ENCODING 'UTF8' TEMPLATE template0;

\connect cubo_modelo_a
SET ROLE cubo;

-- Metadatos de las imágenes procesadas
CREATE TABLE imagenes (
  id_imagen    BIGSERIAL PRIMARY KEY,
  id_solicitud CHAR(36)  NOT NULL UNIQUE,
  id_cliente   INT       NOT NULL,
  sha256       CHAR(64)  NOT NULL,
  formato      VARCHAR(5),
  ancho        INT,
  alto         INT,
  bytes        INT,
  plataforma   VARCHAR(10),
  recibido_en  TIMESTAMP(3) NOT NULL DEFAULT now()
);

-- Resultados de la clasificación (JSONB: característica propia de PostgreSQL)
CREATE TABLE resultados (
  id_resultado         BIGSERIAL PRIMARY KEY,
  id_solicitud         CHAR(36) NOT NULL UNIQUE REFERENCES imagenes(id_solicitud),
  etiqueta             VARCHAR(40) NOT NULL,
  indice_clase         SMALLINT,
  probabilidad         REAL NOT NULL,
  probabilidades       JSONB NOT NULL,
  version_modelo       VARCHAR(40),
  nodo_ia              VARCHAR(40),
  tiempo_inferencia_ms INT,
  latencia_total_ms    INT,
  id_validacion        BIGINT,
  creado_en            TIMESTAMP(3) NOT NULL DEFAULT now()
);

-- Historial de consultas del usuario
CREATE TABLE historial_consultas (
  id           BIGSERIAL PRIMARY KEY,
  id_cliente   INT NOT NULL,
  id_solicitud CHAR(36) UNIQUE,
  tipo         VARCHAR(15) NOT NULL,
  estado       VARCHAR(12) NOT NULL CHECK (estado IN ('OK', 'RECHAZADO', 'ERROR')),
  motivo       VARCHAR(200),
  creado_en    TIMESTAMP(3) NOT NULL DEFAULT now()
);

-- Métricas de entrenamiento del modelo (cargadas desde los CSV)
CREATE TABLE metricas_entrenamiento (
  id             BIGSERIAL PRIMARY KEY,
  modo           VARCHAR(10) NOT NULL,
  epoca          INT NOT NULL,
  loss_train     REAL, acc_train REAL,
  loss_val       REAL, acc_val   REAL,
  tiempo_epoca_s REAL,
  creado_en      TIMESTAMP(3) NOT NULL DEFAULT now()
);

CREATE INDEX idx_hist_cliente_fecha ON historial_consultas (id_cliente, creado_en);
CREATE INDEX idx_img_cliente        ON imagenes (id_cliente);
CREATE INDEX idx_res_creado         ON resultados (creado_en);
-- índice GIN sobre JSONB para consultas por probabilidad de clase
CREATE INDEX idx_res_probs          ON resultados USING GIN (probabilidades);

SELECT 'BD1 (PostgreSQL) creada' AS estado;
