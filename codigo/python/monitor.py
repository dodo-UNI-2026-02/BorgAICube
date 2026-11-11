"""Monitor de una corrida: cada 0,5 s guarda la profundidad de las colas de RabbitMQ
(declaración pasiva AMQP + API HTTP de gestión) y la CPU/memoria que publica cada nodo en cola_metricas.

Uso:
  python monitor.py --corrida par_r1 [--duracion 120]      (Ctrl+C para terminar)
  python monitor.py --corrida saturacion --hasta-vaciar
Salida: resultados/colas_<corrida>.csv y resultados/metricas_nodos_<corrida>.csv
"""
import argparse
import base64
import csv
import json
import os
import time
import urllib.parse
import urllib.request

import pika


def vacias(ch):
    return all(ch.queue_declare(q, passive=True).method.message_count == 0
               for q in ("cola_modeloA", "cola_modeloB", "cola_resultadosA", "cola_resultadosB", "cola_validacion"))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--corrida", required=True)
    p.add_argument("--duracion", type=float, default=0, help="segundos (0 = hasta Ctrl+C)")
    p.add_argument("--rabbit", default=os.environ.get("CUBO_RABBIT_URL", "amqp://cubo:cubo123@localhost:5672/cubo"))
    p.add_argument("--hasta-vaciar", action="store_true", help="terminar cuando las colas de trabajo queden vacías")
    p.add_argument("--api-puerto", type=int, default=15672)
    p.add_argument("--resultados", default=os.path.join(os.path.dirname(__file__), "..", "..", "resultados"))
    a = p.parse_args()

    u = urllib.parse.urlparse(a.rabbit)
    vhost = urllib.parse.quote(u.path.lstrip("/") or "/", safe="")
    api = f"http://{u.hostname}:{a.api_puerto}/api/queues/{vhost}"
    auth = "Basic " + base64.b64encode(f"{u.username}:{u.password}".encode()).decode()

    os.makedirs(a.resultados, exist_ok=True)
    fc = open(os.path.join(a.resultados, f"colas_{a.corrida}.csv"), "w", newline="", encoding="utf-8")
    fm = open(os.path.join(a.resultados, f"metricas_nodos_{a.corrida}.csv"), "w", newline="", encoding="utf-8")
    wc, wm = csv.writer(fc), csv.writer(fm)
    wc.writerow(["t_s", "cola", "mensajes", "listos", "sin_ack", "publicados_s", "entregados_s"])
    wm.writerow(["t_s", "nodo", "rol", "cpu_pct", "mem_mb", "procesados", "errores"])

    con = pika.BlockingConnection(pika.URLParameters(a.rabbit))
    ch = con.channel()
    ch.queue_purge("cola_metricas")  # solo métricas de esta corrida
    ch2 = con.channel()  # para leer la profundidad de las colas en tiempo real
    t0 = siguiente = time.time()
    print(f"monitor {a.corrida}: Ctrl+C para terminar")
    try:
        for metodo, _, cuerpo in ch.consume("cola_metricas", auto_ack=True, inactivity_timeout=0.2):
            ahora = time.time()
            if cuerpo:
                m = json.loads(cuerpo)
                wm.writerow([round(ahora - t0, 1), m.get("nodo"), m.get("rol"), round(m.get("cpuPct", 0), 2),
                             round(m.get("memMB", 0), 1), m.get("procesados", ""), m.get("errores", "")])
            if ahora >= siguiente:  # colas, cada 0,5 s
                siguiente += 0.5
                req = urllib.request.Request(api, headers={"Authorization": auth})
                for q in json.load(urllib.request.urlopen(req, timeout=5)):
                    if not q["name"].startswith("cola_") or q["name"].startswith("cola_admin"):
                        continue
                    st = q.get("message_stats", {})
                    listos = ch2.queue_declare(q["name"], passive=True).method.message_count  # al instante
                    wc.writerow([round(ahora - t0, 1), q["name"], listos + q.get("messages_unacknowledged", 0), listos,
                                 q.get("messages_unacknowledged", 0), st.get("publish_details", {}).get("rate", 0),
                                 st.get("deliver_get_details", {}).get("rate", 0)])
                fc.flush()
                fm.flush()
            if a.duracion and ahora - t0 >= a.duracion:
                break
            if a.hasta_vaciar and ahora - t0 > 20 and vacias(ch2):
                break
    except KeyboardInterrupt:
        pass
    con.close()
    print("guardado:", fc.name, fm.name)


if __name__ == "__main__":
    main()
