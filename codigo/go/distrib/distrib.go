// Paquete distrib: entrenamiento distribuido por RabbitMQ (promedio de modelos).
//
// En cada ronda el coordinador publica en cola_entrenamiento una tarea por nodo
// (pesos actuales + las posiciones del dataset que le tocan). Cada nodo hace K
// pasos de SGD sobre su parte con 1 hilo (modo dist) o con todos sus núcleos
// (modo pardist) y devuelve sus pesos; el coordinador los promedia.
package distrib

import (
	"bytes"
	"encoding/gob"
	"fmt"
	"runtime"
	"time"

	"cubo/ia/cnn"
	"cubo/ia/rmq"

	amqp "github.com/rabbitmq/amqp091-go"
)

type Tarea struct {
	ID      string
	N       int // tamaño del dataset: todos los nodos deben tener el mismo .bin
	Red     *cnn.Red
	Indices []int
	Lote    int
	LR      float32
	Hilos   int // 0 = todos los núcleos del nodo
}

type Resultado struct {
	ID, Nodo, Err      string
	Pesos              []float32
	Perdida            float64
	Aciertos, Muestras int
	Hilos              int
}

func codificar(v any) []byte {
	var b bytes.Buffer
	gob.NewEncoder(&b).Encode(v)
	return b.Bytes()
}

// Atender: lado del nodo. Consume tareas de entrenamiento de una en una.
// datasets: modelo ("A"/"B") → dataset cargado.
func Atender(conn *amqp.Connection, nodo string, datasets map[string]*cnn.Dataset) error {
	ch, err := conn.Channel()
	if err != nil {
		return err
	}
	ch.Qos(1, 0, false) // una tarea a la vez por nodo
	msgs, err := ch.Consume(rmq.ColaEntrenamiento, "", false, false, false, false, nil)
	if err != nil {
		return err
	}
	for m := range msgs {
		var t Tarea
		res := Resultado{Nodo: nodo}
		if err := gob.NewDecoder(bytes.NewReader(m.Body)).Decode(&t); err != nil {
			res.Err = err.Error()
		} else {
			res.ID = t.ID
			d := datasets[t.Red.Modelo]
			if d == nil || d.N != t.N {
				res.Err = "dataset distinto o ausente en " + nodo
			} else {
				hilos := t.Hilos
				if hilos <= 0 {
					hilos = runtime.NumCPU()
				}
				e := cnn.NuevoEntrenador(t.Red, hilos, t.LR)
				for i := 0; i < len(t.Indices); i += t.Lote {
					p, a := e.Paso(d, t.Indices[i:min(i+t.Lote, len(t.Indices))])
					res.Perdida += p
					res.Aciertos += a
				}
				res.Pesos, res.Muestras, res.Hilos = t.Red.Pesos, len(t.Indices), hilos
			}
		}
		ch.Publish("", m.ReplyTo, false, false, amqp.Publishing{CorrelationId: m.CorrelationId, Body: codificar(res)})
		m.Ack(false)
	}
	return fmt.Errorf("canal de entrenamiento cerrado")
}

// Coordinador: lado del que entrena. Envía las tareas de una ronda y promedia.
type Coordinador struct {
	ch     *amqp.Channel
	cola   string
	llegan <-chan amqp.Delivery
	ronda  int
	Nodos  map[string]int // nodo → hilos usados (para el informe)
}

func NuevoCoordinador(conn *amqp.Connection) (*Coordinador, error) {
	ch, err := conn.Channel()
	if err != nil {
		return nil, err
	}
	if err := rmq.Declarar(ch); err != nil {
		return nil, err
	}
	q, err := ch.QueueDeclare("", false, true, true, false, nil) // cola privada de respuestas
	if err != nil {
		return nil, err
	}
	llegan, err := ch.Consume(q.Name, "", true, true, false, false, nil)
	return &Coordinador{ch: ch, cola: q.Name, llegan: llegan, Nodos: map[string]int{}}, err
}

// Ronda reparte partes[i] al nodo i, espera las M respuestas y deja en red los pesos promediados.
func (c *Coordinador) Ronda(red *cnn.Red, n int, partes [][]int, lote int, lr float32, hilos int) (perdida float64, aciertos, muestras int, err error) {
	c.ronda++
	pend := map[string]bool{}
	for i, p := range partes {
		id := fmt.Sprintf("r%d-%d", c.ronda, i)
		pend[id] = true
		cuerpo := codificar(Tarea{ID: id, N: n, Red: red, Indices: p, Lote: lote, LR: lr, Hilos: hilos})
		if err = c.ch.Publish(rmq.ExCubo, rmq.ColaEntrenamiento, false, false,
			amqp.Publishing{CorrelationId: id, ReplyTo: c.cola, Body: cuerpo}); err != nil {
			return
		}
	}
	suma := make([]float64, len(red.Pesos))
	limite := time.After(10 * time.Minute)
	for len(pend) > 0 {
		select {
		case m := <-c.llegan:
			if !pend[m.CorrelationId] {
				continue // respuesta de una ronda anterior
			}
			var r Resultado
			if err = gob.NewDecoder(bytes.NewReader(m.Body)).Decode(&r); err != nil {
				return
			}
			if r.Err != "" {
				return 0, 0, 0, fmt.Errorf("%s", r.Err)
			}
			delete(pend, r.ID)
			c.Nodos[r.Nodo] = r.Hilos
			for i, v := range r.Pesos { // promedio ponderado por muestras
				suma[i] += float64(v) * float64(r.Muestras)
			}
			perdida += r.Perdida
			aciertos += r.Aciertos
			muestras += r.Muestras
		case <-limite:
			return 0, 0, 0, fmt.Errorf("ningún nodo respondió (¿hay nodos IA conectados?)")
		}
	}
	for i := range red.Pesos {
		red.Pesos[i] = float32(suma[i] / float64(muestras))
	}
	return
}
