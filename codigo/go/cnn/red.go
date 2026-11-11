// Paquete cnn: red neuronal convolucional escrita desde cero (sin librerías de IA).
//
// Arquitectura (dos "micro chunks"):
//
//	Convoluciones: conv3x3(C→F1) → ReLU → maxpool2 → conv3x3(F1→F2) → ReLU → maxpool2
//	MLP:           densa(N→D) → ReLU → densa(D→K) → Softmax
//
// Todos los parámetros viven en un solo []float32 (Pesos): así promediarlos
// entre nodos o enviarlos por RabbitMQ es copiar un arreglo.
package cnn

import (
	"encoding/gob"
	"math"
	"math/rand"
	"os"
)

type Red struct {
	C, H, W, K int // entrada C×H×W, K clases
	F1, F2, D  int // filtros de cada convolución y neuronas de la capa oculta
	Pesos      []float32
	Media      []float32 // normalización por canal (calculada con el set de entrenamiento)
	Desv       []float32
	Clases     []string
	Modelo     string // "A" o "B"
	Version    string
}

// desplazamientos de cada bloque de parámetros dentro de Pesos
type offs struct{ w1, b1, w2, b2, w3, b3, w4, b4, total, n3 int }

func (r *Red) offs() offs {
	var o offs
	o.n3 = r.F2 * (r.H / 4) * (r.W / 4)
	o.w1 = 0
	o.b1 = o.w1 + r.F1*r.C*9
	o.w2 = o.b1 + r.F1
	o.b2 = o.w2 + r.F2*r.F1*9
	o.w3 = o.b2 + r.F2
	o.b3 = o.w3 + r.D*o.n3
	o.w4 = o.b3 + r.D
	o.b4 = o.w4 + r.K*r.D
	o.total = o.b4 + r.K
	return o
}

// Nueva crea una red con inicialización He.
func Nueva(c, h, w, k int, clases []string, semilla int64) *Red {
	r := &Red{C: c, H: h, W: w, K: k, F1: 8, F2: 16, D: 64, Clases: clases}
	o := r.offs()
	r.Pesos = make([]float32, o.total)
	rng := rand.New(rand.NewSource(semilla))
	he := func(ini, fin, fanIn int) {
		s := math.Sqrt(2 / float64(fanIn))
		for i := ini; i < fin; i++ {
			r.Pesos[i] = float32(rng.NormFloat64() * s)
		}
	}
	he(o.w1, o.b1, c*9)
	he(o.w2, o.b2, r.F1*9)
	he(o.w3, o.b3, o.n3)
	he(o.w4, o.b4, r.D)
	return r
}

func (r *Red) NumPesos() int { return r.offs().total }

// Activaciones intermedias de una muestra (necesarias para el backward).
type cache struct {
	x, a1, p1, a2, p2, h, out []float32
	i1, i2                    []int32
}

// conv3x3 con padding 1 y ReLU: in c×h×w → out f×h×w
func conv(in []float32, c, h, w int, wt, b []float32, f int, out []float32) {
	for o := 0; o < f; o++ {
		for y := 0; y < h; y++ {
			for x := 0; x < w; x++ {
				s := b[o]
				for ci := 0; ci < c; ci++ {
					k := wt[(o*c+ci)*9:]
					base := ci * h * w
					for dy := -1; dy <= 1; dy++ {
						yy := y + dy
						if yy < 0 || yy >= h {
							continue
						}
						for dx := -1; dx <= 1; dx++ {
							xx := x + dx
							if xx < 0 || xx >= w {
								continue
							}
							s += k[(dy+1)*3+dx+1] * in[base+yy*w+xx]
						}
					}
				}
				if s < 0 {
					s = 0
				}
				out[(o*h+y)*w+x] = s
			}
		}
	}
}

// backward de conv3x3+ReLU: recibe dOut (se modifica), acumula dW, dB y escribe dIn (si no es nil)
func convBack(in []float32, c, h, w int, wt []float32, f int, out, dOut, dW, dB, dIn []float32) {
	if dIn != nil {
		for i := range dIn {
			dIn[i] = 0
		}
	}
	for o := 0; o < f; o++ {
		for y := 0; y < h; y++ {
			for x := 0; x < w; x++ {
				idx := (o*h+y)*w + x
				g := dOut[idx]
				if out[idx] <= 0 || g == 0 { // ReLU
					continue
				}
				dB[o] += g
				for ci := 0; ci < c; ci++ {
					kw := (o*c + ci) * 9
					base := ci * h * w
					for dy := -1; dy <= 1; dy++ {
						yy := y + dy
						if yy < 0 || yy >= h {
							continue
						}
						for dx := -1; dx <= 1; dx++ {
							xx := x + dx
							if xx < 0 || xx >= w {
								continue
							}
							p := base + yy*w + xx
							dW[kw+(dy+1)*3+dx+1] += g * in[p]
							if dIn != nil {
								dIn[p] += g * wt[kw+(dy+1)*3+dx+1]
							}
						}
					}
				}
			}
		}
	}
}

// maxpool 2×2: guarda el índice del máximo para el backward
func pool(in []float32, c, h, w int, out []float32, arg []int32) {
	h2, w2 := h/2, w/2
	for ci := 0; ci < c; ci++ {
		for y := 0; y < h2; y++ {
			for x := 0; x < w2; x++ {
				b0 := int32((ci*h+2*y)*w + 2*x)
				best := b0
				for _, d := range [3]int32{1, int32(w), int32(w) + 1} {
					if in[b0+d] > in[best] {
						best = b0 + d
					}
				}
				i := (ci*h2+y)*w2 + x
				out[i], arg[i] = in[best], best
			}
		}
	}
}

func (r *Red) nuevoCache() *cache {
	h2, w2, h4, w4 := r.H/2, r.W/2, r.H/4, r.W/4
	return &cache{
		x:  make([]float32, r.C*r.H*r.W),
		a1: make([]float32, r.F1*r.H*r.W), p1: make([]float32, r.F1*h2*w2), i1: make([]int32, r.F1*h2*w2),
		a2: make([]float32, r.F2*h2*w2), p2: make([]float32, r.F2*h4*w4), i2: make([]int32, r.F2*h4*w4),
		h: make([]float32, r.D), out: make([]float32, r.K),
	}
}

// forward: x ya normalizada (C×H×W). Deja probabilidades en c.out.
func (r *Red) forward(c *cache) {
	o, p := r.offs(), r.Pesos
	h2, w2 := r.H/2, r.W/2
	// micro chunk de convoluciones
	conv(c.x, r.C, r.H, r.W, p[o.w1:o.b1], p[o.b1:o.w2], r.F1, c.a1)
	pool(c.a1, r.F1, r.H, r.W, c.p1, c.i1)
	conv(c.p1, r.F1, h2, w2, p[o.w2:o.b2], p[o.b2:o.w3], r.F2, c.a2)
	pool(c.a2, r.F2, h2, w2, c.p2, c.i2)
	// micro chunk MLP
	for j := 0; j < r.D; j++ {
		s := p[o.b3+j]
		fila := p[o.w3+j*o.n3 : o.w3+(j+1)*o.n3]
		for i, v := range c.p2 {
			s += fila[i] * v
		}
		if s < 0 {
			s = 0
		}
		c.h[j] = s
	}
	for k := 0; k < r.K; k++ {
		s := p[o.b4+k]
		for j, v := range c.h {
			s += p[o.w4+k*r.D+j] * v
		}
		c.out[k] = s
	}
	softmax(c.out)
}

func softmax(v []float32) {
	m := v[0]
	for _, x := range v {
		if x > m {
			m = x
		}
	}
	var s float32
	for i, x := range v {
		v[i] = float32(math.Exp(float64(x - m)))
		s += v[i]
	}
	for i := range v {
		v[i] /= s
	}
}

// Predecir devuelve las probabilidades de una imagen ya normalizada. Solo lee
// los pesos, así que varias goroutines pueden llamarla a la vez.
func (r *Red) Predecir(x []float32) []float32 {
	c := r.nuevoCache()
	copy(c.x, x)
	r.forward(c)
	return c.out
}

// Gradiente de una muestra: acumula en grad (mismo tamaño que Pesos) y devuelve la pérdida.
type Trabajo struct {
	c                 *cache
	dp2, dp1, da2, dh []float32
	da1               []float32
}

func (r *Red) NuevoTrabajo() *Trabajo {
	return &Trabajo{c: r.nuevoCache(), dp2: make([]float32, r.F2*(r.H/4)*(r.W/4)),
		dp1: make([]float32, r.F1*(r.H/2)*(r.W/2)), da2: make([]float32, r.F2*(r.H/2)*(r.W/2)),
		da1: make([]float32, r.F1*r.H*r.W), dh: make([]float32, r.D)}
}

func (r *Red) Gradiente(t *Trabajo, x []float32, etiqueta int, grad []float32) (perdida float32, acierto bool) {
	o, p, c := r.offs(), r.Pesos, t.c
	copy(c.x, x)
	r.forward(c)
	mejor := 0
	for k := range c.out {
		if c.out[k] > c.out[mejor] {
			mejor = k
		}
	}
	perdida = -float32(math.Log(math.Max(float64(c.out[etiqueta]), 1e-12)))
	// softmax + entropía cruzada: dz = p - y
	for j := range t.dh {
		t.dh[j] = 0
	}
	for k := 0; k < r.K; k++ {
		g := c.out[k]
		if k == etiqueta {
			g--
		}
		grad[o.b4+k] += g
		for j, v := range c.h {
			grad[o.w4+k*r.D+j] += g * v
			t.dh[j] += g * p[o.w4+k*r.D+j]
		}
	}
	for i := range t.dp2 {
		t.dp2[i] = 0
	}
	for j := 0; j < r.D; j++ {
		if c.h[j] <= 0 {
			continue
		}
		g := t.dh[j]
		grad[o.b3+j] += g
		fila := o.w3 + j*o.n3
		for i, v := range c.p2 {
			grad[fila+i] += g * v
			t.dp2[i] += g * p[fila+i]
		}
	}
	// pooling hacia atrás: el gradiente va solo a la posición del máximo
	for i := range t.da2 {
		t.da2[i] = 0
	}
	for i, j := range c.i2 {
		t.da2[j] += t.dp2[i]
	}
	h2, w2 := r.H/2, r.W/2
	convBack(c.p1, r.F1, h2, w2, p[o.w2:o.b2], r.F2, c.a2, t.da2, grad[o.w2:o.b2], grad[o.b2:o.w3], t.dp1)
	for i := range t.da1 {
		t.da1[i] = 0
	}
	for i, j := range c.i1 {
		t.da1[j] += t.dp1[i]
	}
	convBack(c.x, r.C, r.H, r.W, p[o.w1:o.b1], r.F1, c.a1, t.da1, grad[o.w1:o.b1], grad[o.b1:o.w2], nil)
	return perdida, mejor == etiqueta
}

func (r *Red) Guardar(ruta string) error {
	f, err := os.Create(ruta)
	if err != nil {
		return err
	}
	defer f.Close()
	return gob.NewEncoder(f).Encode(r)
}

func Cargar(ruta string) (*Red, error) {
	f, err := os.Open(ruta)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	r := &Red{}
	return r, gob.NewDecoder(f).Decode(r)
}

func ln(v float32) float64 { return math.Log(math.Max(float64(v), 1e-12)) }
