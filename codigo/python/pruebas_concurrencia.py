"""Pruebas de concurrencia sobre las bases reales (no necesitan RabbitMQ):

 1. 20 hilos insertan el MISMO id_solicitud en BD1 y BD3 → debe quedar 1 fila
    por tabla (idempotencia con UNIQUE + ON CONFLICT / INSERT IGNORE).
 2. 50 hilos piden validación SOLICITUD para un cliente con cuota 10 en BD2 →
    deben aprobarse exactamente 10 (SELECT ... FOR UPDATE).
 3. 20 hilos registran el mismo usuario nuevo en BD2 → se crea exactamente 1.

Uso: python pruebas_concurrencia.py --host-bd1 IP --host-bd2 IP --host-bd3 IP
"""
import argparse
import os
import sys
import threading
import uuid

import adaptadores
import validador


def pool_de(rol, host, puerto, a, n):
    cfg = adaptadores.ROLES[rol]
    ad = adaptadores.Adaptador(cfg["motor"], host, puerto or cfg["puerto"], a.usuario, a.password, cfg["base"])
    return ad, adaptadores.PoolConexiones(ad, n)


def en_paralelo(n, fn):
    barrera = threading.Barrier(n)
    resultados, errores = [], []
    lock = threading.Lock()

    def tarea(i):
        barrera.wait()  # todos arrancan a la vez para forzar la carrera
        try:
            r = fn(i)
            with lock:
                resultados.append(r)
        except Exception as e:
            with lock:
                errores.append(e)
    hilos = [threading.Thread(target=tarea, args=(i,)) for i in range(n)]
    for h in hilos:
        h.start()
    for h in hilos:
        h.join()
    return resultados, errores


def prueba_idempotencia(rol, host, puerto, a):
    ad, pool = pool_de(rol, host, puerto, a, 20)
    ids = str(uuid.uuid4())

    def insertar(i):
        with pool.transaccion() as con:
            cur = con.cursor()
            cur.execute(ad.insertar_ignorando("imagenes", ["id_solicitud", "id_cliente", "sha256", "formato"]),
                        (ids, 999999, "0" * 64, "png"))
            marc = ["%s", "%s", "%s", ad.json()]
            cur.execute(ad.insertar_ignorando("resultados", ["id_solicitud", "etiqueta", "probabilidad",
                                                             "probabilidades"], marc), (ids, "prueba", 0.5, "{}"))
            cur.execute(ad.insertar_ignorando("historial_consultas", ["id_cliente", "id_solicitud", "tipo", "estado"]),
                        (999999, ids, "IMAGEN", "OK"))
    _, errores = en_paralelo(20, insertar)
    with pool.transaccion() as con:
        cur = con.cursor()
        conteos = []
        for t in ("imagenes", "resultados", "historial_consultas"):
            cur.execute(f"SELECT COUNT(*) FROM {t} WHERE id_solicitud = %s", (ids,))
            conteos.append(cur.fetchone()[0])
        cur.execute("DELETE FROM historial_consultas WHERE id_solicitud = %s", (ids,))
        cur.execute("DELETE FROM resultados WHERE id_solicitud = %s", (ids,))
        cur.execute("DELETE FROM imagenes WHERE id_solicitud = %s", (ids,))
    pool.cerrar()
    ok = conteos == [1, 1, 1] and not errores
    print(f"[{'OK ' if ok else 'FALLA'}] {rol}: 20 hilos con el mismo id_solicitud → filas por tabla {conteos}, "
          f"errores {len(errores)}")
    return ok


def prueba_cuota(host, puerto, a):
    ad, pool = pool_de("BD2", host, puerto, a, 50)
    usuario = "prueba_cuota_" + uuid.uuid4().hex[:8]
    with pool.transaccion() as con:
        cur = con.cursor()
        cur.execute("INSERT INTO clientes (usuario, password_hash, salt, nombre, cuota_diaria, fecha_cuota) "
                    "VALUES (%s, %s, %s, %s, 10, CURDATE())", (usuario, "0" * 64, "0" * 32, "Prueba cuota"))
        idc = cur.lastrowid
        cur.execute("INSERT INTO cliente_modelo VALUES (%s, 'A')", (idc,))
    msg = {"tipoValidacion": "SOLICITUD", "idCliente": idc, "modelo": "A", "formato": "png", "bytes": 1000}
    res, errores = en_paralelo(50, lambda i: validador.validar(pool, dict(msg)))
    aprobadas = sum(1 for r in res if r["valido"])
    with pool.transaccion() as con:
        cur = con.cursor()
        cur.execute("SELECT usadas_hoy FROM clientes WHERE id_cliente = %s", (idc,))
        usadas = cur.fetchone()[0]
        cur.execute("DELETE FROM auditoria_validaciones WHERE id_cliente = %s", (idc,))
        cur.execute("DELETE FROM cliente_modelo WHERE id_cliente = %s", (idc,))
        cur.execute("DELETE FROM clientes WHERE id_cliente = %s", (idc,))
    pool.cerrar()
    ok = aprobadas == 10 and usadas == 10 and not errores
    print(f"[{'OK ' if ok else 'FALLA'}] BD2: 50 hilos contra cuota 10 → aprobadas {aprobadas}, usadas_hoy {usadas}, "
          f"errores {len(errores)}")
    return ok


def prueba_registro(host, puerto, a):
    ad, pool = pool_de("BD2", host, puerto, a, 20)
    usuario = "prueba_reg_" + uuid.uuid4().hex[:8]
    msg = {"tipoValidacion": "REGISTRO", "usuario": usuario, "password": "clave123", "nombre": "Prueba"}

    def registrar(i):
        try:
            return validador.validar(pool, dict(msg))
        except ValueError as e:
            return {"valido": False, "motivo": str(e)}
    res, errores = en_paralelo(20, registrar)
    creados = sum(1 for r in res if r["valido"])
    with pool.transaccion() as con:
        cur = con.cursor()
        cur.execute("SELECT id_cliente FROM clientes WHERE usuario = %s", (usuario,))
        filas = cur.fetchall()
        for (idc,) in filas:
            cur.execute("DELETE FROM auditoria_validaciones WHERE id_cliente = %s", (idc,))
            cur.execute("DELETE FROM cliente_modelo WHERE id_cliente = %s", (idc,))
            cur.execute("DELETE FROM clientes WHERE id_cliente = %s", (idc,))
    pool.cerrar()
    ok = creados == 1 and len(filas) == 1 and not errores
    print(f"[{'OK ' if ok else 'FALLA'}] BD2: 20 hilos registran el mismo usuario → creados {creados}, "
          f"filas {len(filas)}, errores {len(errores)}")
    return ok


def main():
    e = os.environ.get
    p = argparse.ArgumentParser()
    p.add_argument("--host-bd1", default=e("CUBO_BD1_HOST", "localhost"))
    p.add_argument("--puerto-bd1", type=int, default=None)
    p.add_argument("--host-bd2", default=e("CUBO_BD2_HOST", "localhost"))
    p.add_argument("--puerto-bd2", type=int, default=None)
    p.add_argument("--host-bd3", default=e("CUBO_BD3_HOST", "localhost"))
    p.add_argument("--puerto-bd3", type=int, default=None)
    p.add_argument("--usuario", default="cubo")
    p.add_argument("--password", default="cubo123")
    a = p.parse_args()
    ok = all([prueba_idempotencia("BD1", a.host_bd1, a.puerto_bd1, a),
              prueba_idempotencia("BD3", a.host_bd3, a.puerto_bd3, a),
              prueba_cuota(a.host_bd2, a.puerto_bd2, a),
              prueba_registro(a.host_bd2, a.puerto_bd2, a)])
    print("TODAS LAS PRUEBAS PASARON" if ok else "HAY PRUEBAS QUE FALLARON")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
