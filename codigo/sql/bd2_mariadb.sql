-- =====================================================================
-- BD2 — MariaDB — Base de VALIDACIÓN (cliente, producto/modelo, imagen)
-- Ejecutar como root:   mariadb -u root -p < bd2_mariadb.sql
-- Vuelve a crear todo desde cero (borra la base si existe).
-- =====================================================================
SET NAMES utf8mb4;
DROP DATABASE IF EXISTS cubo_validacion;
CREATE DATABASE cubo_validacion CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Usuario de aplicación usado por nodo_bd.py (y por verificar_consistencia.py)
CREATE USER IF NOT EXISTS 'cubo'@'%' IDENTIFIED BY 'cubo123';
CREATE USER IF NOT EXISTS 'cubo'@'localhost' IDENTIFIED BY 'cubo123';
GRANT ALL PRIVILEGES ON cubo_validacion.* TO 'cubo'@'%';
GRANT ALL PRIVILEGES ON cubo_validacion.* TO 'cubo'@'localhost';
FLUSH PRIVILEGES;

USE cubo_validacion;

-- Validación del cliente
CREATE TABLE clientes (
  id_cliente     INT AUTO_INCREMENT PRIMARY KEY,
  usuario        VARCHAR(50)  NOT NULL UNIQUE,
  password_hash  CHAR(64)     NOT NULL,          -- SHA-256(salt || password) en hex
  salt           CHAR(32)     NOT NULL,
  nombre         VARCHAR(100) NOT NULL,
  estado         ENUM('ACTIVO','SUSPENDIDO') NOT NULL DEFAULT 'ACTIVO',
  cuota_diaria   INT NOT NULL DEFAULT 5000,
  usadas_hoy     INT NOT NULL DEFAULT 0,
  fecha_cuota    DATE NOT NULL,
  creado_en      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

-- Validación del producto: modelos de IA disponibles
CREATE TABLE modelos (
  id_modelo   CHAR(1) PRIMARY KEY,                -- 'A', 'B'
  nombre      VARCHAR(60) NOT NULL,
  activo      BOOLEAN NOT NULL DEFAULT TRUE,
  clases_json TEXT NOT NULL
) ENGINE=InnoDB;

-- Qué modelos puede usar cada cliente
CREATE TABLE cliente_modelo (
  id_cliente INT NOT NULL,
  id_modelo  CHAR(1) NOT NULL,
  PRIMARY KEY (id_cliente, id_modelo),
  FOREIGN KEY (id_cliente) REFERENCES clientes(id_cliente),
  FOREIGN KEY (id_modelo)  REFERENCES modelos(id_modelo)
) ENGINE=InnoDB;

-- Validación de la imagen
CREATE TABLE reglas_imagen (
  id_modelo           CHAR(1) PRIMARY KEY,
  formatos_permitidos VARCHAR(50) NOT NULL,       -- 'png,jpg'
  bytes_max           INT NOT NULL,
  ancho_min           INT NOT NULL,
  alto_min            INT NOT NULL,
  FOREIGN KEY (id_modelo) REFERENCES modelos(id_modelo)
) ENGINE=InnoDB;

CREATE TABLE imagenes_bloqueadas (
  sha256 CHAR(64) PRIMARY KEY,
  motivo VARCHAR(200)
) ENGINE=InnoDB;

-- Auditoría de TODA validación (la usa verificar_consistencia.py)
CREATE TABLE auditoria_validaciones (
  id_validacion BIGINT AUTO_INCREMENT PRIMARY KEY,
  tipo          ENUM('LOGIN','REGISTRO','SOLICITUD','IMAGEN') NOT NULL,
  id_cliente    INT NULL,
  id_solicitud  CHAR(36) NULL,
  nodo_origen   VARCHAR(20),
  valido        BOOLEAN NOT NULL,
  motivo        VARCHAR(200),
  creado_en     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_aud_solicitud (id_solicitud),
  INDEX idx_aud_cliente (id_cliente, creado_en)
) ENGINE=InnoDB;

-- ----------------------------- Datos semilla -----------------------------
INSERT INTO modelos (id_modelo, nombre, clases_json) VALUES
 ('A', 'Malaria (NIH)',                 '["Parasitada","No infectada"]'),
 ('B', 'Células sanguíneas (BloodMNIST)', '["basófilo","eosinófilo","eritroblasto","granulocito inmaduro","linfocito","monocito","neutrófilo","plaqueta"]');

INSERT INTO reglas_imagen VALUES
 ('A', 'png,jpg', 2097152, 16, 16),
 ('B', 'png,jpg', 2097152, 16, 16);

-- 3 usuarios de demostración (contraseña: clave123) y 1 suspendido
INSERT INTO clientes (usuario, password_hash, salt, nombre, fecha_cuota)
SELECT u, SHA2(CONCAT(MD5(CONCAT('sal-', u)), 'clave123'), 256), MD5(CONCAT('sal-', u)), n, CURDATE()
FROM (SELECT 'ana' AS u, 'Ana Pérez' AS n UNION ALL SELECT 'luis', 'Luis Quispe'
      UNION ALL SELECT 'demo', 'Usuario Demo' UNION ALL SELECT 'suspendido', 'Cuenta Suspendida') t;
UPDATE clientes SET estado = 'SUSPENDIDO' WHERE usuario = 'suspendido';

-- 1000 usuarios para las pruebas de carga: usuario1 … usuario1000 (contraseña: clave123)
INSERT INTO clientes (usuario, password_hash, salt, nombre, fecha_cuota)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000)
SELECT CONCAT('usuario', n),
       SHA2(CONCAT(MD5(CONCAT('sal-usuario', n)), 'clave123'), 256),
       MD5(CONCAT('sal-usuario', n)),
       CONCAT('Usuario de carga ', n),
       CURDATE()
FROM seq;

-- Todos los clientes pueden usar ambos modelos
INSERT INTO cliente_modelo (id_cliente, id_modelo)
SELECT c.id_cliente, m.id_modelo FROM clientes c CROSS JOIN modelos m;

-- Hashes bloqueados de ejemplo (SHA-256 de cadenas de prueba)
INSERT INTO imagenes_bloqueadas VALUES
 (SHA2('imagen-bloqueada-1', 256), 'Imagen reportada como corrupta'),
 (SHA2('imagen-bloqueada-2', 256), 'Imagen duplicada de prueba'),
 (SHA2('imagen-bloqueada-3', 256), 'Contenido no médico'),
 (SHA2('imagen-bloqueada-4', 256), 'Imagen de prueba bloqueada'),
 (SHA2('imagen-bloqueada-5', 256), 'Imagen de prueba bloqueada');

SELECT COUNT(*) AS clientes_creados FROM clientes;
