# PC3: plan de acción

Base: el proyecto del compañero (`sem11/anvancePC3/cubo-ia_fase6_todas_las_PC.zip`). Requisitos: `enunciado/P3cc4P1-262_v07.pdf`.

## Qué se reutiliza tal cual
| Parte | Lenguaje | Estado |
|---|---|---|
| Backend (TCP :5000), cliente de escritorio (Swing + terminal), servidor web propio (:8080), generador de carga | Java | Completo |
| Frontend web (HTML/JS, sin WebSocket) | JS | Completo |
| Nodos BD1 (PostgreSQL), BD2 (MariaDB, validación), BD3 (MySQL) y verificación de consistencia | Python | Completo |
| Scripts de creación de las 3 bases | SQL | Completo |
| Datasets ya preprocesados (malaria 32×32, BloodMNIST 28×28) y 100 imágenes de prueba | — | Completo |

## Qué falta y cómo se resuelve
| # | Falta | Acción |
|---|---|---|
| 1 | Código fuente Go del nodo IA (solo había `.exe`) | Escribir en Go la CNN (convolución + ReLU + pooling, MLP + Softmax), el nodo IA que consume `cola_modeloA/B` y publica en `cola_resultadosA/B`, y el entrenamiento en 4 modos (seq, par, dist, pardist). Reentrenar los dos modelos con ese código |
| 2 | Código fuente de la app Android (solo había `.apk`) | App Kotlin mínima: login/registro, chat con imagen, modelo A/B, historial y prueba de estrés de 1000 envíos. Compilar el APK |
| 3 | Monitor de colas/CPU/memoria y gráficas (eran `.exe`) | `monitor.py` (API de gestión de RabbitMQ + `cola_metricas`) y `graficas.py` |
| 4 | Pruebas | Levantar RabbitMQ + 3 bases (Docker) y todos los nodos; probar el flujo completo, 1000 registros, saturación, 4 configuraciones y consistencia de las bases |
| 5 | Informe y presentación en PDF | Datasets, preprocesamiento, métricas, arquitectura, diagrama de protocolo, resultados y gráficas |
| 6 | Comprimido de entrega | Solo fuentes (`.java`, `.go`, `.py`, `.kt`, `.html/.js/.css`), scripts SQL, dataset y los 2 PDF |

## Despliegue (3 SO, 3 BD, 3 LP)
| Nodo | SO | Lenguaje | Base |
|---|---|---|---|
| Servidor: RabbitMQ + backend + servidor web | Linux (VM/nube) o Windows | Java | — |
| Nodos IA | Windows / Linux | Go | — |
| Nodo BD2 (validación) | Windows | Python | MariaDB |
| Nodo BD1 (modelo A) / BD3 (modelo B) | Linux / Windows | Python | PostgreSQL / MySQL |
| Cliente móvil | Android | Kotlin | — |

## Estado (2026-11-10)
Los 6 puntos están hechos y probados en una PC: flujo completo con los 3 clientes, 4 configuraciones de entrenamiento e inferencia, saturación con 3000 transacciones (0 errores), 1000 registros, consistencia de las 3 bases y pruebas de concurrencia. Falta repetir las mediciones en el clúster real (laptops + VM de Google Cloud, ver `NUBE_GCP.md`) y poner los nombres del equipo en la portada del informe.
