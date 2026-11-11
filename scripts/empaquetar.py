"""Arma el comprimido de entrega para UNIVIRTUAL: solo códigos fuente de cada lenguaje
(.java .go .py .kt .kts .html .js .css), scripts .sql, el dataset y los PDF.

  python scripts/empaquetar.py      -> entrega/PC3_CC4P1_BorgIACube.zip
"""
import os
import zipfile

RAIZ = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
FUENTES = (".java", ".go", ".py", ".kt", ".kts", ".html", ".js", ".css", ".sql")
SALTAR = {"out", "build", ".gradle", "__pycache__", "lib"}

destino = os.path.join(RAIZ, "entrega", "PC3_CC4P1_BorgIACube.zip")
os.makedirs(os.path.dirname(destino), exist_ok=True)
with zipfile.ZipFile(destino, "w", zipfile.ZIP_DEFLATED) as z:
    for base, dirs, archivos in os.walk(os.path.join(RAIZ, "codigo")):
        dirs[:] = [d for d in dirs if d not in SALTAR]
        for a in archivos:
            if a.endswith(FUENTES):
                ruta = os.path.join(base, a)
                z.write(ruta, os.path.relpath(ruta, RAIZ))
    for base, _, archivos in os.walk(os.path.join(RAIZ, "datos")):  # dataset
        for a in archivos:
            if a.endswith((".bin", ".png")):
                ruta = os.path.join(base, a)
                z.write(ruta, os.path.relpath(ruta, RAIZ))
    for a in ("informe/Informe_PC3.pdf", "informe/Presentacion_PC3.pdf"):
        z.write(os.path.join(RAIZ, a), a)
    print(len(z.namelist()), "archivos ->", destino)
