package cnn

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"image"
	_ "image/jpeg" // registra los decodificadores de la biblioteca estándar
	_ "image/png"
	"math"
	"os"
)

// Dataset preprocesado (.bin): cabecera "DSET", versión, N, C, H, W, K (uint32 LE)
// y luego N registros: [split][etiqueta][C×H×W bytes en orden canal-fila-columna].
// split: 0 = entrenamiento, 1 = validación, 2 = prueba.
type Dataset struct {
	N, C, H, W, K int
	Split, Etiq   []byte
	Pix           []byte
}

func CargarDataset(ruta string) (*Dataset, error) {
	b, err := os.ReadFile(ruta)
	if err != nil {
		return nil, err
	}
	if len(b) < 28 || string(b[:4]) != "DSET" {
		return nil, fmt.Errorf("%s no es un dataset DSET", ruta)
	}
	u := func(i int) int { return int(binary.LittleEndian.Uint32(b[4+4*i:])) }
	d := &Dataset{N: u(1), C: u(2), H: u(3), W: u(4), K: u(5)}
	tam := d.C * d.H * d.W
	if len(b) != 28+d.N*(2+tam) {
		return nil, fmt.Errorf("%s: tamaño inesperado", ruta)
	}
	d.Split, d.Etiq, d.Pix = make([]byte, d.N), make([]byte, d.N), make([]byte, d.N*tam)
	for i := 0; i < d.N; i++ {
		o := 28 + i*(2+tam)
		d.Split[i], d.Etiq[i] = b[o], b[o+1]
		copy(d.Pix[i*tam:], b[o+2:o+2+tam])
	}
	return d, nil
}

// Indices devuelve las posiciones de un split.
func (d *Dataset) Indices(split byte) []int {
	var r []int
	for i, s := range d.Split {
		if s == split {
			r = append(r, i)
		}
	}
	return r
}

// MediaDesv por canal sobre el set de entrenamiento (valores en [0,1]).
func (d *Dataset) MediaDesv() (media, desv []float32) {
	hw := d.H * d.W
	media, desv = make([]float32, d.C), make([]float32, d.C)
	sum, sum2 := make([]float64, d.C), make([]float64, d.C)
	n := 0.0
	for _, i := range d.Indices(0) {
		for c := 0; c < d.C; c++ {
			for _, v := range d.Pix[i*d.C*hw+c*hw : i*d.C*hw+(c+1)*hw] {
				f := float64(v) / 255
				sum[c] += f
				sum2[c] += f * f
			}
		}
		n += float64(hw)
	}
	for c := range media {
		m := sum[c] / n
		media[c] = float32(m)
		desv[c] = float32(math.Sqrt(sum2[c]/n - m*m))
	}
	return
}

// Muestra escribe en x la imagen i normalizada.
func (r *Red) Muestra(d *Dataset, i int, x []float32) {
	tam, hw := d.C*d.H*d.W, d.H*d.W
	for j, v := range d.Pix[i*tam : (i+1)*tam] {
		c := j / hw
		x[j] = (float32(v)/255 - r.Media[c]) / r.Desv[c]
	}
}

// Preparar decodifica una imagen PNG/JPEG, la rellena a cuadrado con negro (como en
// el preprocesamiento del dataset), la redimensiona a H×W (bilineal) y la normaliza.
func (r *Red) Preparar(datos []byte) (x []float32, ancho, alto int, err error) {
	img, _, err := image.Decode(bytes.NewReader(datos))
	if err != nil {
		return nil, 0, 0, err
	}
	bnd := img.Bounds()
	ancho, alto = bnd.Dx(), bnd.Dy()
	lado := max(ancho, alto)
	ox, oy := (lado-ancho)/2, (lado-alto)/2 // la imagen queda centrada en el cuadrado
	pix := func(px, py int) [3]float32 {
		px, py = min(max(px, 0), lado-1)-ox, min(max(py, 0), lado-1)-oy
		if px < 0 || py < 0 || px >= ancho || py >= alto {
			return [3]float32{}
		}
		rr, gg, bb, _ := img.At(bnd.Min.X+px, bnd.Min.Y+py).RGBA()
		return [3]float32{float32(rr) / 65535, float32(gg) / 65535, float32(bb) / 65535}
	}
	x = make([]float32, r.C*r.H*r.W)
	esc := float32(lado) / float32(r.W)
	for y := 0; y < r.H; y++ {
		for xx := 0; xx < r.W; xx++ {
			sx, sy := (float32(xx)+0.5)*esc-0.5, (float32(y)+0.5)*esc-0.5
			x0, y0 := int(math.Floor(float64(sx))), int(math.Floor(float64(sy)))
			fx, fy := sx-float32(x0), sy-float32(y0)
			a, b, c, d := pix(x0, y0), pix(x0+1, y0), pix(x0, y0+1), pix(x0+1, y0+1)
			for ch := 0; ch < r.C; ch++ {
				v := (a[ch]*(1-fx)+b[ch]*fx)*(1-fy) + (c[ch]*(1-fx)+d[ch]*fx)*fy
				x[ch*r.H*r.W+y*r.W+xx] = (v - r.Media[ch]) / r.Desv[ch]
			}
		}
	}
	return x, ancho, alto, nil
}
