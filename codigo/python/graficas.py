"""Gráficas de la PC3 a partir de los CSV de resultados/ (matplotlib).

  python graficas.py            → resultados/graficas/*.png
"""
import csv
import glob
import os
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

R = os.path.join(os.path.dirname(__file__), "..", "..", "resultados")
G = os.path.join(R, "graficas")
SERIE = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300"]  # orden fijo
TINTA, TINTA2, REJILLA = "#1f1f1e", "#5f5e5a", "#e4e3df"
plt.rcParams.update({"font.size": 10, "axes.edgecolor": REJILLA, "axes.labelcolor": TINTA2, "xtick.color": TINTA2,
                     "ytick.color": TINTA2, "axes.grid": True, "grid.color": REJILLA, "grid.linewidth": 0.8,
                     "axes.spines.top": False, "axes.spines.right": False, "axes.titlesize": 11,
                     "axes.titleweight": "bold", "axes.axisbelow": True, "legend.frameon": False})


def leer(ruta):
    with open(ruta, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def guardar(fig, nombre):
    fig.tight_layout()
    fig.savefig(os.path.join(G, nombre), dpi=150)
    plt.close(fig)
    print("  ", nombre)


def barras(ax, etiquetas, valores, color, fmt="{:.1f}"):
    b = ax.bar(etiquetas, valores, color=color, width=0.6, edgecolor="white", linewidth=2)
    for r, v in zip(b, valores):
        ax.annotate(fmt.format(v), (r.get_x() + r.get_width() / 2, r.get_height()), ha="center", va="bottom",
                    fontsize=8, color=TINTA2, xytext=(0, 2), textcoords="offset points")


def entrenamiento():
    ruta = os.path.join(R, "entrenamiento", "benchmark_entrenamiento.csv")
    if not os.path.exists(ruta):
        return
    filas = leer(ruta)
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(11, 4))
    for i, mod in enumerate(["A", "B"]):
        orden = {"seq": 0, "par": 1, "dist": 2, "pardist": 3}
        fs = sorted([f for f in filas if f["modelo"] == mod], key=lambda f: (orden[f["modo"]], int(f["maquinas"])))
        tseq = [float(f["tiempo_medio_epoca_s"]) for f in fs if f["modo"] == "seq"]
        for f in fs:  # speedup = T_secuencial / T_configuración
            f["speedup"] = tseq[-1] / float(f["tiempo_medio_epoca_s"]) if tseq else 0
        etq = [f["modo"] + ("" if f["maquinas"] == "1" else f"\nM={f['maquinas']}") for f in fs]
        x = [j + (i - 0.5) * 0.38 for j in range(len(fs))]
        nombre = "A (malaria)" if mod == "A" else "B (sangre)"
        a1.bar(x, [float(f["tiempo_medio_epoca_s"]) for f in fs], 0.36, color=SERIE[i], label=nombre,
               edgecolor="white", linewidth=2)
        a2.plot(range(len(fs)), [float(f["speedup"] or 0) for f in fs], "o-", color=SERIE[i], lw=2, ms=6, label=nombre)
    a1.set_xticks(range(len(etq)), etq)
    a2.set_xticks(range(len(etq)), etq)
    a1.set(title="Entrenamiento: tiempo por época", ylabel="segundos")
    a2.set(title="Speedup respecto a secuencial", ylabel="speedup (×)")
    a1.legend()
    a2.legend()
    guardar(fig, "1_entrenamiento_tiempo_speedup.png")


def curvas_y_confusion():
    for mod, nombre in [("A", "malaria"), ("B", "células sanguíneas")]:
        ruta = os.path.join(R, "modelos", f"entrenamiento_{mod}_par.csv")
        if not os.path.exists(ruta):
            continue
        f = leer(ruta)
        ep = [int(x["epoca"]) for x in f]
        fig, (a1, a2) = plt.subplots(1, 2, figsize=(10, 3.6))
        for ax, clave, tit in [(a1, "loss", "Loss"), (a2, "acc", "Accuracy")]:
            ax.plot(ep, [float(x[clave + "_train"]) for x in f], "o-", color=SERIE[0], lw=2, ms=5, label="entrenamiento")
            ax.plot(ep, [float(x[clave + "_val"]) for x in f], "o-", color=SERIE[1], lw=2, ms=5, label="validación")
            ax.set(title=f"Modelo {mod} ({nombre}): {tit}", xlabel="época")
            ax.legend()
        guardar(fig, f"2_curvas_modelo_{mod}.png")
        conf = leer(os.path.join(R, "modelos", f"confusion_{mod}_par.csv"))
        clases = list(conf[0].keys())[1:]
        m = [[int(fila[c]) for c in clases] for fila in conf]
        fig, ax = plt.subplots(figsize=(max(4.2, 1.2 + 0.75 * len(clases)), max(3.6, 0.9 + 0.65 * len(clases))))
        ax.imshow(m, cmap=matplotlib.colors.LinearSegmentedColormap.from_list("azul", ["#f3f7fd", "#104281"]))
        mx = max(max(r) for r in m)
        for i, r in enumerate(m):
            for j, v in enumerate(r):
                ax.text(j, i, v, ha="center", va="center", fontsize=8, color="white" if v > mx / 2 else TINTA)
        ax.set_xticks(range(len(clases)), clases, rotation=45, ha="right")
        ax.set_yticks(range(len(clases)), clases)
        ax.grid(False)
        ax.set(title=f"Confusión – modelo {mod} (prueba)", xlabel="predicha", ylabel="real")
        guardar(fig, f"3_confusion_modelo_{mod}.png")


def carga():
    ruta = os.path.join(R, "resumen_carga.csv")
    if not os.path.exists(ruta):
        return
    filas = [f for f in leer(ruta) if f["tipo"] == "CONSULTA"]
    orden = [("seq", "1"), ("par", "1"), ("dist", "2"), ("dist", "3"), ("pardist", "1"), ("pardist", "2"), ("pardist", "3")]
    agg = defaultdict(lambda: [0.0, 0.0, 0])  # (config, nodos) → throughput total, latencia media, n
    for f in filas:
        k = (f["config"], f["nodos_ia"])
        agg[k][0] += float(f["throughput_tps"])
        agg[k][1] += float(f["lat_media_ms"])
        agg[k][2] += 1
    claves = [k for k in orden if k in agg]
    if claves:
        etq = [c + ("" if n == "1" and c in ("seq", "par") else f"\nM={n}") for c, n in claves]
        fig, ax = plt.subplots(figsize=(8, 3.8))
        barras(ax, etq, [agg[k][0] for k in claves], SERIE[0])
        ax.set(title="Throughput vs. configuración (escritorio + web, 2000 consultas)", ylabel="transacciones/s")
        guardar(fig, "4_throughput_vs_configuracion.png")

        fig, ax = plt.subplots(figsize=(6.5, 3.8))
        for i, (cfg, base) in enumerate([("dist", ("seq", "1")), ("pardist", None)]):
            ks = ([base] if base in agg else []) + [k for k in claves if k[0] == cfg]
            ks = sorted(ks, key=lambda k: int(k[1]))
            if ks:
                ax.plot([int(k[1]) for k in ks], [agg[k][1] / agg[k][2] for k in ks], "o-", color=SERIE[i], lw=2, ms=7,
                        label="1 núcleo por nodo" if cfg == "dist" else "4 núcleos por nodo")
        ax.set_xticks([1, 2, 3])
        ax.set(title="Latencia media vs. número de nodos IA", xlabel="nodos IA", ylabel="ms")
        ax.legend()
        guardar(fig, "5_latencia_vs_nodos.png")

    sat = [f for f in filas if f["config"].startswith("saturacion")]
    if sat:
        fig, (a1, a2) = plt.subplots(1, 2, figsize=(10, 3.6))
        barras(a1, [f["plataforma"] for f in sat], [float(f["lat_media_ms"]) for f in sat], SERIE[0], "{:.0f}")
        a1.set(title="Saturación: latencia media por cliente", ylabel="ms")
        barras(a2, [f["plataforma"] for f in sat], [float(f["throughput_tps"]) for f in sat], SERIE[1])
        err = sum(int(f["errores"]) for f in sat) / sum(int(f["n"]) for f in sat) * 100
        a2.set(title=f"Saturación: throughput por cliente (errores: {err:.2f} %)", ylabel="transacciones/s")
        guardar(fig, "6_saturacion_latencia_errores.png")


def rabbit_y_nodos(corrida="saturacion"):
    ruta = os.path.join(R, f"colas_{corrida}.csv")
    if os.path.exists(ruta):
        series = defaultdict(lambda: ([], []))
        for f in leer(ruta):
            series[f["cola"]][0].append(float(f["t_s"]))
            series[f["cola"]][1].append(int(f["mensajes"]))
        colas = ["cola_modeloA", "cola_modeloB", "cola_validacion", "cola_resultadosA", "cola_resultadosB", "cola_respuestas"]
        fig, ax = plt.subplots(figsize=(9, 3.8))
        for i, q in enumerate(colas):
            if q in series:
                ax.plot(*series[q], color=SERIE[i], lw=2, label=q)
        ax.set(title="RabbitMQ bajo carga: mensajes pendientes por cola", xlabel="segundos", ylabel="mensajes en cola")
        ax.legend(ncol=3, fontsize=8)
        guardar(fig, "7_rabbitmq_colas_bajo_carga.png")
    ruta = os.path.join(R, f"metricas_nodos_{corrida}.csv")
    if os.path.exists(ruta):
        por = defaultdict(lambda: ([], [], []))
        for f in leer(ruta):
            n = f["rol"] + " " + f["nodo"].split("-")[1] if f["rol"] == "IA" else f["rol"]
            por[n][0].append(float(f["t_s"]))
            por[n][1].append(float(f["cpu_pct"]))
            por[n][2].append(float(f["mem_mb"]))
        nodos = sorted(por)
        fig, (a1, a2) = plt.subplots(1, 2, figsize=(11, 3.8))
        prom = lambda v: sum(v) / max(len(v), 1)
        barras(a1, nodos, [prom(por[n][1]) for n in nodos], SERIE[0])
        barras(a2, nodos, [max(por[n][2]) for n in nodos], SERIE[2], "{:.0f}")
        a1.set(title="CPU media por nodo (saturación)", ylabel="% del total de núcleos")
        a2.set(title="Memoria máxima por nodo (saturación)", ylabel="MB")
        for ax in (a1, a2):
            ax.tick_params(axis="x", rotation=30, labelsize=8)
        guardar(fig, "8_cpu_memoria_por_nodo.png")


if __name__ == "__main__":
    os.makedirs(G, exist_ok=True)
    entrenamiento()
    curvas_y_confusion()
    carga()
    rabbit_y_nodos()
