-- =====================================================================
-- BD3 — MySQL 8 — Base del MODELO B (células sanguíneas, BloodMNIST)
-- Ejecutar como root:   mysql -u root -p < bd3_mysql.sql
-- Vuelve a crear la base desde cero.
-- =====================================================================
SET NAMES utf8mb4;
DROP DATABASE IF EXISTS cubo_modelo_b;
CREATE DATABASE cubo_modelo_b CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'cubo'@'%' IDENTIFIED BY 'cubo123';
CREATE USER IF NOT EXISTS 'cubo'@'localhost' IDENTIFIED BY 'cubo123';
GRANT ALL PRIVILEGES ON cubo_modelo_b.* TO 'cubo'@'%';
GRANT ALL PRIVILEGES ON cubo_modelo_b.* TO 'cubo'@'localhost';
FLUSH PRIVILEGES;

USE cubo_modelo_b;

-- Metadatos de las imágenes procesadas
CREATE TABLE imagenes (
  id_imagen    BIGINT AUTO_INCREMENT PRIMARY KEY,
  id_solicitud CHAR(36) NOT NULL UNIQUE,
  id_cliente   INT      NOT NULL,
  sha256       CHAR(64) NOT NULL,
  formato      VARCHAR(5),
  ancho        INT,
  alto         INT,
  bytes        INT,
  plataforma   VARCHAR(10),
  recibido_en  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_img_cliente (id_cliente)
) ENGINE=InnoDB;

-- Resultados de la clasificación (columna JSON nativa de MySQL)
CREATE TABLE resultados (
  id_resultado         BIGINT AUTO_INCREMENT PRIMARY KEY,
  id_solicitud         CHAR(36) NOT NULL UNIQUE,
  etiqueta             VARCHAR(40) NOT NULL,
  indice_clase         SMALLINT,
  probabilidad         FLOAT NOT NULL,
  probabilidades       JSON NOT NULL,
  version_modelo       VARCHAR(40),
  nodo_ia              VARCHAR(40),
  tiempo_inferencia_ms INT,
  latencia_total_ms    INT,
  id_validacion        BIGINT,
  creado_en            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_res_creado (creado_en),
  CONSTRAINT fk_res_img FOREIGN KEY (id_solicitud) REFERENCES imagenes(id_solicitud)
) ENGINE=InnoDB;

-- Historial de consultas del usuario
CREATE TABLE historial_consultas (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  id_cliente   INT NOT NULL,
  id_solicitud CHAR(36) UNIQUE,
  tipo         VARCHAR(15) NOT NULL,
  estado       ENUM('OK', 'RECHAZADO', 'ERROR') NOT NULL,
  motivo       VARCHAR(200),
  creado_en    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_hist_cliente_fecha (id_cliente, creado_en)
) ENGINE=InnoDB;

-- Métricas de entrenamiento del modelo (cargadas desde los CSV)
CREATE TABLE metricas_entrenamiento (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  modo           VARCHAR(10) NOT NULL,
  epoca          INT NOT NULL,
  loss_train     FLOAT, acc_train FLOAT,
  loss_val       FLOAT, acc_val   FLOAT,
  tiempo_epoca_s FLOAT,
  creado_en      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

SELECT 'BD3 (MySQL) creada' AS estado;
