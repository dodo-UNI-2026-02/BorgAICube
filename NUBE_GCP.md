# Borg IA Cube en Google Cloud (VM `oac-servidor`)

Se reutiliza la VM del parcial: `oac-servidor` (Ubuntu, `southamerica-west1-a`, IP estática **34.176.215.27**). En la nube solo corren **RabbitMQ, el backend y el servidor web**. Los nodos IA (Go) y los nodos BD (Python + su base) corren en las laptops y se conectan *hacia* la VM, así que no hay que abrir puertos en las casas.

```
laptops y celular ──► 34.176.215.27 :5000 (backend TCP) · :8080 (web) · :5672 (RabbitMQ)
```

## 1. Firewall (consola de Google Cloud, una vez)
☰ → **Red de VPC → Firewall** → abrir `permitir-open-ai-cubes` → **Editar** → *Protocolos y puertos* → TCP: `5000,6000,8080,5672` → **Guardar**.
Se mantienen las IP `/32` de cada integrante (cada uno ve la suya en https://api.ipify.org). El día de la exposición se agrega `0.0.0.0/0` y se quita al terminar.

## 2. Preparar la VM (una vez; se puede repetir para actualizar)
☰ → **Compute Engine → Instancias de VM** → si está detenida, **Iniciar** → botón **SSH** de `oac-servidor`. En la terminal:
```bash
curl -fsSL https://raw.githubusercontent.com/dodo-UNI-2026-02/BorgAICube/main/scripts/nube_preparar_vm.sh | bash
```
El script detiene Open AI Cubes (también usa el puerto 5000); instala RabbitMQ, Java 17, git y tmux; crea el vhost `cubo` y el usuario `cubo` con una clave aleatoria (en `~/cubo-clave.txt`); clona el repo y compila. Al final imprime la URL para los nodos.

## 3. Arrancar el servidor
```bash
bash ~/BorgAICube/scripts/servidor_nube.sh
cat ~/cubo-url-publica.txt          # URL que va en las laptops (compartirla solo con el equipo)
```
El backend y la web quedan en la sesión tmux `pc3` aunque se cierre el SSH. `tmux attach -t pc3` abre la consola del backend (comando `nodos` para ver quién está conectado). Para detenerlo: `tmux kill-session -t pc3`.

## 4. Conectar las laptops
En cada laptop, con la URL del paso 3:
```bash
export CUBO_RABBIT_URL='amqp://cubo:CLAVE@34.176.215.27:5672/cubo'      # PowerShell: $env:CUBO_RABBIT_URL='...'
```
| Laptop | Comando (desde la carpeta del repo) |
|---|---|
| La que tiene MariaDB: BD2, validación (**primero**) | `python codigo/python/nodo_bd.py --rol BD2 --db-host 127.0.0.1` |
| La que tiene PostgreSQL: BD1 | `python codigo/python/nodo_bd.py --rol BD1 --db-host 127.0.0.1` |
| La que tiene MySQL: BD3 | `python codigo/python/nodo_bd.py --rol BD3 --db-host 127.0.0.1` |
| Nodos IA (las que quieran) | Linux: `cd bin-linux && ./nodo` · Windows: `go build -o nodo.exe ./cmd/nodo` en `codigo/go`, o con Docker como en `scripts/local.sh` |

Cada base se crea antes con su script de `codigo/sql/`.

**Clientes:**
- Escritorio: `cd codigo && java -cp "java/out;java/lib/*" cubo.desktop.ClienteDesktop --host 34.176.215.27`
- Web: <http://34.176.215.27:8080>
- Android: IP `34.176.215.27`, puerto `5000`

## 5. Mediciones contra la nube
- Monitor, en la VM: `cd ~/BorgAICube && python3 codigo/python/monitor.py --corrida nube_r1 --rabbit "$(cat ~/cubo-url-local.txt)" --hasta-vaciar`
- Carga, desde una laptop: `GeneradorCarga --host 34.176.215.27 …`

## Memoria y costo
La e2-micro (1 GB, gratis) alcanza para la demo: RabbitMQ usa como máximo el 30 % de la RAM y el backend y la web están limitados con `-Xmx`. Para la prueba de saturación conviene subirla a **e2-small** solo ese día (*Detener → Editar → Tipo de máquina*, unos 0,02 USD/h) y volver a e2-micro al terminar. Para volver al parcial: `tmux kill-session -t pc3` y arrancar Open AI Cubes como antes.
