# Guía del equipo: qué hace cada integrante

El servidor (RabbitMQ + backend + web) corre en Google Cloud: **34.176.215.27**. Cada laptop se conecta *hacia* él, así que no hay que abrir puertos en casa. Todos los comandos son para **PowerShell en Windows** y se ejecutan desde la carpeta del repo.

## Reparto

| | SO | Base de datos | Ejecuta |
|---|---|---|---|
| **Servidor** | Linux (Google Cloud) | — | RabbitMQ, backend Java (:5000), servidor web (:8080). Lo administra Integrante01 |
| **Integrante01** | Windows | **BD2 – MariaDB** (validación) | Nodo BD2 (Python), 1 nodo IA (Go), clientes de escritorio y web, generador de carga |
| **Integrante02** | Windows | **BD1 – PostgreSQL** (modelo A, malaria) | Nodo BD1 (Python), 1 nodo IA (Go) |
| **Integrante03** | Windows | **BD3 – MySQL** (modelo B, sangre) | Nodo BD3 (Python), 1 nodo IA (Go), **celular Android** |

Así se cumplen los requisitos del enunciado: 3 SO (Linux, Windows y Android), 3 bases distintas en 3 nodos independientes y 3 lenguajes en nodos distintos (Java en el servidor, Python y Go en las laptops, Kotlin en el celular).

## 1. Instalar (una vez, todos)

1. **Git**, **Python 3.10+**, **Go 1.24+** y **Java 17+** (JDK).
2. Clonar el repo y bajar los datos del [Release v1.0](https://github.com/dodo-UNI-2026-02/BorgAICube/releases/tag/v1.0):
   ```powershell
   git clone https://github.com/dodo-UNI-2026-02/BorgAICube.git
   cd BorgAICube
   ```
   Descomprimir `datos_bin.zip` dentro de la carpeta `datos\`; deben quedar `datos\malaria_32.bin` y `datos\blood_28.bin`. Solo se usan para el entrenamiento distribuido.
3. Dependencias de Python y compilación:
   ```powershell
   pip install pika psycopg2-binary pymysql
   mkdir resultados/equipo -Force
   go build -C codigo/go -o ../../nodo.exe ./cmd/nodo
   go build -C codigo/go -o ../../entrenar.exe ./cmd/entrenar
   javac -encoding UTF-8 -d codigo/java/out -cp "codigo/java/lib/*" (Get-ChildItem -Recurse codigo/java/src -Filter *.java).FullName
   ```
4. La **URL de RabbitMQ** con la clave te la pasa Integrante01 por privado (es el contenido de `~/cubo-url-publica.txt` en la VM). **No la subas al repo.** En cada PowerShell nueva:
   ```powershell
   $env:CUBO_RABBIT_URL = "amqp://cubo:CLAVE@34.176.215.27:5672/cubo"
   ```

## 2. Crear la base de datos (una vez, cada uno la suya)

Instalar el motor que te toca, con un usuario administrador, y correr su script:

| Integrante | Motor (puerto) | Comando |
|---|---|---|
| Integrante01 | MariaDB 10.6+ (3306) | `mariadb -u root -p -e "source codigo/sql/bd2_mariadb.sql"` |
| Integrante02 | PostgreSQL 13+ (5432) | `psql -U postgres -f codigo/sql/bd1_postgresql.sql` |
| Integrante03 | MySQL 8 (3306) | `mysql -u root -p -e "source codigo/sql/bd3_mysql.sql"` |

Cada script crea la base desde cero y el usuario `cubo`. BD2 trae los usuarios del chat: `ana`, `luis` y `demo`, con clave `clave123`, y `usuario1` … `usuario1000` para la carga.

## 3. Día de la prueba: levantar todo en este orden

1. **Integrante01** enciende la VM (Compute Engine → `oac-servidor` → Iniciar), entra por **SSH** y ejecuta:
   ```bash
   cd ~/BorgAICube && git pull && bash scripts/servidor_nube.sh
   ```
2. **Cada integrante abre dos PowerShell** (con `$env:CUBO_RABBIT_URL` puesta en las dos).
   - **Ventana 1, el nodo BD** (Integrante01 primero, porque BD2 valida a todos):
     ```powershell
     python codigo\python\nodo_bd.py --rol BD2 --db-host 127.0.0.1     # Integrante01
     python codigo\python\nodo_bd.py --rol BD1 --db-host 127.0.0.1     # Integrante02
     python codigo\python\nodo_bd.py --rol BD3 --db-host 127.0.0.1     # Integrante03
     ```
     Debe decir `… | 8 hilos | esperando mensajes...`.
   - **Ventana 2, el nodo IA:**
     ```powershell
     .\nodo.exe -datos datos
     ```
     Debe decir `consumiendo cola_modeloA …` y `cola_modeloB …`.
3. **Comprobar:** en la VM, `tmux attach -t pc3` y escribir `nodos`. Deben aparecer 1 BACKEND, BD1, BD2, BD3 y 3 IA. Para salir sin apagarlo: `Ctrl+B` y luego `D`.

## 4. Usar los clientes

- **Web:** <http://34.176.215.27:8080>
- **Escritorio:** `java -cp "codigo/java/out;codigo/java/lib/*" cubo.desktop.ClienteDesktop --host 34.176.215.27`
- **Terminal** (registrar usuarios uno por uno): `java -cp "codigo/java/out;codigo/java/lib/*" cubo.desktop.ClienteTerminal --host 34.176.215.27`
- **Android** (Integrante03): instalar `BorgIACube.apk` del Release v1.0. Hay que permitir "orígenes desconocidos". La IP ya viene puesta (`34.176.215.27`, puerto `5000`).

Las imágenes de prueba están en `datos\muestras\` (`A_*` = malaria, `B_*` = sangre).

## 5. Mediciones con el clúster real (para el informe)

Antes de cada corrida, Integrante01 deja el monitor corriendo en la VM:
```bash
cd ~/BorgAICube && python3 codigo/python/monitor.py --corrida NOMBRE --rabbit "$(cat ~/cubo-url-local.txt)" --hasta-vaciar --resultados ~/resultados
```

**a) Inferencia en las 4 configuraciones.** Cambian solo los nodos IA (Ctrl+C para detenerlos y relanzarlos con otros flags). La carga la lanza Integrante01, con estos dos comandos a la vez en dos ventanas:
```powershell
java -cp "codigo/java/out;codigo/java/lib/*" cubo.carga.GeneradorCarga --plataforma DESKTOP --host 34.176.215.27 --n 1000 --concurrencia 32 --delay-min 0 --delay-max 50 --config CONFIG --nodos-ia M --muestras datos/muestras --salida resultados/equipo/carga_DESKTOP_CONFIG_MM.csv
java -cp "codigo/java/out;codigo/java/lib/*" cubo.carga.GeneradorCarga --plataforma WEB --host 34.176.215.27 --puerto 8080 --n 1000 --concurrencia 32 --delay-min 0 --delay-max 50 --config CONFIG --nodos-ia M --muestras datos/muestras --salida resultados/equipo/carga_WEB_CONFIG_MM.csv
```
| CONFIG | Nodos IA encendidos (M) | Flags del nodo IA |
|---|---|---|
| `seq` | 1 (Integrante01) | `-datos datos -sin-entrenamiento -hilos 1 -gomaxprocs 1` |
| `par` | 1 (Integrante01) | `-datos datos -sin-entrenamiento` (todos sus núcleos) |
| `dist` | 2 y luego 3 | `-datos datos -sin-entrenamiento -hilos 1 -gomaxprocs 1` |
| `pardist` | 1, 2 y 3 | `-datos datos -sin-entrenamiento` |

**b) Entrenamiento en las 4 configuraciones** (lo corre Integrante01; para `dist` y `pardist`, los nodos IA de los demás deben estar encendidos y sin `-sin-entrenamiento`):
```powershell
.\entrenar.exe -datos datos -resultados resultados/equipo -pesos datos/tmp.gob -epocas 3 -modelo A -modo seq
.\entrenar.exe -datos datos -resultados resultados/equipo -pesos datos/tmp.gob -epocas 3 -modelo A -modo par
.\entrenar.exe -datos datos -resultados resultados/equipo -pesos datos/tmp.gob -epocas 3 -modelo A -modo dist -workers 3 -sufijo _M3
.\entrenar.exe -datos datos -resultados resultados/equipo -pesos datos/tmp.gob -epocas 3 -modelo A -modo pardist -workers 3 -sufijo _M3
```
Luego lo mismo con `-modelo B`.

**c) Saturación:** con los 3 nodos IA encendidos, Integrante01 lanza los dos comandos de carga con `--config saturacion --nodos-ia 3`, y **al mismo tiempo** Integrante03 pulsa **Estrés** en el celular. Al terminar, la app muestra la ruta de `carga_ANDROID.csv`; hay que pasar ese archivo a la PC por USB.

**d) 1000 registros nuevos:**
```powershell
java -cp "codigo/java/out;codigo/java/lib/*" cubo.carga.GeneradorCarga --tipo REGISTRO --host 34.176.215.27 --n 1000 --concurrencia 8 --delay-min 0 --delay-max 50 --config registro --salida resultados/equipo/carga_REGISTRO.csv
```

**e) Validar BD1, BD2 y BD3** (al final; debe decir «CONSISTENTES»):
```powershell
python codigo\python\verificar_consistencia.py --rabbit $env:CUBO_RABBIT_URL --salida resultados/equipo/consistencia.csv --carga (Get-ChildItem resultados/equipo/carga_*.csv).FullName
```

**Entregar los resultados:** Integrante01 baja los CSV del monitor desde la VM (botón **Descargar archivo** del SSH, carpeta `~/resultados`), junta todo en `resultados/equipo/`, incluido el CSV de Android, y lo sube al repo. Con eso se actualizan las gráficas (`python codigo/python/graficas.py`) y el informe.

## Al terminar
- Ctrl+C en los nodos de cada laptop.
- En la VM: `tmux kill-session -t pc3` y luego **Detener** la VM. Los puertos están abiertos a todo internet, así que no la dejen encendida sin usar.

## Problemas frecuentes
| Síntoma | Solución |
|---|---|
| `ACCESS_REFUSED` al iniciar un nodo | URL o clave de RabbitMQ mal copiadas |
| El chat dice `TIMEOUT` | Falta algún nodo: BD2 siempre, BD1 para malaria, BD3 para sangre, y al menos un nodo IA |
| `Can't connect to MySQL/PostgreSQL` | El motor de tu base no está corriendo, o no se ejecutó el script SQL |
| El cliente se conecta y se desconecta al instante | En la VM, volver a correr `bash scripts/servidor_nube.sh` |
| `nodo.exe` bloqueado por Windows | Correrlo con `go run ./cmd/nodo -datos ../../datos` desde `codigo/go`, o permitirlo en Windows Security |
| Más detalle del servidor | `NUBE_GCP.md` |
