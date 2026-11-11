"""Valida los cambios en BD1, BD2 y BD3 cruzando las tres bases:

  * toda fila de resultados (BD1/BD3) tiene una validación IMAGEN válida en BD2;
  * ningún id_solicitud aparece a la vez en BD1 y BD3;
  * no hay clientes en BD1/BD3 inexistentes o suspendidos en BD2;
  * no hay id_solicitud duplicados;
  * conteos: enviados (CSV del generador de carga) vs. persistidos vs. rechazados.

Uso:
  python verificar_consistencia.py --host-bd1 IP --host-bd2 IP --host-bd3 IP     (conexión directa a las 3 bases)
  python verificar_consistencia.py --rabbit amqp://usuario:clave@SERVIDOR:5672/cubo
        (pide los datos a los nodos BD por RabbitMQ: sirve aunque las bases estén
         en PCs detrás de un router, sin abrir sus puertos)
  [--carga ../resultados/carga_*.csv]
"""
import argparse
import csv
import glob
import json
import os
import time
import uuid

import adaptadores


def conectar(rol, host, puerto, a):
    cfg = adaptadores.ROLES[rol]
    return adaptadores.Adaptador(cfg["motor"], host, puerto or cfg["puerto"], a.usuario, a.password,
                                 cfg["base"]).conectar()


def consulta(con, sql, params=()):
    cur = con.cursor()
    cur.execute(sql, params)
    filas = cur.fetchall()
    con.commit()
    return filas


def datos_directos(a):
    """Lee los datos conectándose a las 3 bases."""
    bd1 = conectar("BD1", a.host_bd1, a.puerto_bd1, a)
    bd2 = conectar("BD2", a.host_bd2, a.puerto_bd2, a)
    bd3 = conectar("BD3", a.host_bd3, a.puerto_bd3, a)
    d = {"BD2": {
        "validas": [f[0].strip() for f in consulta(bd2, "SELECT id_solicitud FROM auditoria_validaciones "
                                                        "WHERE tipo = 'IMAGEN' AND valido AND id_solicitud IS NOT NULL")],
        "clientes": {str(f[0]): f[1] for f in consulta(bd2, "SELECT id_cliente, estado FROM clientes")}}}
    for rol, con in (("BD1", bd1), ("BD3", bd3)):
        d[rol] = {
            "resultados": [f[0].strip() for f in consulta(con, "SELECT id_solicitud FROM resultados")],
            "clientes": [f[0] for f in consulta(con, "SELECT DISTINCT id_cliente FROM imagenes")],
            "historial": {e: n for e, n in consulta(con, "SELECT estado, COUNT(*) FROM historial_consultas GROUP BY estado")}}
    return d


def datos_por_rabbit(url, timeout=30):
    """Pide a cada nodo BD (cola_admin_BDx) los datos de consistencia por RPC."""
    import pika
    conn = pika.BlockingConnection(pika.URLParameters(url))
    ch = conn.channel()
    cola = ch.queue_declare("", exclusive=True, auto_delete=True).method.queue
    respuestas, cids = {}, {}

    def al_recibir(_ch, _m, props, cuerpo):
        rol = cids.get(props.correlation_id)
        if rol:
            respuestas[rol] = json.loads(cuerpo)

    ch.basic_consume(cola, al_recibir, auto_ack=True)
    for rol in ("BD1", "BD2", "BD3"):
        cid = str(uuid.uuid4())
        cids[cid] = rol
        ch.basic_publish("", f"cola_admin_{rol}", json.dumps({"tipo": "CONSISTENCIA"}).encode(),
                         pika.BasicProperties(reply_to=cola, correlation_id=cid, content_type="application/json"))
    fin = time.time() + timeout
    while len(respuestas) < 3 and time.time() < fin:
        conn.process_data_events(time_limit=0.5)
    conn.close()
    faltan = [r for r in ("BD1", "BD2", "BD3") if r not in respuestas]
    if faltan:
        raise SystemExit(f"No respondieron {', '.join(faltan)} en {timeout} s: ¿están corriendo sus nodo_bd.py?")
    for rol, r in respuestas.items():
        print(f"  datos de {rol} recibidos de {r.get('nodo')}")
    return respuestas


def main():
    e = os.environ.get
    p = argparse.ArgumentParser()
    p.add_argument("--host-bd1", default=e("CUBO_BD1_HOST", "localhost"))
    p.add_argument("--puerto-bd1", type=int)
    p.add_argument("--host-bd2", default=e("CUBO_BD2_HOST", "localhost"))
    p.add_argument("--puerto-bd2", type=int)
    p.add_argument("--host-bd3", default=e("CUBO_BD3_HOST", "localhost"))
    p.add_argument("--puerto-bd3", type=int)
    p.add_argument("--usuario", default="cubo")
    p.add_argument("--password", default="cubo123")
    p.add_argument("--rabbit", default=None, help="URL de RabbitMQ: pedir los datos a los nodos BD (sin conexión directa)")
    p.add_argument("--carga", nargs="*", default=None, help="CSV del generador de carga (por defecto ../resultados/carga_*.csv)")
    p.add_argument("--salida", default="../resultados/consistencia.csv")
    a = p.parse_args()

    d = datos_por_rabbit(a.rabbit) if a.rabbit else datos_directos(a)
    res1, res3 = d["BD1"]["resultados"], d["BD3"]["resultados"]
    validas = set(d["BD2"]["validas"])
    clientes = {int(k): v for k, v in d["BD2"]["clientes"].items()}
    cli1, cli3 = set(d["BD1"]["clientes"]), set(d["BD3"]["clientes"])
    hist = {(rol, estado): n for rol in ("BD1", "BD3") for estado, n in d[rol]["historial"].items()}

    sin_validacion = [i for i in res1 + res3 if i not in validas]
    en_ambas = set(res1) & set(res3)
    cli_malos = [c for c in cli1 | cli3 if clientes.get(c) != "ACTIVO"]
    duplicados = (len(res1) - len(set(res1))) + (len(res3) - len(set(res3)))

    rutas = a.carga if a.carga is not None else glob.glob("../resultados/carga_*.csv")
    # Las filas de REGISTRO (alta de clientes) no tienen idSolicitud ni modelo:
    # no generan resultados en BD1/BD3 y se cuentan aparte.
    enviados = ok_cli = registros = registros_ok = 0
    ids_ok = set()
    for ruta in rutas:
        with open(ruta, newline="", encoding="utf-8") as f:
            for fila in csv.DictReader(f):
                if fila.get("modelo") not in ("A", "B"):
                    registros += 1
                    registros_ok += fila.get("estado") == "OK"
                    continue
                enviados += 1
                if fila.get("estado") == "OK":
                    ok_cli += 1
                    if fila.get("idSolicitud"):
                        ids_ok.add(fila["idSolicitud"])
    persistidos = set(res1) | set(res3)
    ok_sin_persistir = len(ids_ok - persistidos)

    chequeos = [
        ("Resultados en BD1 (PostgreSQL)", len(res1), None),
        ("Resultados en BD3 (MySQL)", len(res3), None),
        ("Validaciones IMAGEN válidas en BD2 (MariaDB)", len(validas), None),
        ("Resultados sin validación válida en BD2", len(sin_validacion), len(sin_validacion) == 0),
        ("id_solicitud presentes en BD1 y BD3 a la vez", len(en_ambas), len(en_ambas) == 0),
        ("Clientes de BD1/BD3 inexistentes o suspendidos en BD2", len(cli_malos), len(cli_malos) == 0),
        ("id_solicitud duplicados", duplicados, duplicados == 0),
        ("Historial OK (BD1 + BD3)", hist.get(("BD1", "OK"), 0) + hist.get(("BD3", "OK"), 0), None),
        ("Historial RECHAZADO (BD1 + BD3)", hist.get(("BD1", "RECHAZADO"), 0) + hist.get(("BD3", "RECHAZADO"), 0), None),
        ("Historial ERROR (BD1 + BD3)", hist.get(("BD1", "ERROR"), 0) + hist.get(("BD3", "ERROR"), 0), None),
    ]
    if rutas:
        chequeos += [("Consultas enviadas (CSV de carga)", enviados, None),
                     ("Consultas OK según los clientes", ok_cli, None),
                     ("Consultas OK de clientes sin resultado en BD1/BD3", ok_sin_persistir, ok_sin_persistir == 0),
                     ("Registros de clientes enviados / OK (CSV de carga)", f"{registros}/{registros_ok}", None),
                     ("Clientes totales en BD2", len(clientes), None)]
    print(f"{'Chequeo':58} {'Valor':>8}  Estado")
    print("-" * 78)
    for nombre, valor, bien in chequeos:
        estado = "" if bien is None else ("OK" if bien else "INCONSISTENTE")
        print(f"{nombre:58} {valor:>8}  {estado}")
    os.makedirs(os.path.dirname(a.salida) or ".", exist_ok=True)
    with open(a.salida, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["chequeo", "valor", "estado"])
        for nombre, valor, bien in chequeos:
            w.writerow([nombre, valor, "" if bien is None else ("OK" if bien else "INCONSISTENTE")])
    todo_ok = all(b for _, _, b in chequeos if b is not None)
    print("\nRESULTADO:", "BD1, BD2 y BD3 CONSISTENTES" if todo_ok else "SE ENCONTRARON INCONSISTENCIAS")
    print("reporte guardado en", a.salida)


if __name__ == "__main__":
    main()
