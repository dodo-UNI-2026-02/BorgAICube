// nodo: Nodo IA. Consume imágenes de cola_modeloA / cola_modeloB, ejecuta la CNN
// y publica el diagnóstico en cola_resultadosA / cola_resultadosB. También atiende
// tareas de entrenamiento distribuido (cola_entrenamiento) y publica heartbeat,
// métricas y logs en ex_control.
//
// Ejemplo: nodo -modelos A,B -hilos 8
package main

import (
	"encoding/base64"
	"encoding/json"
	"flag"
	"log"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"cubo/ia/cnn"
	"cubo/ia/distrib"
	"cubo/ia/rmq"

	amqp "github.com/rabbitmq/amqp091-go"
)

var archivos = map[string]string{"A": "malaria_32.bin", "B": "blood_28.bin"}

var procesados, errores, activos atomic.Int64

func main() {
	modelos := flag.String("modelos", "A,B", "modelos que atiende este nodo")
	hilos := flag.Int("hilos", runtime.NumCPU(), "goroutines de inferencia por modelo")
	procs := flag.Int("gomaxprocs", 0, "núcleos que puede usar el proceso (0 = todos)")
	datos := flag.String("datos", "../datos", "carpeta con modelo_<M>.gob y los datasets")
	sinEntrenar := flag.Bool("sin-entrenamiento", false, "no atender tareas de entrenamiento")
	flag.Parse()
	if *procs > 0 {
		runtime.GOMAXPROCS(*procs)
	}
	nodo := rmq.NombreNodo("ia")

	conn, err := amqp.Dial(rmq.URL())
	if err != nil {
		log.Fatal(err)
	}
	ch, err := conn.Channel()
	if err != nil {
		log.Fatal(err)
	}
	if err := rmq.Declarar(ch); err != nil {
		log.Fatal(err)
	}
	pub, err := rmq.NuevoPublicador(conn)
	if err != nil {
		log.Fatal(err)
	}

	datasets := map[string]*cnn.Dataset{}
	for _, m := range strings.Split(*modelos, ",") {
		red, err := cnn.Cargar(filepath.Join(*datos, "modelo_"+m+".gob"))
		if err != nil {
			log.Fatalf("modelo %s: %v", m, err)
		}
		if err := atender(conn, pub, red, m, nodo, *hilos); err != nil {
			log.Fatal(err)
		}
		log.Printf("[%s] consumiendo cola_modelo%s con %d goroutines (versión %s)", nodo, m, *hilos, red.Version)
		if !*sinEntrenar {
			if d, err := cnn.CargarDataset(filepath.Join(*datos, archivos[m])); err == nil {
				datasets[m] = d
			}
		}
	}
	if len(datasets) > 0 {
		go func() { log.Fatal(distrib.Atender(conn, nodo, datasets)) }()
		log.Printf("[%s] atendiendo tareas de entrenamiento distribuido", nodo)
	}
	pub.Log(nodo, "INFO", "nodo IA iniciado")
	go pub.Control(nodo, "IA", *hilos, func() map[string]any {
		return map[string]any{"procesados": procesados.Load(), "errores": errores.Load(), "hilosActivos": activos.Load()}
	})
	<-conn.NotifyClose(make(chan *amqp.Error, 1))
	log.Fatal("conexión con RabbitMQ cerrada")
}

// atender abre un canal para el modelo con prefetch = hilos y reparte los
// mensajes entre `hilos` goroutines (productor-consumidor sobre un chan de Go).
func atender(conn *amqp.Connection, pub *rmq.Publicador, red *cnn.Red, m, nodo string, hilos int) error {
	ch, err := conn.Channel()
	if err != nil {
		return err
	}
	ch.Qos(hilos, 0, false)
	msgs, err := ch.Consume("cola_modelo"+m, nodo+"-"+m, false, false, false, false, nil)
	if err != nil {
		return err
	}
	var ackMu sync.Mutex // el canal de consumo también se comparte entre goroutines
	for i := 0; i < hilos; i++ {
		go func() {
			for d := range msgs {
				activos.Add(1)
				res := procesar(red, d.Body, m, nodo)
				if err := pub.JSON(rmq.ExCubo, "cola_resultados"+m, res, true); err != nil {
					errores.Add(1)
				}
				ackMu.Lock()
				d.Ack(false)
				ackMu.Unlock()
				activos.Add(-1)
			}
		}()
	}
	return nil
}

func procesar(red *cnn.Red, cuerpo []byte, m, nodo string) map[string]any {
	t0 := time.Now()
	var s map[string]any
	res := map[string]any{"modelo": m, "nodoIA": nodo, "versionModelo": red.Version}
	if err := json.Unmarshal(cuerpo, &s); err != nil {
		errores.Add(1)
		res["etiqueta"] = "ERROR_FORMATO"
		return res
	}
	// se reenvían los datos de la solicitud que necesitan BD2 y BD1/BD3
	for _, k := range []string{"idSolicitud", "idCliente", "plataforma", "formato", "sha256", "bytes", "timestampBackend"} {
		res[k] = s[k]
	}
	img, err := base64.StdEncoding.DecodeString(str(s["imagen"]))
	var x []float32
	var ancho, alto int
	if err == nil {
		x, ancho, alto, err = red.Preparar(img)
	}
	if err != nil {
		errores.Add(1)
		res["etiqueta"] = "ERROR_FORMATO"
		return res
	}
	p := red.Predecir(x)
	mejor := 0
	for k := range p {
		if p[k] > p[mejor] {
			mejor = k
		}
	}
	procesados.Add(1)
	res["etiqueta"], res["indiceClase"], res["probabilidad"] = red.Clases[mejor], mejor, p[mejor]
	res["clases"], res["probabilidades"] = red.Clases, p
	res["ancho"], res["alto"] = ancho, alto
	res["tiempoInferenciaMs"] = time.Since(t0).Milliseconds()
	res["timestampIA"] = time.Now().UnixMilli()
	return res
}

func str(v any) string {
	s, _ := v.(string)
	return s
}
