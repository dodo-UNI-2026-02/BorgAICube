package cnn

import (
	"math/rand"
	"sync"
)

// Entrenador hace pasos de SGD con momentum. Cada lote se reparte entre `hilos`
// goroutines; cada una acumula en su PROPIO búfer de gradiente (sin datos
// compartidos que escribir, sin condición de carrera) y al final se suman.
type Entrenador struct {
	Red      *Red
	Hilos    int
	LR, Mom  float32
	vel      []float32
	grads    [][]float32
	trabajos []*Trabajo
	xs       [][]float32
}

func NuevoEntrenador(r *Red, hilos int, lr float32) *Entrenador {
	e := &Entrenador{Red: r, Hilos: max(hilos, 1), LR: lr, Mom: 0.9, vel: make([]float32, len(r.Pesos))}
	for i := 0; i < e.Hilos; i++ {
		e.grads = append(e.grads, make([]float32, len(r.Pesos)))
		e.trabajos = append(e.trabajos, r.NuevoTrabajo())
		e.xs = append(e.xs, make([]float32, r.C*r.H*r.W))
	}
	return e
}

// Paso entrena con un lote (posiciones del dataset). Devuelve pérdida sumada y aciertos.
func (e *Entrenador) Paso(d *Dataset, lote []int) (perdida float64, aciertos int) {
	var wg sync.WaitGroup
	perd := make([]float64, e.Hilos)
	ac := make([]int, e.Hilos)
	for h := 0; h < e.Hilos; h++ {
		wg.Add(1)
		go func(h int) {
			defer wg.Done()
			g := e.grads[h]
			for i := range g {
				g[i] = 0
			}
			for j := h; j < len(lote); j += e.Hilos { // reparto intercalado del lote
				e.Red.Muestra(d, lote[j], e.xs[h])
				p, ok := e.Red.Gradiente(e.trabajos[h], e.xs[h], int(d.Etiq[lote[j]]), g)
				perd[h] += float64(p)
				if ok {
					ac[h]++
				}
			}
		}(h)
	}
	wg.Wait() // barrera: todas las goroutines terminaron su parte del lote
	escala := e.LR / float32(len(lote))
	p := e.Red.Pesos
	for i := range p {
		var s float32
		for h := 0; h < e.Hilos; h++ {
			s += e.grads[h][i]
		}
		e.vel[i] = e.Mom*e.vel[i] - escala*s
		p[i] += e.vel[i]
	}
	for h := 0; h < e.Hilos; h++ {
		perdida += perd[h]
		aciertos += ac[h]
	}
	return
}

// Barajar devuelve una permutación reproducible de idx (misma semilla → mismo orden en todos los nodos).
func Barajar(idx []int, semilla int64) []int {
	r := append([]int(nil), idx...)
	rand.New(rand.NewSource(semilla)).Shuffle(len(r), func(i, j int) { r[i], r[j] = r[j], r[i] })
	return r
}

// Evaluar calcula pérdida media, accuracy y matriz de confusión [real][predicha] en paralelo.
func (r *Red) Evaluar(d *Dataset, idx []int, hilos int) (perdida, acc float64, conf [][]int) {
	hilos = max(hilos, 1)
	confs := make([][][]int, hilos)
	perd := make([]float64, hilos)
	var wg sync.WaitGroup
	for h := 0; h < hilos; h++ {
		confs[h] = matriz(r.K)
		wg.Add(1)
		go func(h int) {
			defer wg.Done()
			t, x := r.NuevoTrabajo(), make([]float32, r.C*r.H*r.W)
			for j := h; j < len(idx); j += hilos {
				r.Muestra(d, idx[j], x)
				copy(t.c.x, x)
				r.forward(t.c)
				y, pred := int(d.Etiq[idx[j]]), 0
				for k := range t.c.out {
					if t.c.out[k] > t.c.out[pred] {
						pred = k
					}
				}
				perd[h] -= ln(t.c.out[y])
				confs[h][y][pred]++
			}
		}(h)
	}
	wg.Wait()
	conf = matriz(r.K)
	bien := 0
	for h := range confs {
		perdida += perd[h]
		for i := range conf {
			for j := range conf {
				conf[i][j] += confs[h][i][j]
			}
			bien += confs[h][i][i]
		}
	}
	n := float64(max(len(idx), 1))
	return perdida / n, float64(bien) / n, conf
}

func matriz(k int) [][]int {
	m := make([][]int, k)
	for i := range m {
		m[i] = make([]int, k)
	}
	return m
}
