# PC3: Borg IA Cube (CNN distribuida con chat multiplataforma y RabbitMQ)

**Curso:** CC4P1 Programación Concurrente y Distribuida, UNI 2026-2 · **Semana:** 11–12 · **Enunciado:** [`enunciado/P3cc4P1-262_v07.pdf`](enunciado/P3cc4P1-262_v07.pdf)

Un usuario envía la foto de una célula desde un chat (Android, escritorio o web) y recibe el diagnóstico de una CNN escrita desde cero:

- **Modelo A, malaria** (NIH, 27 558 imágenes, 32×32): parasitada / no infectada. Accuracy de prueba: **95,2 %**.
- **Modelo B, células sanguíneas** (BloodMNIST, 17 092 imágenes, 28×28): 8 tipos. Accuracy de prueba: **88,6 %**.

**¿Eres del equipo? Empieza por [`GUIA_EQUIPO.md`](GUIA_EQUIPO.md)** (qué hace cada integrante) y descarga el APK y los datasets del [Release v1.0](https://github.com/dodo-UNI-2026-02/BorgAICube/releases/tag/v1.0). El servidor en Google Cloud está en [`NUBE_GCP.md`](NUBE_GCP.md).

Base: el proyecto del compañero (`sem11/anvancePC3`). Se reescribió en Go el nodo IA, cuyo código fuente no venía en el zip; se escribió la app Android, el monitor y las gráficas; y se probó todo. El detalle está en [`PLAN.md`](PLAN.md).

## Viaje de una consulta

```
Cliente ─TCP propio─► Backend ─► RabbitMQ ─► Nodo IA (CNN) ─► RabbitMQ ─► Nodo BD1/BD3 ─► RabbitMQ ─► Backend ─► Cliente
                         └── valida usuario y cuota en BD2 (RPC por RabbitMQ) ──┘      └── valida la imagen en BD2 y guarda en 1 transacción
```

## Componentes

| Carpeta | Lenguaje | Qué es |
|---|---|---|
| `codigo/java/` | Java | Backend (TCP :5000), servidor HTTP propio (:8080), cliente de escritorio (Swing y terminal) y generador de carga |
| `codigo/web/` | HTML/JS | Frontend del chat web (HTTP, sin WebSocket) |
| `codigo/go/` | Go | CNN (`cnn/`), nodo IA (`cmd/nodo`), entrenamiento en 4 modos (`cmd/entrenar`) y entrenamiento distribuido por RabbitMQ (`distrib/`) |
| `codigo/python/` | Python | Nodos BD1 (PostgreSQL), BD2 (MariaDB, validación) y BD3 (MySQL); verificación de consistencia, monitor y gráficas |
| `codigo/android/` | Kotlin | App Android (solo SDK de Android, sin librerías externas) |
| `codigo/sql/` | SQL | Scripts de creación de las 3 bases |
| `datos/` | — | Datasets preprocesados (`.bin`), modelos entrenados (`.gob`) y 100 imágenes de prueba |
| `resultados/` | — | CSV de las mediciones y `graficas/` |
| `informe/` | — | Informe y presentación (PDF) |
| `scripts/` | Bash | `local.sh` (levanta todo en una PC), `mediciones.sh`; `empaquetar.py` arma el comprimido de entrega |

Librerías externas: solo los clientes de RabbitMQ (`amqp-client`, `amqp091-go`, `pika`) y los drivers de las bases (`psycopg2`, `pymysql`). Las gráficas usan `matplotlib`, pero no forman parte del sistema.

## Cómo ejecutarlo en una PC

Requisitos: Java 17+, Go 1.24+, Python 3.10+, RabbitMQ (con el plugin de gestión), Docker y Android Studio para el APK.

1. **RabbitMQ:** crear el vhost `cubo` y el usuario `cubo`/`cubo123` con permisos en él.
2. **Bases** (en Docker o instaladas):
   ```bash
   docker run -d --name cubo-bd1-postgres -e POSTGRES_PASSWORD=root123 -p 5432:5432 -v "$PWD/codigo/sql:/sql" postgres:16
   docker run -d --name cubo-bd2-mariadb -e MARIADB_ROOT_PASSWORD=root123 -p 3306:3306 -v "$PWD/codigo/sql:/sql" mariadb:11
   docker run -d --name cubo-bd3-mysql -e MYSQL_ROOT_PASSWORD=root123 -p 3307:3306 -v "$PWD/codigo/sql:/sql" mysql:8.4
   docker exec cubo-bd1-postgres psql -U postgres -f /sql/bd1_postgresql.sql
   docker exec cubo-bd2-mariadb sh -c 'mariadb -uroot -proot123 < /sql/bd2_mariadb.sql'
   docker exec cubo-bd3-mysql sh -c 'mysql -uroot -proot123 < /sql/bd3_mysql.sql'
   ```
3. **Compilar:**
   ```bash
   python -m venv .venv && .venv/Scripts/pip install pika psycopg2-binary pymysql matplotlib
   cd codigo/java && javac -encoding UTF-8 -d out -cp "lib/*" $(find src -name '*.java') && cd ../..
   cd codigo/go && GOOS=linux CGO_ENABLED=0 go build -o ../../bin-linux/ ./cmd/... && cd ../..
   ```
4. **Levantar todo** (backend, web, BD1/BD2/BD3 y 1 nodo IA en un contenedor Linux): `./scripts/local.sh 1`. Para detenerlo: `./scripts/local.sh parar`.
5. **Clientes:**
   - Escritorio (GUI): `cd codigo && java -cp "java/out;java/lib/*" cubo.desktop.ClienteDesktop`
   - Terminal (registros uno por uno): `cd codigo && java -cp "java/out;java/lib/*" cubo.desktop.ClienteTerminal`
   - Web: <http://localhost:8080>
   - Android: `codigo/android` → `gradlew assembleDebug`, instalar el APK y poner la IP del servidor (puerto 5000).

   Usuarios: `ana`, `luis` o `demo` con clave `clave123`; también `usuario1` … `usuario1000` para la carga.
6. **Entrenar un modelo:** `cd bin-linux && ./entrenar -modelo A -modo par -epocas 12` (modos `seq`, `par`, `dist`, `pardist`; en `dist` y `pardist` los nodos IA conectados hacen de workers).

## Despliegue en red (3 SO, 3 BD, 3 lenguajes)

Cada pieza solo necesita la URL de RabbitMQ (`CUBO_RABBIT_URL=amqp://cubo:cubo123@IP_SERVIDOR:5672/cubo`) y los clientes, la IP del backend.

| Máquina | SO | Ejecuta |
|---|---|---|
| Servidor | Linux (VM o nube) | RabbitMQ, backend Java y servidor web Java |
| PC 1 | Windows | Nodo BD2 (Python + MariaDB) y clientes de escritorio y web |
| PC 2 | Linux | Nodos IA (Go) y nodo BD1 (Python + PostgreSQL) |
| PC 3 | Windows/Linux | Nodo BD3 (Python + MySQL) y más nodos IA |
| Celular | Android | App Borg IA Cube (Kotlin) |

## Mediciones

`./scripts/mediciones.sh entrenamiento | inferencia | saturacion | registros` y después `python codigo/python/graficas.py`. Las M máquinas se simulan con contenedores limitados a núcleos fijos (`--cpuset-cpus`). La consistencia de las 3 bases se revisa con `python codigo/python/verificar_consistencia.py --rabbit $CUBO_RABBIT_URL --carga resultados/carga_*.csv`.
