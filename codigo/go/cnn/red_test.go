package cnn

import (
	"math"
	"math/rand"
	"os"
	"testing"
)

// Compara el gradiente analítico (backpropagation) con el numérico en pesos al azar.
func TestGradiente(t *testing.T) {
	r := Nueva(3, 8, 8, 3, []string{"a", "b", "c"}, 1)
	rng := rand.New(rand.NewSource(2))
	x := make([]float32, 3*8*8)
	for i := range x {
		x[i] = float32(rng.NormFloat64())
	}
	g := make([]float32, len(r.Pesos))
	r.Gradiente(r.NuevoTrabajo(), x, 1, g)
	perdida := func() float64 { return -ln(r.Predecir(x)[1]) }
	for n := 0; n < 40; n++ {
		i := rng.Intn(len(r.Pesos))
		v := r.Pesos[i]
		const h = 1e-2
		r.Pesos[i] = v + h
		lp := perdida()
		r.Pesos[i] = v - h
		lm := perdida()
		r.Pesos[i] = v
		num := (lp - lm) / (2 * h)
		if math.Abs(num-float64(g[i])) > 1e-2+0.05*math.Abs(num) {
			t.Errorf("peso %d: analítico %.5f, numérico %.5f", i, g[i], num)
		}
	}
}

func BenchmarkInferencia(b *testing.B) {
	r, err := Cargar("../../../datos/modelo_A.gob")
	if err != nil {
		b.Skip(err)
	}
	img, _ := os.ReadFile("../../../datos/muestras/A_00_parasitada.png")
	for i := 0; i < b.N; i++ {
		x, _, _, _ := r.Preparar(img)
		r.Predecir(x)
	}
}
