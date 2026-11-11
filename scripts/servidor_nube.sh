#!/usr/bin/env bash
# Arranca el backend (TCP 5000) y el servidor web (HTTP 8080) en una sesión tmux "pc3",
# para que sigan vivos al cerrar la ventana SSH. Ver: tmux attach -t pc3 · Detener: tmux kill-session -t pc3
cd "$(dirname "$0")/../codigo/java"
export CUBO_RABBIT_URL=$(cat ~/cubo-url-local.txt)
tmux kill-session -t pc3 2>/dev/null
tmux new-session -d -s pc3 -n backend "CUBO_RABBIT_URL=$CUBO_RABBIT_URL java -Xmx256m -cp 'out:lib/*' cubo.backend.Backend; bash"
sleep 3
tmux new-window -t pc3 -n web "java -Xmx128m -cp 'out:lib/*' cubo.web.ServidorHttp --www ../web; bash"
sleep 2
tmux capture-pane -p -t pc3:backend | tail -3
tmux capture-pane -p -t pc3:web | tail -2
echo "Servidor en marcha (tmux attach -t pc3 para ver la consola; Ctrl+B y luego D para salir)."
