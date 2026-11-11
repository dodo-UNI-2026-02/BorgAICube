"""Nodo de base de datos (Python). Un proceso por base:

  BD1 (PostgreSQL, modelo A) y BD3 (MySQL, modelo B):
      consumen cola_resultadosA|B → validan la imagen con BD2 (RPC por
      cola_validacion) → guardan imagen + resultado + historial en UNA
      transacción → publican en cola_respuestas.
      Consumen además cola_historialA|B (RPC) para el historial del usuario.
  BD2 (MariaDB, validación):
      consume cola_validacion (LOGIN, REGISTRO, SOLICITUD, IMAGEN) y responde
      al reply_to con el mismo correlation_id.

Uso:
  python nodo_bd.py --rol BD1 --db-host localhost --rabbit amqp://cubo:cubo123@IP_RABBIT:5672/cubo
  python nodo_bd.py --rol BD1 --cargar-metricas ../resultados/entrenamiento_A_par.csv
"""
import argparse
import csv
import json
import os
import platform
import socket
import sys
import threading
import time

import adaptadores
import validador
from consumidor import EX_CONTROL, EX_CUBO, Rabbit
from metricas import Medidor


def args():
    e = os.environ.get
    p = argparse.ArgumentParser(description="Nodo BD del Cubo IA")
    p.add_argument("--rol", required=True, choices=["BD1", "BD2", "BD3"])
    p.add_argument("--rabbit", default=e("CUBO_RABBIT_URL", "amqp://cubo:cubo123@localhost:5672/cubo"))
    p.add_argument("--db-host", default=e("CUBO_DB_HOST", "localhost"))
    p.add_argument("--db-puerto", type=int, default=int(e("CUBO_DB_PUERTO", "0")) or None)
    p.add_argument("--db-usuario", default=e("CUBO_DB_USUARIO", "cubo"))
    p.add_argument("--db-password", default=e("CUBO_DB_PASSWORD", "cubo123"))
    p.add_argument("--hilos", type=int, default=int(e("CUBO_HILOS", "8")))
    p.add_argument("--nodo", default=e("CUBO_NODO", ""))
    p.add_argument("--cargar-metricas", metavar="CSV", help="vuelca un CSV de entrenamiento a metricas_entrenamiento y sale")
    return p.parse_args()


def ip_local():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return "127.0.0.1"


class Nodo:
    def __init__(self, a):
        self.a = a
        cfg = adaptadores.ROLES[a.rol]
        self.modelo = cfg["modelo"]
        self.nombre = a.nodo or f"{a.rol.lower()}-{socket.gethostname()}"
        self.ad = adaptadores.Adaptador(cfg["motor"], a.db_host, a.db_puerto or cfg["puerto"],
                                        a.db_usuario, a.db_password, cfg["base"])
        self.pool = adaptadores.PoolConexiones(self.ad, a.hilos)
        self.lock = threading.Lock()
        self.procesados = self.errores = self.activos = 0

    def contar(self, campo, delta=1):
        with self.lock:
            setattr(self, campo, getattr(self, campo) + delta)

    # ------------------------------------------------------------ BD2
    def manejar_validacion(self, msg, props, tag):
        self.contar("activos")
        try:
            try:
                resp = validador.validar(self.pool, msg)
            except ValueError as e:  # p. ej. registro duplicado concurrente
                resp = {"valido": False, "motivo": str(e)}
            self.rabbit.responder(props, resp)
            self.rabbit.ack(tag)
            self.contar("procesados")
        except Exception:
            self.contar("errores")
            raise
        finally:
            self.contar("activos", -1)

    # ------------------------------------------------------------ BD1 / BD3
    def manejar_resultado(self, r, props, tag):
        self.contar("activos")
        try:
            self._resultado(r)
            self.rabbit.ack(tag)
            self.contar("procesados")
        except Exception:
            self.contar("errores")
            raise
        finally:
            self.contar("activos", -1)

    def _resultado(self, r):
        ids = r["idSolicitud"]
        base = {"idSolicitud": ids, "nodoBD": self.nombre, "timestampBD": int(time.time() * 1000)}
        if r.get("etiqueta") == "ERROR_FORMATO":
            self._historial(r, "ERROR", "La imagen no se pudo decodificar")
            self.rabbit.publicar(EX_CUBO, "cola_respuestas", {**base, "estado": "ERROR", "codigo": "FORMATO",
                                                              "motivo": "La imagen no se pudo decodificar"})
            return
        v = self.rabbit.rpc(EX_CUBO, "cola_validacion", {
            "tipoValidacion": "IMAGEN", "idSolicitud": ids, "idCliente": r["idCliente"], "modelo": r["modelo"],
            "sha256": r.get("sha256", ""), "formato": r.get("formato", ""), "bytes": r.get("bytes", 0),
            "ancho": r.get("ancho", 0), "alto": r.get("alto", 0), "nodoOrigen": self.a.rol}, timeout=10)
        if not v.get("valido"):
            self._historial(r, "RECHAZADO", v.get("motivo", ""))
            self.rabbit.publicar(EX_CUBO, "cola_respuestas", {**base, "estado": "RECHAZADO", "codigo": "VALIDACION",
                                                              "motivo": v.get("motivo", "")})
            return
        clases = r.get("clases") or []
        probs = r.get("probabilidades") or []
        mapa = {c: round(float(p), 6) for c, p in zip(clases, probs)} if clases else probs
        latencia = base["timestampBD"] - int(r.get("timestampBackend") or base["timestampBD"])
        ad = self.ad
        with self.pool.transaccion() as con:  # una sola transacción: imagen + resultado + historial
            cur = con.cursor()
            cur.execute(ad.insertar_ignorando("imagenes", ["id_solicitud", "id_cliente", "sha256", "formato", "ancho",
                                                           "alto", "bytes", "plataforma"]),
                        (ids, r["idCliente"], r.get("sha256", ""), r.get("formato", ""), r.get("ancho"),
                         r.get("alto"), r.get("bytes"), (r.get("plataforma") or "")[:10]))
            cols = ["id_solicitud", "etiqueta", "indice_clase", "probabilidad", "probabilidades", "version_modelo",
                    "nodo_ia", "tiempo_inferencia_ms", "latencia_total_ms", "id_validacion"]
            marc = ["%s"] * len(cols)
            marc[4] = ad.json()
            cur.execute(ad.insertar_ignorando("resultados", cols, marc),
                        (ids, r["etiqueta"], r.get("indiceClase"), r.get("probabilidad"),
                         json.dumps(mapa, ensure_ascii=False), r.get("versionModelo"), r.get("nodoIA"),
                         r.get("tiempoInferenciaMs"), latencia, v.get("idValidacion")))
            cur.execute(ad.insertar_ignorando("historial_consultas", ["id_cliente", "id_solicitud", "tipo", "estado",
                                                                      "motivo"]),
                        (r["idCliente"], ids, "IMAGEN", "OK", ""))
        self.rabbit.publicar(EX_CUBO, "cola_respuestas", {**base, "estado": "OK", "resultado": r,
                                                          "idValidacion": v.get("idValidacion")})

    def _historial(self, r, estado, motivo):
        with self.pool.transaccion() as con:
            con.cursor().execute(
                self.ad.insertar_ignorando("historial_consultas", ["id_cliente", "id_solicitud", "tipo", "estado",
                                                                   "motivo"]),
                (r["idCliente"], r["idSolicitud"], "IMAGEN", estado, (motivo or "")[:200]))

    def manejar_historial(self, msg, props, tag):
        limite = max(1, min(int(msg.get("limite") or 20), 200))
        with self.pool.transaccion() as con:
            cur = con.cursor()
            cur.execute("SELECT h.id_solicitud, h.estado, h.motivo, h.creado_en, r.etiqueta, r.probabilidad "
                        "FROM historial_consultas h LEFT JOIN resultados r ON r.id_solicitud = h.id_solicitud "
                        "WHERE h.id_cliente = %s ORDER BY h.creado_en DESC LIMIT %s", (msg.get("idCliente"), limite))
            filas = cur.fetchall()
        items = [{"idSolicitud": (f[0] or "").strip(), "modelo": self.modelo, "estado": f[1], "motivo": f[2] or "",
                  "fecha": f[3].isoformat(timespec="seconds") if f[3] else "", "etiqueta": f[4] or "",
                  "probabilidad": float(f[5]) if f[5] is not None else 0.0} for f in filas]
        self.rabbit.responder(props, {"modelo": self.modelo, "items": items})
        self.rabbit.ack(tag)

    # ------------------------------------------------------------ administración
    def manejar_admin(self, msg, props, tag):
        """Responde por RabbitMQ los datos que necesita verificar_consistencia.py,
        para poder verificar aunque la base esté detrás de un router (sin
        conexiones entrantes)."""
        try:
            if msg.get("tipo") != "CONSISTENCIA":
                self.rabbit.responder(props, {"error": f"tipo desconocido: {msg.get('tipo')}"})
                return
            with self.pool.transaccion() as con:
                cur = con.cursor()
                if self.a.rol == "BD2":
                    cur.execute("SELECT id_solicitud FROM auditoria_validaciones "
                                "WHERE tipo = 'IMAGEN' AND valido AND id_solicitud IS NOT NULL")
                    validas = [f[0].strip() for f in cur.fetchall()]
                    cur.execute("SELECT id_cliente, estado FROM clientes")
                    clientes = {str(f[0]): f[1] for f in cur.fetchall()}
                    datos = {"validas": validas, "clientes": clientes}
                else:
                    cur.execute("SELECT id_solicitud FROM resultados")
                    resultados = [f[0].strip() for f in cur.fetchall()]
                    cur.execute("SELECT DISTINCT id_cliente FROM imagenes")
                    clientes = [f[0] for f in cur.fetchall()]
                    cur.execute("SELECT estado, COUNT(*) FROM historial_consultas GROUP BY estado")
                    historial = {f[0]: f[1] for f in cur.fetchall()}
                    datos = {"resultados": resultados, "clientes": clientes, "historial": historial}
            self.rabbit.responder(props, {"rol": self.a.rol, "nodo": self.nombre, **datos})
        finally:
            self.rabbit.ack(tag)

    # ------------------------------------------------------------ control
    def medir_rtt(self):
        """Ida y vuelta nodo → RabbitMQ → nodo, en ms (−1 si no responde)."""
        t0 = time.perf_counter()
        try:
            self.rabbit.rpc("", self.rabbit.cola_rpc, {"ping": 1}, timeout=5)
            return round((time.perf_counter() - t0) * 1000, 1)
        except Exception:
            return -1

    def control(self):
        med = Medidor()
        ip = ip_local()
        cpu_modelo = platform.processor() or platform.machine()
        rtt = -1
        n = 0
        while True:
            time.sleep(1)
            ahora = int(time.time() * 1000)
            if n % 5 == 0:
                rtt = self.medir_rtt()
                self.rabbit.publicar(EX_CONTROL, f"heartbeat.{self.nombre}", {
                    "nodo": self.nombre, "rol": self.a.rol, "lenguaje": "Python", "so": platform.system().lower(),
                    "ip": ip, "hilos": self.a.hilos, "cpu": cpu_modelo, "nucleos": os.cpu_count(),
                    "timestamp": ahora}, persistente=False)
            n += 1
            cpu, mem = med.muestra()
            with self.lock:
                p, er, ac = self.procesados, self.errores, self.activos
            self.rabbit.publicar(EX_CONTROL, f"metricas.{self.nombre}", {
                "nodo": self.nombre, "rol": self.a.rol, "cpuPct": round(cpu, 2), "memMB": round(mem, 2),
                "hilosActivos": ac, "procesados": p, "errores": er, "rttMs": rtt, "timestamp": ahora},
                persistente=False)

    def iniciar(self):
        self.rabbit = Rabbit(self.a.rabbit, self.a.hilos)
        if self.a.rol == "BD2":
            self.rabbit.consumir("cola_validacion", self.manejar_validacion)
        else:
            self.rabbit.consumir(f"cola_resultados{self.modelo}", self.manejar_resultado)
            self.rabbit.consumir(f"cola_historial{self.modelo}", self.manejar_historial)
        cola_admin = f"cola_admin_{self.a.rol}"
        self.rabbit.ch.queue_declare(cola_admin, durable=True, arguments={"x-message-ttl": 60000})
        self.rabbit.consumir(cola_admin, self.manejar_admin)
        threading.Thread(target=self.control, daemon=True).start()
        print(f"{self.nombre}: {self.ad.motor} {self.ad.host}:{self.ad.puerto}/{self.ad.base} | "
              f"{self.a.hilos} hilos | esperando mensajes...", flush=True)
        self.rabbit.iniciar()


def cargar_metricas(nodo, ruta):
    with open(ruta, newline="", encoding="utf-8") as f:
        filas = list(csv.DictReader(f))
    with nodo.pool.transaccion() as con:
        cur = con.cursor()
        for r in filas:
            cur.execute("INSERT INTO metricas_entrenamiento (modo, epoca, loss_train, acc_train, loss_val, acc_val, "
                        "tiempo_epoca_s) VALUES (%s, %s, %s, %s, %s, %s, %s)",
                        (r["modo"], int(r["epoca"]), float(r["loss_train"]), float(r["acc_train"]),
                         float(r["loss_val"]), float(r["acc_val"]), float(r["tiempo_epoca_s"])))
    print(f"{len(filas)} filas cargadas en metricas_entrenamiento de {nodo.a.rol}")


def main():
    a = args()
    if a.cargar_metricas and a.rol == "BD2":
        sys.exit("--cargar-metricas solo aplica a BD1 o BD3")
    nodo = Nodo(a)
    if a.cargar_metricas:
        cargar_metricas(nodo, a.cargar_metricas)
        return
    try:
        nodo.iniciar()
    except KeyboardInterrupt:
        print("detenido")


if __name__ == "__main__":
    main()
