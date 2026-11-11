"""Lógica de validación de BD2 (MariaDB): cliente, producto (modelo) e imagen.
Cada función se ejecuta dentro de UNA transacción y registra la validación en
auditoria_validaciones. El control de cuota usa SELECT ... FOR UPDATE para que
dos hilos no aprueben más solicitudes de las permitidas."""
import hashlib
import re
import secrets

USUARIO_VALIDO = re.compile(r"^[A-Za-z0-9_.-]{3,50}$")


def _auditar(cur, tipo, valido, motivo="", id_cliente=None, id_solicitud=None, nodo=None):
    cur.execute("INSERT INTO auditoria_validaciones (tipo, id_cliente, id_solicitud, nodo_origen, valido, motivo) "
                "VALUES (%s, %s, %s, %s, %s, %s)", (tipo, id_cliente, id_solicitud, nodo, valido, motivo[:200]))
    return cur.lastrowid


def _hash(salt, password):
    return hashlib.sha256((salt + password).encode("utf-8")).hexdigest()


def validar(pool, msg):
    tipo = msg.get("tipoValidacion")
    with pool.transaccion() as con:
        cur = con.cursor()
        if tipo == "LOGIN":
            return _login(cur, msg)
        if tipo == "REGISTRO":
            return _registro(cur, msg)
        if tipo == "SOLICITUD":
            return _solicitud(cur, msg)
        if tipo == "IMAGEN":
            return _imagen(cur, msg)
    return {"valido": False, "motivo": f"tipo de validación desconocido: {tipo}"}


def _login(cur, msg):
    usuario, password = msg.get("usuario", ""), msg.get("password", "")
    cur.execute("SELECT id_cliente, password_hash, salt, nombre, estado FROM clientes WHERE usuario = %s", (usuario,))
    fila = cur.fetchone()
    if fila is None or _hash(fila[2], password) != fila[1]:
        _auditar(cur, "LOGIN", False, "credenciales inválidas", fila[0] if fila else None)
        return {"valido": False, "motivo": "Credenciales inválidas"}
    if fila[4] != "ACTIVO":
        _auditar(cur, "LOGIN", False, "cliente suspendido", fila[0])
        return {"valido": False, "motivo": "Cliente suspendido"}
    idv = _auditar(cur, "LOGIN", True, "", fila[0])
    return {"valido": True, "motivo": "", "idCliente": fila[0], "nombre": fila[3], "idValidacion": idv}


def _registro(cur, msg):
    usuario, password = msg.get("usuario", ""), msg.get("password", "")
    nombre = (msg.get("nombre") or usuario)[:100]
    if not USUARIO_VALIDO.match(usuario) or len(password) < 4:
        _auditar(cur, "REGISTRO", False, "usuario o contraseña con formato inválido")
        return {"valido": False, "motivo": "Usuario (3-50 caracteres) o contraseña (mín. 4) inválidos"}
    cur.execute("SELECT id_cliente FROM clientes WHERE usuario = %s", (usuario,))
    if cur.fetchone():
        _auditar(cur, "REGISTRO", False, "usuario ya existe")
        return {"valido": False, "motivo": "El usuario ya existe"}
    salt = secrets.token_hex(16)
    try:
        cur.execute("INSERT INTO clientes (usuario, password_hash, salt, nombre, fecha_cuota) "
                    "VALUES (%s, %s, %s, %s, CURDATE())", (usuario, _hash(salt, password), salt, nombre))
    except Exception as e:  # dos registros simultáneos del mismo usuario: gana uno (UNIQUE)
        if "Duplicate" in str(e):
            raise ValueError("El usuario ya existe") from e
        raise
    id_cliente = cur.lastrowid
    cur.execute("INSERT INTO cliente_modelo (id_cliente, id_modelo) SELECT %s, id_modelo FROM modelos WHERE activo",
                (id_cliente,))
    idv = _auditar(cur, "REGISTRO", True, "", id_cliente)
    return {"valido": True, "motivo": "", "idCliente": id_cliente, "nombre": nombre, "idValidacion": idv}


def _solicitud(cur, msg):
    """Valida cliente activo, modelo habilitado, formato/tamaño y consume cuota (con FOR UPDATE)."""
    id_cliente, modelo = msg.get("idCliente"), msg.get("modelo")
    cur.execute("SELECT estado, cuota_diaria, usadas_hoy, fecha_cuota < CURDATE() FROM clientes "
                "WHERE id_cliente = %s FOR UPDATE", (id_cliente,))
    fila = cur.fetchone()
    motivo, codigo = "", "VALIDACION"
    if fila is None:
        motivo = "Cliente inexistente"
    elif fila[0] != "ACTIVO":
        motivo = "Cliente suspendido"
    else:
        cur.execute("SELECT m.activo, r.formatos_permitidos, r.bytes_max FROM cliente_modelo cm "
                    "JOIN modelos m ON m.id_modelo = cm.id_modelo JOIN reglas_imagen r ON r.id_modelo = m.id_modelo "
                    "WHERE cm.id_cliente = %s AND cm.id_modelo = %s", (id_cliente, modelo))
        prod = cur.fetchone()
        formato = (msg.get("formato") or "").lower().replace("jpeg", "jpg")
        if prod is None or not prod[0]:
            motivo = f"Modelo {modelo} no habilitado para el cliente"
        elif formato not in prod[1].split(","):
            motivo, codigo = f"Formato '{formato}' no permitido", "FORMATO"
        elif int(msg.get("bytes") or 0) > prod[2]:
            motivo, codigo = "Imagen demasiado grande", "FORMATO"
        else:
            usadas = 0 if fila[3] else fila[2]
            if usadas >= fila[1]:
                motivo, codigo = "Cuota diaria excedida", "CUOTA_EXCEDIDA"
            else:
                cur.execute("UPDATE clientes SET usadas_hoy = %s, fecha_cuota = CURDATE() WHERE id_cliente = %s",
                            (usadas + 1, id_cliente))
    valido = motivo == ""
    idv = _auditar(cur, "SOLICITUD", valido, motivo, id_cliente, msg.get("idSolicitud"), msg.get("nodoOrigen", "BACKEND"))
    return {"valido": valido, "motivo": motivo, "codigo": "" if valido else codigo,
            "idCliente": id_cliente, "idValidacion": idv}


def _imagen(cur, msg):
    """Valida la imagen ya procesada por la IA: formato, tamaño, dimensiones y hash bloqueado."""
    id_cliente, modelo = msg.get("idCliente"), msg.get("modelo")
    motivo = ""
    cur.execute("SELECT estado FROM clientes WHERE id_cliente = %s", (id_cliente,))
    cli = cur.fetchone()
    cur.execute("SELECT formatos_permitidos, bytes_max, ancho_min, alto_min FROM reglas_imagen WHERE id_modelo = %s",
                (modelo,))
    regla = cur.fetchone()
    formato = (msg.get("formato") or "").lower().replace("jpeg", "jpg")
    if cli is None or cli[0] != "ACTIVO":
        motivo = "Cliente inexistente o suspendido"
    elif regla is None:
        motivo = f"Modelo {modelo} sin reglas de imagen"
    elif formato not in regla[0].split(","):
        motivo = f"Formato '{formato}' no permitido"
    elif int(msg.get("bytes") or 0) > regla[1]:
        motivo = "Imagen demasiado grande"
    elif int(msg.get("ancho") or 0) < regla[2] or int(msg.get("alto") or 0) < regla[3]:
        motivo = f"Imagen demasiado pequeña (mínimo {regla[2]}x{regla[3]})"
    else:
        cur.execute("SELECT motivo FROM imagenes_bloqueadas WHERE sha256 = %s", (msg.get("sha256", ""),))
        b = cur.fetchone()
        if b:
            motivo = f"Imagen bloqueada: {b[0]}"
    valido = motivo == ""
    idv = _auditar(cur, "IMAGEN", valido, motivo, id_cliente, msg.get("idSolicitud"), msg.get("nodoOrigen"))
    return {"valido": valido, "motivo": motivo, "idCliente": id_cliente, "idValidacion": idv}
