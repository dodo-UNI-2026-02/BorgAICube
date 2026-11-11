"""Conexión a RabbitMQ con pika, segura para usar desde un pool de hilos.

pika.BlockingConnection NO es thread-safe. Por eso:
  * el hilo principal es el único que toca el canal (start_consuming);
  * cada mensaje se entrega a un ThreadPoolExecutor;
  * los hilos trabajadores publican y hacen ack/nack agendándolo con
    connection.add_callback_threadsafe(...), que lo ejecuta el hilo principal;
  * las llamadas RPC (p. ej. a cola_validacion) usan una cola de respuesta
    exclusiva; el hilo principal recibe la respuesta y despierta al trabajador
    que la espera mediante un Future.
"""
import concurrent.futures
import functools
import json
import threading
import uuid

import pika

EX_CUBO, EX_HISTORIAL, EX_CONTROL, EX_DLX = "ex_cubo", "ex_historial", "ex_control", "ex_dlx"
COLAS_NEGOCIO = ["cola_modeloA", "cola_modeloB", "cola_resultadosA", "cola_resultadosB", "cola_validacion",
                 "cola_respuestas", "cola_historialA", "cola_historialB", "cola_entrenamiento"]
COLAS_CONTROL = [("cola_heartbeat", "heartbeat.#"), ("cola_metricas", "metricas.#"), ("cola_logs", "logs.#")]
COLA_ERRORES = "cola_errores"


def declarar_topologia(ch):
    """Declaración idempotente, idéntica a la de Go (rmq.go) y Java (PuenteRabbit)."""
    for nombre, tipo in [(EX_CUBO, "direct"), (EX_HISTORIAL, "direct"),
                         (EX_CONTROL, "topic"), (EX_DLX, "fanout")]:
        ch.exchange_declare(nombre, exchange_type=tipo, durable=True)
    for q in COLAS_NEGOCIO:
        ch.queue_declare(q, durable=True, arguments={"x-dead-letter-exchange": EX_DLX})
        ch.queue_bind(q, EX_CUBO, routing_key=q)
    ch.queue_bind("cola_historialA", EX_HISTORIAL, routing_key="A")
    ch.queue_bind("cola_historialB", EX_HISTORIAL, routing_key="B")
    for q, patron in COLAS_CONTROL:
        ch.queue_declare(q, durable=True, arguments={"x-message-ttl": 60000})
        ch.queue_bind(q, EX_CONTROL, routing_key=patron)
    ch.queue_declare(COLA_ERRORES, durable=True)
    ch.queue_bind(COLA_ERRORES, EX_DLX, routing_key="")


class Rabbit:
    def __init__(self, url, hilos):
        params = pika.URLParameters(url)
        params.heartbeat = 60
        params.blocked_connection_timeout = 300
        self.conn = pika.BlockingConnection(params)
        self.ch = self.conn.channel()
        declarar_topologia(self.ch)
        self.ch.basic_qos(prefetch_count=hilos)
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=hilos, thread_name_prefix="bd")
        res = self.ch.queue_declare("", exclusive=True, auto_delete=True)
        self.cola_rpc = res.method.queue
        self._pendientes = {}
        self._lock = threading.Lock()
        self.ch.basic_consume(self.cola_rpc, self._respuesta_rpc, auto_ack=True)

    # ---------- operaciones seguras desde cualquier hilo ----------
    def _agendar(self, fn, *args, **kw):
        self.conn.add_callback_threadsafe(functools.partial(fn, *args, **kw))

    def publicar(self, exchange, routing_key, cuerpo, persistente=True, correlation_id=None, reply_to=None):
        props = pika.BasicProperties(content_type="application/json",
                                     delivery_mode=2 if persistente else 1,
                                     correlation_id=correlation_id, reply_to=reply_to)
        datos = json.dumps(cuerpo, ensure_ascii=False).encode("utf-8")
        self._agendar(self.ch.basic_publish, exchange=exchange, routing_key=routing_key, body=datos, properties=props)

    def ack(self, tag):
        self._agendar(self.ch.basic_ack, delivery_tag=tag)

    def nack(self, tag):
        self._agendar(self.ch.basic_nack, delivery_tag=tag, requeue=False)  # va a ex_dlx → cola_errores

    def responder(self, props, cuerpo):
        if props.reply_to:
            self.publicar("", props.reply_to, cuerpo, persistente=False, correlation_id=props.correlation_id)

    def rpc(self, exchange, routing_key, cuerpo, timeout=10.0):
        """Petición-respuesta. Solo debe llamarse desde hilos trabajadores."""
        cid = str(uuid.uuid4())
        fut = concurrent.futures.Future()
        with self._lock:
            self._pendientes[cid] = fut
        try:
            self.publicar(exchange, routing_key, cuerpo, persistente=False, correlation_id=cid, reply_to=self.cola_rpc)
            return fut.result(timeout=timeout)
        finally:
            with self._lock:
                self._pendientes.pop(cid, None)

    # ---------- solo en el hilo principal ----------
    def _respuesta_rpc(self, ch, metodo, props, cuerpo):
        with self._lock:
            fut = self._pendientes.get(props.correlation_id)
        if fut is not None and not fut.done():
            try:
                fut.set_result(json.loads(cuerpo))
            except Exception as e:
                fut.set_exception(e)

    def consumir(self, cola, manejador):
        """manejador(cuerpo: dict, props) se ejecuta en el pool. Debe hacer ack/nack él mismo."""
        def al_recibir(ch, metodo, props, cuerpo):
            tag = metodo.delivery_tag
            try:
                datos = json.loads(cuerpo)
            except Exception:
                self.ch.basic_nack(tag, requeue=False)
                return
            self.pool.submit(self._ejecutar, manejador, datos, props, tag)
        self.ch.basic_consume(cola, al_recibir, auto_ack=False)

    def _ejecutar(self, manejador, datos, props, tag):
        try:
            manejador(datos, props, tag)
        except Exception as e:  # el mensaje va a la DLX para no perderlo
            print(f"[ERROR] {type(e).__name__}: {e}", flush=True)
            self.nack(tag)

    def iniciar(self):
        self.ch.start_consuming()
