#!/usr/bin/env bash
# Experimentos con el clúster del equipo (servidor en Google Cloud), lanzados desde la PC de un integrante.
# Requiere: export CUBO_RABBIT_URL='amqp://cubo:CLAVE@34.176.215.27:5672/cubo'
#   ./scripts/equipo.sh seq | par        (fase A: solo el nodo IA de esta PC)
#   ./scripts/equipo.sh pardist | dist   (fases B y C: con los nodos IA de todos)
#   ./scripts/equipo.sh entrenar_local | entrenar_dist | saturacion | registros | validar
set -u
cd "$(dirname "$0")/.."
RAIZ=$(pwd -W 2>/dev/null || pwd)
export MSYS_NO_PATHCONV=1 PYTHONIOENCODING=utf-8
: "${CUBO_RABBIT_URL:?falta CUBO_RABBIT_URL}"
HOST=34.176.215.27
R=resultados/equipo
PY=.venv/Scripts/python; [ -x "$PY" ] || PY=.venv/bin/python
CP="java/out;java/lib/*"; [ "$(uname)" = Linux ] && CP="java/out:java/lib/*"
mkdir -p $R

ia_local() { # nodo IA de esta PC en un contenedor Linux: ia_local <cpuset> <flags...>
  local cpus=$1; shift
  docker rm -f cubo-ia-nube > /dev/null 2>&1
  docker run -d --rm --name cubo-ia-nube --hostname integrante02 --cpuset-cpus "$cpus" -v "$RAIZ:/pc3" \
    -w /pc3/bin-linux -e CUBO_RABBIT_URL="$CUBO_RABBIT_URL" alpine:3.20 ./nodo "$@" > /dev/null
  sleep 8
}

carga() { # carga <config> <nodos_ia>: 1000 por escritorio + 1000 por web a la vez, con monitor
  local cfg=$1 n=$2
  $PY codigo/python/monitor.py --corrida "${cfg}_M$n" --resultados $R ${MON:---hasta-vaciar} > /dev/null 2>&1 & local mon=$!
  sleep 2
  (cd codigo && java -cp "$CP" cubo.carga.GeneradorCarga --plataforma DESKTOP --host $HOST --n 1000 --concurrencia 32 \
     --delay-min 0 --delay-max 50 --config "$cfg" --nodos-ia "$n" --salida ../$R/carga_DESKTOP_${cfg}_M$n.csv | tail -1) & local des=$!
  (cd codigo && java -cp "$CP" cubo.carga.GeneradorCarga --plataforma WEB --host $HOST --puerto 8080 --n 1000 --concurrencia 32 \
     --delay-min 0 --delay-max 50 --config "$cfg" --nodos-ia "$n" --salida ../$R/carga_WEB_${cfg}_M$n.csv | tail -1)
  wait $des; wait $mon
}

entrenar() { # entrenar <cpuset> <flags...>: coordinador / entrenamiento local en un contenedor
  local cpus=$1; shift
  docker run --rm --cpuset-cpus "$cpus" -v "$RAIZ:/pc3" -w /pc3/bin-linux -e CUBO_RABBIT_URL="$CUBO_RABBIT_URL" \
    alpine:3.20 ./entrenar -epocas 3 -pesos /tmp/tmp.gob -resultados ../$R "$@" | grep -E 'modo|época|Prueba|nodo '
}

case "${1:-}" in
  seq) ia_local 0 -hilos 1 -gomaxprocs 1 -sin-entrenamiento; carga seq 1 ;;
  par) ia_local 0-11 -sin-entrenamiento; carga par 1 ;;
  entrenar_local) for M in A B; do entrenar 0 -modelo $M -modo seq; entrenar 0-11 -modelo $M -modo par -hilos 12; done ;;
  pardist) ia_local 0-11 -sin-entrenamiento; carga pardist 3 ;;
  dist) ia_local 0 -hilos 1 -gomaxprocs 1 -sin-entrenamiento; carga dist 3 ;;
  entrenar_dist)
    ia_local 0-11
    for M in A B; do
      entrenar 0-11 -modelo $M -modo dist -workers 3 -sufijo _M3
      entrenar 0-11 -modelo $M -modo pardist -workers 3 -sufijo _M3
    done ;;
  saturacion) MON="--duracion 75" carga saturacion 3 ;;
  registros)
    (cd codigo && java -cp "$CP" cubo.carga.GeneradorCarga --tipo REGISTRO --host $HOST --n 1000 --concurrencia 8 \
       --delay-min 0 --delay-max 50 --config registro --salida ../$R/carga_REGISTRO.csv | tail -1) ;;
  validar)
    $PY codigo/python/verificar_consistencia.py --rabbit "$CUBO_RABBIT_URL" --salida $R/consistencia.csv --carga $R/carga_*.csv ;;
  *) echo "uso: $0 seq|par|entrenar_local|pardist|dist|entrenar_dist|saturacion|registros|validar" ;;
esac
