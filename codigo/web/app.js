// Cliente web del chat. Habla HTTP (fetch) con el servidor HTTP propio, que a su
// vez usa sockets TCP propios hacia el backend. No usa WebSocket ni librerías.
"use strict";

const $ = (id) => document.getElementById(id);
let adjunto = null;       // {nombre, formato, base64, url}
let sondeando = false;
const pendientes = new Map(); // idMensajeCliente → callback (modo estrés)

async function api(ruta, cuerpo) {
  const r = await fetch(ruta, {
    method: cuerpo === undefined ? "GET" : "POST",
    headers: cuerpo === undefined ? {} : { "Content-Type": "application/json" },
    body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
    credentials: "same-origin",
  });
  if (r.status === 401) { salir(true); throw new Error("sesión expirada"); }
  return r.json();
}

function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])); }

function burbuja(html, clase) {
  const d = document.createElement("div");
  d.className = "msg " + clase;
  d.innerHTML = html;
  $("mensajes").appendChild(d);
  $("mensajes").scrollTop = $("mensajes").scrollHeight;
  return d;
}
const bot = (html, err) => burbuja("<b>BorgAICube:</b> " + html, "bot" + (err ? " err" : ""));
const nombreModelo = (m) => (m === "A" ? "Malaria" : "Células sanguíneas");

function mostrarResultado(m) {
  const probs = Object.entries(m.probabilidades || {}).sort((a, b) => b[1] - a[1]).slice(0, 4);
  const barras = probs.map(([k, v]) =>
    `<div class="barra">${esc(k)} — ${(v * 100).toFixed(1)} %<div class="pista"><div class="relleno" style="width:${(v * 100).toFixed(1)}%"></div></div></div>`).join("");
  bot(`Diagnóstico (Modelo ${esc(m.modelo)} – ${nombreModelo(m.modelo)}): <b>${esc(m.etiqueta)}</b> con ` +
      `${(m.probabilidad * 100).toFixed(1)} % de confianza.${barras}` +
      `<div class="meta">Procesado por ${esc(m.nodoIA)} · guardado en ${esc(m.nodoBD)} · ${m.latenciaTotalMs} ms</div>`);
}

// Long polling: el servidor responde cuando hay RESULTADO/ERROR o a los 25 s.
async function sondear() {
  if (sondeando) return;
  sondeando = true;
  while (sondeando) {
    try {
      const r = await api("/api/eventos");
      for (const ev of r.eventos || []) {
        const cb = pendientes.get(ev.idMensajeCliente);
        if (cb) { pendientes.delete(ev.idMensajeCliente); cb(ev); continue; }
        if (ev.tipo === "RESULTADO") mostrarResultado(ev);
        else if (ev.tipo === "ERROR") bot(`⚠ ${esc(ev.codigo)} — ${esc(ev.mensaje)}`, true);
        else if (ev.tipo === "DESCONECTADO") bot("Se perdió la conexión con el backend.", true);
      }
    } catch (e) {
      if (!sondeando) break;
      await new Promise((ok) => setTimeout(ok, 1500));
    }
  }
}

async function autenticar(registro) {
  $("msgAcceso").textContent = "Conectando…";
  try {
    const r = await api(registro ? "/api/registro" : "/api/login",
      { usuario: $("usuario").value.trim(), password: $("password").value, nombre: $("usuario").value.trim() });
    if (r.tipo !== "LOGIN_OK") { $("msgAcceso").textContent = r.motivo || r.mensaje || "Error"; return; }
    $("msgAcceso").textContent = "";
    $("acceso").classList.add("oculto");
    $("chat").classList.remove("oculto");
    $("quien").textContent = `Usuario validado · idCliente ${r.idCliente} · ${r.nombre}`;
    bot(`Hola ${esc(r.nombre)}. Adjunta una imagen de microscopía (📎) y presiona Enviar. ` +
        "Comandos: /malaria, /sangre, /historial.");
    sondear();
  } catch (e) { $("msgAcceso").textContent = "No se pudo conectar: " + e.message; }
}

function salir(silencioso) {
  sondeando = false;
  if (!silencioso) api("/api/logout", {}).catch(() => {});
  $("chat").classList.add("oculto");
  $("acceso").classList.remove("oculto");
  $("mensajes").innerHTML = "";
}

function leerArchivo(f) {
  return new Promise((ok, mal) => {
    const fr = new FileReader();
    fr.onload = () => {
      const url = fr.result;                     // data:image/png;base64,....
      ok({ nombre: f.name, url, base64: url.substring(url.indexOf(",") + 1),
           formato: f.type === "image/jpeg" ? "jpg" : "png" });
    };
    fr.onerror = mal;
    fr.readAsDataURL(f);
  });
}

async function enviar() {
  const texto = $("texto").value.trim();
  $("texto").value = "";
  const modelo = $("modelo").value;
  if (!adjunto) {
    if (!texto) return;
    burbuja("<b>Yo:</b> " + esc(texto), "mio");
    if (texto === "/malaria") { $("modelo").value = "A"; bot("Modelo A — Malaria (Parasitada / No infectada)."); }
    else if (texto === "/sangre") { $("modelo").value = "B"; bot("Modelo B — 8 tipos de células sanguíneas."); }
    else if (texto === "/historial") historial();
    else bot(`Para diagnosticar necesito una imagen (📎). Modelo actual: ${modelo} (${nombreModelo(modelo)}).`);
    return;
  }
  const a = adjunto;
  quitarAdjunto();
  burbuja(`<b>Yo:</b> ${esc(a.nombre)}${texto ? " — " + esc(texto) : ""}<img src="${a.url}" alt="">` +
          `<div class="meta">modelo ${modelo} (${nombreModelo(modelo)})</div>`, "mio");
  try {
    const r = await api("/api/consulta", { modelo, formato: a.formato, payload: a.base64, texto });
    if (r.tipo !== "ACK") bot(`⚠ ${esc(r.codigo)} — ${esc(r.mensaje)}`, true);
  } catch (e) { bot("No se pudo enviar: " + esc(e.message), true); }
}

async function historial() {
  const r = await api("/api/historial", { limite: 15 });
  const filas = (r.items || []).map((it) =>
    `<tr><td>${esc((it.fecha || "").replace("T", " "))}</td><td>${esc(it.modelo)}</td><td>${esc(it.estado)}</td>` +
    `<td>${esc(it.etiqueta)}</td><td>${((it.probabilidad || 0) * 100).toFixed(1)} %</td></tr>`).join("");
  bot(filas ? `tus últimas consultas:<table class="hist">${filas}</table>` : "aún no tienes consultas.");
}

function quitarAdjunto() {
  adjunto = null;
  $("archivo").value = "";
  $("previa").classList.add("oculto");
}

// ---------- modo estrés: N consultas con delay aleatorio desde el navegador ----------
async function estres() {
  const archivos = [...$("estresArchivos").files];
  if (!archivos.length) { bot("Elige al menos una imagen para el modo estrés.", true); return; }
  const imgs = await Promise.all(archivos.map(leerArchivo));
  const n = +$("estresN").value, dmin = +$("estresMin").value, dmax = Math.max(+$("estresMax").value, dmin);
  const mix = $("estresModelo").value === "MIX";
  const lat = []; let ok = 0, err = 0, fin = 0;
  const t0 = performance.now();
  const aviso = bot(`modo estrés: enviando ${n} consultas…`);
  const espera = [];
  for (let i = 0; i < n; i++) {
    await new Promise((r) => setTimeout(r, dmin + Math.random() * (dmax - dmin)));
    const img = imgs[Math.floor(Math.random() * imgs.length)];
    const modelo = mix ? (i % 2 ? "B" : "A") : $("modelo").value;
    const idm = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + i;
    const t = performance.now();
    espera.push(new Promise((listo) => {
      pendientes.set(idm, (ev) => { ev.tipo === "RESULTADO" ? (ok++, lat.push(performance.now() - t)) : err++; fin++; listo(); });
      api("/api/consulta", { idMensajeCliente: idm, modelo, formato: img.formato, payload: img.base64, texto: "estrés" })
        .then((r) => { if (r.tipo !== "ACK") { pendientes.delete(idm); err++; fin++; listo(); } })
        .catch(() => { pendientes.delete(idm); err++; fin++; listo(); });
    }));
    if (i % 25 === 0) aviso.innerHTML = `<b>BorgAICube:</b> modo estrés: ${i + 1}/${n} enviadas, ${fin} respondidas…`;
  }
  await Promise.race([Promise.all(espera), new Promise((r) => setTimeout(r, 90000))]);
  const dur = (performance.now() - t0) / 1000;
  lat.sort((a, b) => a - b);
  const p = (q) => (lat.length ? lat[Math.min(lat.length - 1, Math.ceil(q * lat.length) - 1)].toFixed(0) : 0);
  const media = lat.length ? (lat.reduce((a, b) => a + b, 0) / lat.length).toFixed(1) : 0;
  aviso.innerHTML = `<b>BorgAICube:</b> modo estrés terminado: ${n} transacciones · OK ${ok} · errores ${err + (n - fin)} ` +
    `(${(100 * (err + n - fin) / n).toFixed(2)} %) · latencia media ${media} ms, p50 ${p(0.5)}, p95 ${p(0.95)}, p99 ${p(0.99)} ms · ` +
    `throughput ${(ok / dur).toFixed(2)} tx/s · ${dur.toFixed(1)} s`;
}

// ---------- eventos de la interfaz ----------
$("btnIngresar").onclick = () => autenticar(false);
$("btnRegistro").onclick = () => autenticar(true);
$("password").addEventListener("keydown", (e) => { if (e.key === "Enter") autenticar(false); });
$("btnEnviar").onclick = enviar;
$("texto").addEventListener("keydown", (e) => { if (e.key === "Enter") enviar(); });
$("btnHistorial").onclick = historial;
$("btnSalir").onclick = () => salir(false);
$("quitar").onclick = quitarAdjunto;
$("archivo").onchange = async () => {
  const f = $("archivo").files[0];
  if (!f) return;
  adjunto = await leerArchivo(f);
  $("imgPrevia").src = adjunto.url;
  $("nomPrevia").textContent = f.name;
  $("previa").classList.remove("oculto");
};
$("btnEstres").onclick = () => $("dlgEstres").showModal();
$("dlgEstres").addEventListener("close", () => { if ($("dlgEstres").returnValue === "ok") estres(); });
