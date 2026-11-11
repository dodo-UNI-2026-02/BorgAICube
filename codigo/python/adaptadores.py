"""Adaptadores para los tres motores (PostgreSQL, MySQL y MariaDB) y un pool de
conexiones propio: una conexión por hilo trabajador, nunca compartida."""
import contextlib
import queue
import threading

import psycopg2
import pymysql

# Configuración por rol
ROLES = {
    "BD1": {"motor": "postgresql", "base": "cubo_modelo_a", "puerto": 5432, "modelo": "A"},
    "BD2": {"motor": "mariadb", "base": "cubo_validacion", "puerto": 3306, "modelo": None},
    "BD3": {"motor": "mysql", "base": "cubo_modelo_b", "puerto": 3306, "modelo": "B"},
}


class Adaptador:
    def __init__(self, motor, host, puerto, usuario, password, base):
        self.motor, self.host, self.puerto = motor, host, int(puerto)
        self.usuario, self.password, self.base = usuario, password, base

    @property
    def es_postgres(self):
        return self.motor == "postgresql"

    def conectar(self):
        if self.es_postgres:
            c = psycopg2.connect(host=self.host, port=self.puerto, user=self.usuario,
                                 password=self.password, dbname=self.base, connect_timeout=10)
            c.autocommit = False
            return c
        return pymysql.connect(host=self.host, port=self.puerto, user=self.usuario, password=self.password,
                               database=self.base, charset="utf8mb4", autocommit=False, connect_timeout=10)

    # --- diferencias de dialecto ---
    def insertar_ignorando(self, tabla, columnas, marcadores=None):
        """INSERT idempotente: ON CONFLICT DO NOTHING (PostgreSQL) / INSERT IGNORE (MySQL/MariaDB)."""
        cols = ", ".join(columnas)
        vals = ", ".join(marcadores or ["%s"] * len(columnas))
        if self.es_postgres:
            return f"INSERT INTO {tabla} ({cols}) VALUES ({vals}) ON CONFLICT DO NOTHING"
        return f"INSERT IGNORE INTO {tabla} ({cols}) VALUES ({vals})"

    def json(self):
        """Marcador para insertar JSON: JSONB en PostgreSQL, JSON en MySQL."""
        return "%s::jsonb" if self.es_postgres else "CAST(%s AS JSON)"


class PoolConexiones:
    """Pool con una cola de conexiones. Cada hilo toma una conexión, la usa en
    exclusiva dentro de una transacción y la devuelve; si falla se hace rollback
    y, si la conexión quedó rota, se reemplaza."""

    def __init__(self, adaptador, tamano):
        self.adaptador = adaptador
        self._libres = queue.Queue()
        self._lock = threading.Lock()
        for _ in range(tamano):
            self._libres.put(adaptador.conectar())

    @contextlib.contextmanager
    def transaccion(self):
        con = self._libres.get()
        try:
            yield con
            con.commit()
        except Exception:
            try:
                con.rollback()
            except Exception:
                try:
                    con.close()
                except Exception:
                    pass
                con = self.adaptador.conectar()
            raise
        finally:
            self._libres.put(con)

    def cerrar(self):
        while not self._libres.empty():
            try:
                self._libres.get_nowait().close()
            except Exception:
                pass
