// Paquete rmq: conexión a RabbitMQ, topología (idéntica a Java y Python) y
// mensajes de control (heartbeat, métricas y logs) de los nodos Go.
package rmq

import (
	"encoding/json"
	"net"
	"os"
	"runtime"
	"sync"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

const (
	ExCubo, ExHistorial, ExControl, ExDLX = "ex_cubo", "ex_historial", "ex_control", "ex_dlx"
	ColaEntrenamiento                     = "cola_entrenamiento"
)

var colasNegocio = []string{"cola_modeloA", "cola_modeloB", "cola_resultadosA", "cola_resultadosB",
	"cola_validacion", "cola_respuestas", "cola_historialA", "cola_historialB", ColaEntrenamiento}

func URL() string {
	if u := os.Getenv("CUBO_RABBIT_URL"); u != "" {
		return u
	}
	return "amqp://cubo:cubo123@localhost:5672/cubo"
}

// Declarar crea exchanges y colas de forma idempotente (mismos argumentos que PuenteRabbit.java).
func Declarar(ch *amqp.Channel) error {
	for _, e := range [][2]string{{ExCubo, "direct"}, {ExHistorial, "direct"}, {ExControl, "topic"}, {ExDLX, "fanout"}} {
		if err := ch.ExchangeDeclare(e[0], e[1], true, false, false, false, nil); err != nil {
			return err
		}
	}
	for _, q := range colasNegocio {
		if _, err := ch.QueueDeclare(q, true, false, false, false, amqp.Table{"x-dead-letter-exchange": ExDLX}); err != nil {
			return err
		}
		if err := ch.QueueBind(q, q, ExCubo, false, nil); err != nil {
			return err
		}
	}
	return nil
}

// Publicador: un canal AMQP no debe usarse desde varias goroutines a la vez,
// así que las publicaciones se serializan con un mutex.
type Publicador struct {
	mu sync.Mutex
	ch *amqp.Channel
}

func NuevoPublicador(c *amqp.Connection) (*Publicador, error) {
	ch, err := c.Channel()
	return &Publicador{ch: ch}, err
}

func (p *Publicador) JSON(ex, clave string, v any, persistente bool) error {
	b, _ := json.Marshal(v)
	modo := amqp.Transient
	if persistente {
		modo = amqp.Persistent
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.ch.Publish(ex, clave, false, false, amqp.Publishing{ContentType: "application/json", DeliveryMode: modo, Body: b})
}

func (p *Publicador) Log(nodo, nivel, msg string) {
	p.JSON(ExControl, "logs."+nodo, map[string]any{"nodo": nodo, "nivel": nivel, "mensaje": msg,
		"timestamp": time.Now().UnixMilli()}, false)
}

// Control publica heartbeat cada 5 s y métricas (CPU %, memoria, contadores) cada 1 s.
func (p *Publicador) Control(nodo, rol string, hilos int, contadores func() map[string]any) {
	ip := IPLocal()
	cpu0, t0 := cpuProceso(), time.Now()
	for n := 0; ; n++ {
		time.Sleep(time.Second)
		ahora := time.Now()
		if n%5 == 0 {
			p.JSON(ExControl, "heartbeat."+nodo, map[string]any{"nodo": nodo, "rol": rol, "lenguaje": "Go",
				"so": runtime.GOOS, "ip": ip, "hilos": hilos, "nucleos": runtime.NumCPU(), "timestamp": ahora.UnixMilli()}, false)
		}
		cpu := cpuProceso()
		pct := (cpu - cpu0).Seconds() / (ahora.Sub(t0).Seconds() * float64(runtime.NumCPU())) * 100
		cpu0, t0 = cpu, ahora
		var m runtime.MemStats
		runtime.ReadMemStats(&m)
		msg := map[string]any{"nodo": nodo, "rol": rol, "cpuPct": pct, "memMB": float64(m.Sys) / (1 << 20),
			"timestamp": ahora.UnixMilli()}
		for k, v := range contadores() {
			msg[k] = v
		}
		p.JSON(ExControl, "metricas."+nodo, msg, false)
	}
}

func IPLocal() string {
	c, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return "127.0.0.1"
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).IP.String()
}

func NombreNodo(prefijo string) string {
	h, _ := os.Hostname()
	return prefijo + "-" + h + "-" + time.Now().Format("150405")
}
