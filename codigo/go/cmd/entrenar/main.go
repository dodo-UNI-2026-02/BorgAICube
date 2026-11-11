// entrenar: entrena y prueba la CNN de un modelo en una de las 4 configuraciones.
//
//	seq      1 proceso, 1 hilo
//	par      1 máquina, H hilos
//	dist     M nodos IA (por RabbitMQ), 1 hilo cada uno
//	pardist  M nodos IA, todos los núcleos de cada uno
//
// Ejemplo: entrenar -modelo A -modo par -hilos 8 -epocas 5
package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"cubo/ia/cnn"
	"cubo/ia/distrib"
	"cubo/ia/rmq"

	amqp "github.com/rabbitmq/amqp091-go"
)

var Clases = map[string][]string{
	"A": {"parasitada", "no-infectada"},
	"B": {"basofilo", "eosinofilo", "eritroblasto", "granulocito-inmaduro", "linfocito", "monocito", "neutrofilo", "plaqueta"},
}
var Archivos = map[string]string{"A": "malaria_32.bin", "B": "blood_28.bin"}

func main() {
	modelo := flag.String("modelo", "A", "A (malaria) o B (células sanguíneas)")
	modo := flag.String("modo", "par", "seq | par | dist | pardist")
	hilos := flag.Int("hilos", runtime.NumCPU(), "hilos en modo par")
	workers := flag.Int("workers", 2, "nodos IA en dist/pardist")
	sinc := flag.Int("sinc", 10, "pasos locales por ronda en dist/pardist")
	epocas := flag.Int("epocas", 5, "épocas")
	lote := flag.Int("lote", 32, "tamaño de lote")
	lr := flag.Float64("lr", 0.01, "tasa de aprendizaje")
	datos := flag.String("datos", "../datos", "carpeta de los datasets")
	pesos := flag.String("pesos", "", "dónde guardar el modelo (por defecto datos/modelo_<M>.gob)")
	res := flag.String("resultados", "../resultados", "carpeta de salida de los CSV")
	sufijo := flag.String("sufijo", "", "sufijo de los CSV (ej. _r1)")
	flag.Parse()
	if *pesos == "" {
		*pesos = filepath.Join(*datos, "modelo_"+*modelo+".gob")
	}
	if *modo == "seq" {
		*hilos = 1
	}
	os.MkdirAll(*res, 0o755)

	d, err := cnn.CargarDataset(filepath.Join(*datos, Archivos[*modelo]))
	if err != nil {
		log.Fatal(err)
	}
	red := cnn.Nueva(d.C, d.H, d.W, d.K, Clases[*modelo], 42)
	red.Modelo = *modelo
	red.Media, red.Desv = d.MediaDesv()
	train, val, test := d.Indices(0), d.Indices(1), d.Indices(2)
	fmt.Printf("Modelo %s: %d entrenamiento, %d validación, %d prueba | %d parámetros | modo %s\n",
		*modelo, len(train), len(val), len(test), red.NumPesos(), *modo)

	var coord *distrib.Coordinador
	maquinas, hilosMaq := 1, *hilos
	if *modo == "dist" || *modo == "pardist" {
		conn, err := amqp.Dial(rmq.URL())
		if err != nil {
			log.Fatal(err)
		}
		defer conn.Close()
		if coord, err = distrib.NuevoCoordinador(conn); err != nil {
			log.Fatal(err)
		}
		maquinas, hilosMaq = *workers, 1
	}
	ent := cnn.NuevoEntrenador(red, *hilos, float32(*lr))

	csv := filepath.Join(*res, fmt.Sprintf("entrenamiento_%s_%s%s.csv", *modelo, *modo, *sufijo))
	filas := []string{"epoca,loss_train,acc_train,loss_val,acc_val,tiempo_epoca_s"}
	total := 0.0
	for ep := 1; ep <= *epocas; ep++ {
		orden := cnn.Barajar(train, int64(ep))
		t0 := time.Now()
		var perd float64
		var ac int
		if coord == nil {
			for i := 0; i < len(orden); i += *lote {
				p, a := ent.Paso(d, orden[i:min(i+*lote, len(orden))])
				perd += p
				ac += a
			}
		} else {
			hn := 1
			if *modo == "pardist" {
				hn = 0 // cada nodo usa todos sus núcleos
			}
			parte := (len(orden) + maquinas - 1) / maquinas
			porRonda := *sinc * *lote
			for ini := 0; ini < parte; ini += porRonda {
				var partes [][]int
				for m := 0; m < maquinas; m++ {
					a, b := min(m*parte+ini, len(orden)), min(m*parte+min(ini+porRonda, parte), len(orden))
					if a < b {
						partes = append(partes, orden[a:b])
					}
				}
				p, a, _, err := coord.Ronda(red, d.N, partes, *lote, float32(*lr), hn)
				if err != nil {
					log.Fatal(err)
				}
				perd += p
				ac += a
			}
		}
		seg := time.Since(t0).Seconds()
		total += seg
		lv, av, _ := red.Evaluar(d, val, runtime.NumCPU())
		fmt.Printf("época %d: loss %.4f acc %.4f | val loss %.4f acc %.4f | %.1f s\n",
			ep, perd/float64(len(orden)), float64(ac)/float64(len(orden)), lv, av, seg)
		filas = append(filas, fmt.Sprintf("%d,%.5f,%.5f,%.5f,%.5f,%.3f", ep, perd/float64(len(orden)),
			float64(ac)/float64(len(orden)), lv, av, seg))
	}
	os.WriteFile(csv, []byte(strings.Join(filas, "\n")+"\n"), 0o644)

	_, at, conf := red.Evaluar(d, test, runtime.NumCPU())
	fmt.Printf("Prueba: accuracy %.4f\n", at)
	cf := []string{"real\\predicha," + strings.Join(red.Clases, ",")}
	for i, fila := range conf {
		s := red.Clases[i]
		for _, v := range fila {
			s += "," + strconv.Itoa(v)
		}
		cf = append(cf, s)
	}
	os.WriteFile(filepath.Join(*res, fmt.Sprintf("confusion_%s_%s%s.csv", *modelo, *modo, *sufijo)),
		[]byte(strings.Join(cf, "\n")+"\n"), 0o644)
	if coord != nil {
		for n, h := range coord.Nodos {
			fmt.Printf("  nodo %s usó %d hilos\n", n, h)
			hilosMaq = h
		}
	}

	red.Version = fmt.Sprintf("%s-%s-%s", *modelo, *modo, time.Now().Format("20060102-1504"))
	if err := red.Guardar(*pesos); err != nil {
		log.Fatal(err)
	}
	fmt.Println("Modelo guardado en", *pesos)
	benchmark(*res, *modelo, *modo, maquinas, hilosMaq, *epocas, total/float64(*epocas), at)
}

// benchmark agrega una fila a benchmark_entrenamiento.csv con speedup = t_seq / t_modo.
func benchmark(dir, modelo, modo string, maq, hilos, epocas int, tEpoca, acc float64) {
	ruta := filepath.Join(dir, "benchmark_entrenamiento.csv")
	b, _ := os.ReadFile(ruta)
	lineas := strings.Split(strings.TrimSpace(string(b)), "\n")
	if len(b) == 0 {
		lineas = []string{"fecha,modelo,modo,maquinas,hilos_por_maquina,epocas,tiempo_medio_epoca_s,acc_test,speedup,eficiencia"}
	}
	tSeq := 0.0
	if modo == "seq" {
		tSeq = tEpoca
	}
	for _, l := range lineas[1:] { // último seq registrado del mismo modelo
		c := strings.Split(l, ",")
		if c[1] == modelo && c[2] == "seq" {
			tSeq, _ = strconv.ParseFloat(c[6], 64)
		}
	}
	sp, ef := "", ""
	if tSeq > 0 {
		s := tSeq / tEpoca
		sp, ef = fmt.Sprintf("%.3f", s), fmt.Sprintf("%.3f", s/float64(maq*hilos))
	}
	lineas = append(lineas, fmt.Sprintf("%s,%s,%s,%d,%d,%d,%.3f,%.4f,%s,%s", time.Now().Format(time.RFC3339),
		modelo, modo, maq, hilos, epocas, tEpoca, acc, sp, ef))
	os.WriteFile(ruta, []byte(strings.Join(lineas, "\n")+"\n"), 0o644)
}
