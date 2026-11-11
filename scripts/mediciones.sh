#!/usr/bin/env bash
# Mediciones de la PC3 en UNA máquina: cada nodo IA es un contenedor Linux limitado a
# ciertos núcleos (--cpuset-cpus), así se simulan "M máquinas con N núcleos".
# Antes: ./scripts/local.sh 0   (backend, web y nodos BD, sin nodos IA)
#   ./scripts/mediciones.sh entrenamiento | inferencia | saturacion | registros | todo
set -u
cd "$(dirname "$0")/.."
RAIZ=$(pwd -W 2>/dev/null || pwd)
export MSYS_NO_PATHCONV=1 PYTHONIOENCODING=utf-8
URL_DOCKER=amqp://cubo:cubo123@host.docker.internal:5672/cubo
PY=.venv/Scripts/python; [ -x "$PY" ] || PY=.venv/bin/python
CP="java/out;java/lib/*"; [ "$(uname)" = Linux ] && CP="java/out:java/lib/*"
R=resultados
NUCLEO=(0 2 4)              # 1 núcleo físico por "máquina" (dist)
GRUPO=("0-3" "4-7" "8-11")  # 4 núcleos lógicos por "máquina" (pardist)

ia() { # ia <n> <cpuset> <flags del nodo...>
  local n=$1 cpus=$2; shift 2
  docker run -d --rm --name "cubo-ia-$n" --hostname "maquina$n" --cpuset-cpus "$cpus" -v "$RAIZ:/pc3" \
    -w /pc3/bin-linux -e CUBO_RABBIT_URL=$URL_DOCKER alpine:3.20 ./nodo "$@" > /dev/null
}
parar_ia() { docker rm -f $(docker ps -aq --filter name=cubo-ia-) > /dev/null 2>&1; sleep 1; }
entrenar() { # entrenar <cpuset> <flags...>
  local cpus=$1; shift
  docker run --rm --cpuset-cpus "$cpus" -v "$RAIZ:/pc3" -w /pc3/bin-linux -e CUBO_RABBIT_URL=$URL_DOCKER \
    alpine:3.20 ./entrenar -epocas 3 -pesos /tmp/tmp.gob -resultados ../resultados/entrenamiento "$@"
}

medir_entrenamiento() {
  for M in A B; do
    entrenar 0 -modelo $M -modo seq
    entrenar 0-11 -modelo $M -modo par -hilos 12
    for W in 2 3; do
      parar_ia; for i in $(seq 1 $W); do ia $i "${NUCLEO[$((i-1))]}" -modelos $M; done; sleep 8
      entrenar 0-11 -modelo $M -modo dist -workers $W -sufijo _M$W
      parar_ia; for i in $(seq 1 $W); do ia $i "${GRUPO[$((i-1))]}" -modelos $M; done; sleep 8
      entrenar 0-11 -modelo $M -modo pardist -workers $W -sufijo _M$W
    done
  done
  parar_ia
}

carga() { # carga <config> <nodos_ia> : 1000 consultas DESKTOP + 1000 WEB a la vez, con monitor
  local cfg=$1 n=$2
  $PY codigo/python/monitor.py --corrida "$cfg" ${MON:---hasta-vaciar} > /dev/null 2>&1 & local mon=$!
  sleep 2
  (cd codigo && java -cp "$CP" cubo.carga.GeneradorCarga --plataforma DESKTOP --n 1000 --concurrencia 32 \
     --delay-min 0 --delay-max 50 --config "$cfg" --nodos-ia "$n" --salida ../$R/carga_DESKTOP_${cfg}_M$n.csv | tail -1) & local des=$!
  (cd codigo && java -cp "$CP" cubo.carga.GeneradorCarga --plataforma WEB --n 1000 --concurrencia 32 \
     --delay-min 0 --delay-max 50 --config "$cfg" --nodos-ia "$n" --salida ../$R/carga_WEB_${cfg}_M$n.csv | tail -1)
  wait $des; wait $mon
}

medir_inferencia() {
  parar_ia; ia 1 0 -hilos 1 -gomaxprocs 1 -sin-entrenamiento; sleep 3; carga seq 1
  parar_ia; ia 1 0-3 -hilos 8 -sin-entrenamiento; sleep 3; carga par 1
  for M in 2 3; do
    parar_ia; for i in $(seq 1 $M); do ia $i "${NUCLEO[$((i-1))]}" -hilos 1 -gomaxprocs 1 -sin-entrenamiento; done
    sleep 3; carga dist $M
  done
  for M in 1 2 3; do
    parar_ia; for i in $(seq 1 $M); do ia $i "${GRUPO[$((i-1))]}" -hilos 8 -sin-entrenamiento; done
    sleep 3; carga pardist $M
  done
  parar_ia
}

case "${1:-todo}" in
  entrenamiento) medir_entrenamiento ;;
  inferencia) medir_inferencia ;;
  saturacion)  # Android se lanza a mano desde la app (botón Estrés) al mismo tiempo
    parar_ia; for i in 1 2 3; do ia $i "${GRUPO[$((i-1))]}" -hilos 8 -sin-entrenamiento; done; sleep 3
    MON="--duracion 60" carga saturacion 3 ;;
  registros)
    (cd codigo && java -cp "$CP" cubo.carga.GeneradorCarga --tipo REGISTRO --n 1000 --concurrencia 8 \
       --delay-min 0 --delay-max 50 --config registro --salida ../$R/carga_REGISTRO.csv | tail -1) ;;
  todo) medir_entrenamiento; medir_inferencia ;;
esac
