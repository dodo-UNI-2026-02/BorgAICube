#!/usr/bin/env bash
# Prepara la VM de Google Cloud (oac-servidor, Ubuntu) como servidor de Borg IA Cube:
# RabbitMQ + backend Java + servidor web. Se puede volver a correr sin problema.
#   curl -fsSL https://raw.githubusercontent.com/dodo-UNI-2026-02/BorgAICube/main/scripts/nube_preparar_vm.sh -o preparar.sh && bash preparar.sh
# (No usar "curl ... | bash": rabbitmqctl lee la entrada estándar y se comería el resto del script.)
set -e
REPO=https://github.com/dodo-UNI-2026-02/BorgAICube.git
DIR=~/BorgAICube

echo "== 1/5 Detener Open AI Cubes (usa el puerto 5000)"
tmux kill-session -t oac 2>/dev/null || true
sudo fuser -k 5000/tcp 6000/tcp 2>/dev/null || true   # por si se lanzó fuera de tmux u otro usuario

echo "== 2/5 Paquetes: RabbitMQ, Java 17, git, tmux, pika"
APT="sudo apt-get -o DPkg::Lock::Timeout=900"   # espera a las actualizaciones automáticas de Ubuntu
$APT update -qq
$APT install -y -qq rabbitmq-server openjdk-17-jdk-headless git tmux python3-pika > /dev/null

echo "== 3/5 RabbitMQ: vhost y usuario 'cubo' (clave aleatoria, guardada en ~/cubo-clave.txt)"
[ -f ~/cubo-clave.txt ] || (head -c 12 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' > ~/cubo-clave.txt; chmod 600 ~/cubo-clave.txt)
CLAVE=$(cat ~/cubo-clave.txt)
echo 'vm_memory_high_watermark.relative = 0.3' | sudo tee /etc/rabbitmq/rabbitmq.conf > /dev/null
sudo rabbitmq-plugins enable -q rabbitmq_management > /dev/null
sudo systemctl restart rabbitmq-server
sudo rabbitmqctl -q add_vhost cubo 2>/dev/null || true
sudo rabbitmqctl -q add_user cubo "$CLAVE" 2>/dev/null || sudo rabbitmqctl -q change_password cubo "$CLAVE"
sudo rabbitmqctl -q set_permissions -p cubo cubo ".*" ".*" ".*"
sudo rabbitmqctl -q set_user_tags cubo monitoring

echo "== 4/5 Código: clonar/actualizar y compilar Java"
if [ -d "$DIR/.git" ]; then git -C "$DIR" pull -q; else git clone -q "$REPO" "$DIR"; fi
cd "$DIR/codigo/java" && rm -rf out && javac -encoding UTF-8 -d out -cp "lib/*" $(find src -name '*.java')

echo "== 5/5 Listo"
IP=$(curl -s -H Metadata-Flavor:Google http://metadata/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip)
echo "amqp://cubo:$CLAVE@localhost:5672/cubo" > ~/cubo-url-local.txt
echo "amqp://cubo:$CLAVE@$IP:5672/cubo" > ~/cubo-url-publica.txt
cat <<FIN

Arrancar el servidor:      bash $DIR/scripts/servidor_nube.sh
URL para los nodos (laptops), en ~/cubo-url-publica.txt:
  CUBO_RABBIT_URL=$(cat ~/cubo-url-publica.txt)
Clientes: backend $IP:5000 · web http://$IP:8080
Firewall: TCP 5000, 8080 y 5672 abiertos para las IP del equipo.
FIN
