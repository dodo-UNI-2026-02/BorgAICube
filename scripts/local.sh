#!/usr/bin/env bash
# Levanta todo el sistema en UNA máquina para probarlo (Windows con Git Bash o Linux).
# Requisitos: RabbitMQ en localhost:5672 (vhost cubo, usuario cubo/cubo123), las 3 bases
# creadas (ver README) y Docker para los nodos IA (Linux).
#   ./scripts/local.sh [nodos_ia] [flags del nodo]      ej.: ./scripts/local.sh 2 -hilos 1 -gomaxprocs 1
#   ./scripts/local.sh parar
set -u
cd "$(dirname "$0")/.."
RAIZ=$(pwd -W 2>/dev/null || pwd)
export MSYS_NO_PATHCONV=1 PYTHONIOENCODING=utf-8
export CUBO_RABBIT_URL=${CUBO_RABBIT_URL:-amqp://cubo:cubo123@localhost:5672/cubo}
CP="codigo/java/out;codigo/java/lib/*"; [ "$(uname)" = Linux ] && CP="codigo/java/out:codigo/java/lib/*"
PY=.venv/Scripts/python; [ -x "$PY" ] || PY=.venv/bin/python
mkdir -p resultados/logs

if [ "${1:-}" = parar ]; then
  docker rm -f $(docker ps -aq --filter name=cubo-ia-) >/dev/null 2>&1
  for f in resultados/logs/*.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null; rm -f "$f"; done
  exit 0
fi

N=${1:-1}; shift || true
iniciar() { local nombre=$1; shift; "$@" > "resultados/logs/$nombre.log" 2>&1 & echo $! > "resultados/logs/$nombre.pid"; }

iniciar backend java -cp "$CP" cubo.backend.Backend --sin-consola
sleep 3
iniciar web java -cp "$CP" cubo.web.ServidorHttp --www codigo/web
iniciar bd2 $PY codigo/python/nodo_bd.py --rol BD2 --db-host 127.0.0.1 --db-puerto 3306
sleep 2
iniciar bd1 $PY codigo/python/nodo_bd.py --rol BD1 --db-host 127.0.0.1 --db-puerto 5432
iniciar bd3 $PY codigo/python/nodo_bd.py --rol BD3 --db-host 127.0.0.1 --db-puerto 3307
for i in $(seq 1 "$N"); do
  docker run -d --rm --name "cubo-ia-$i" --hostname "nodo$i" -v "$RAIZ:/pc3" -w /pc3/bin-linux \
    -e CUBO_RABBIT_URL="${CUBO_RABBIT_URL/localhost/host.docker.internal}" alpine:3.20 ./nodo "$@" > /dev/null
done
echo "Listo: backend :5000, web http://localhost:8080, BD1/BD2/BD3 y $N nodo(s) IA. Logs en resultados/logs/"
