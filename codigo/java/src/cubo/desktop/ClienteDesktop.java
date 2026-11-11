package cubo.desktop;

import cubo.carga.GeneradorCarga;
import cubo.comun.Args;
import cubo.comun.ConexionBackend;
import cubo.comun.Json;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Image;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cliente de escritorio con GUI Swing (mismo chat que el de terminal).
 * Toda la red corre en hilos de fondo; la interfaz solo se toca con
 * SwingUtilities.invokeLater.
 *
 *   java -cp "out:lib/*" cubo.desktop.ClienteDesktop --host IP_BACKEND --puerto 5000
 */
public class ClienteDesktop {
    private static final Color AZUL = new Color(0x2b, 0x57, 0x97), GRIS = new Color(0xee, 0xf1, 0xf5),
            VERDE = new Color(0xdc, 0xf5, 0xe3);
    private ConexionBackend con;
    private JFrame ventana;
    private JPanel mensajes;
    private JScrollPane scroll;
    private JTextField entrada;
    private JComboBox<String> modelo;
    private JLabel estado;
    private final String host;
    private final int puerto;

    ClienteDesktop(String host, int puerto) {
        this.host = host;
        this.puerto = puerto;
    }

    static String nombreModelo(String m) { return "A".equals(m) ? "Malaria" : "Células sanguíneas"; }

    public static String textoResultado(Map<String, Object> m) {
        return String.format("Diagnóstico (Modelo %s – %s): %s con %.1f %% de confianza. Procesado por %s en %d ms.",
                Json.str(m, "modelo"), nombreModelo(Json.str(m, "modelo")), Json.str(m, "etiqueta"),
                Json.dbl(m, "probabilidad", 0) * 100, Json.str(m, "nodoIA"), Json.num(m, "latenciaTotalMs", 0));
    }

    // ------------------------------------------------------------ login
    void mostrarLogin() {
        JFrame f = new JFrame("Borg IA Cube — Iniciar sesión");
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JTextField tHost = new JTextField(host), tPuerto = new JTextField(String.valueOf(puerto)),
                tUsuario = new JTextField("ana");
        JPasswordField tPass = new JPasswordField("clave123");
        p.add(new JLabel("Backend (IP):")); p.add(tHost);
        p.add(new JLabel("Puerto:")); p.add(tPuerto);
        p.add(new JLabel("Usuario:")); p.add(tUsuario);
        p.add(new JLabel("Contraseña:")); p.add(tPass);
        JButton bIngresar = new JButton("Ingresar"), bRegistro = new JButton("Registrarse");
        p.add(bIngresar); p.add(bRegistro);
        JLabel msg = new JLabel(" ");
        f.add(p, BorderLayout.CENTER);
        f.add(msg, BorderLayout.SOUTH);
        for (JButton b : new JButton[]{bIngresar, bRegistro}) {
            b.addActionListener(ev -> {
                bIngresar.setEnabled(false); bRegistro.setEnabled(false);
                msg.setText("Conectando...");
                boolean reg = ev.getSource() == bRegistro;
                new Thread(() -> {
                    try {
                        if (con == null || !con.abierta())
                            con = new ConexionBackend(tHost.getText().trim(), Integer.parseInt(tPuerto.getText().trim()),
                                    "DESKTOP", m -> SwingUtilities.invokeLater(() -> alEvento(m)));
                        String u = tUsuario.getText().trim(), pw = new String(tPass.getPassword());
                        Map<String, Object> r = reg ? con.registro(u, pw, u) : con.login(u, pw);
                        SwingUtilities.invokeLater(() -> {
                            if ("LOGIN_OK".equals(Json.str(r, "tipo"))) { f.dispose(); mostrarChat(); }
                            else msg.setText("Error: " + Json.str(r, "motivo") + Json.str(r, "mensaje"));
                            bIngresar.setEnabled(true); bRegistro.setEnabled(true);
                        });
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> {
                            msg.setText("No se pudo conectar: " + e.getMessage());
                            bIngresar.setEnabled(true); bRegistro.setEnabled(true);
                        });
                    }
                }).start();
            });
        }
        f.getRootPane().setDefaultButton(bIngresar);
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    // ------------------------------------------------------------ chat
    void mostrarChat() {
        ventana = new JFrame("Borg IA Cube — " + con.nombre);
        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        arriba.setBackground(AZUL);
        JLabel titulo = new JLabel("Usuario validado — idCliente " + con.idCliente + " — " + con.nombre);
        titulo.setForeground(Color.WHITE);
        titulo.setFont(titulo.getFont().deriveFont(Font.BOLD, 14f));
        modelo = new JComboBox<>(new String[]{"A — Malaria", "B — Células sanguíneas"});
        estado = new JLabel("● conectado");
        estado.setForeground(new Color(0x9f, 0xf0, 0xb0));
        JPanel der = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        der.setOpaque(false);
        der.add(estado);
        der.add(modelo);
        arriba.add(titulo, BorderLayout.WEST);
        arriba.add(der, BorderLayout.EAST);

        mensajes = new JPanel();
        mensajes.setLayout(new BoxLayout(mensajes, BoxLayout.Y_AXIS));
        mensajes.setBackground(Color.WHITE);
        scroll = new JScrollPane(mensajes);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        entrada = new JTextField();
        JButton bImagen = new JButton("Imagen…"), bEnviar = new JButton("Enviar"), bHist = new JButton("Historial"),
                bEstres = new JButton("Estrés…");
        JPanel abajo = new JPanel(new BorderLayout(6, 6));
        abajo.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        botones.add(bImagen); botones.add(bEnviar); botones.add(bHist); botones.add(bEstres);
        abajo.add(entrada, BorderLayout.CENTER);
        abajo.add(botones, BorderLayout.EAST);

        bImagen.addActionListener(e -> elegirImagen());
        bEnviar.addActionListener(e -> enviarTexto());
        entrada.addActionListener(e -> enviarTexto());
        bHist.addActionListener(e -> historial());
        bEstres.addActionListener(e -> estres());

        ventana.add(arriba, BorderLayout.NORTH);
        ventana.add(scroll, BorderLayout.CENTER);
        ventana.add(abajo, BorderLayout.SOUTH);
        ventana.setSize(820, 640);
        ventana.setLocationRelativeTo(null);
        ventana.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        ventana.setVisible(true);
        bot("Hola " + con.nombre + ". Envíame una imagen de microscopía con «Imagen…». Comandos: /malaria, /sangre, "
                + "/historial, /ayuda.");
    }

    String modeloActual() { return modelo.getSelectedIndex() == 0 ? "A" : "B"; }

    void burbuja(String html, boolean mio, ImageIcon icono, Map<String, Object> probs) {
        JPanel fila = new JPanel(new FlowLayout(mio ? FlowLayout.RIGHT : FlowLayout.LEFT));
        fila.setOpaque(false);
        JPanel b = new JPanel();
        b.setLayout(new BoxLayout(b, BoxLayout.Y_AXIS));
        b.setBackground(mio ? VERDE : GRIS);
        b.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JLabel l = new JLabel("<html><div style='width:420px'>" + html + "</div></html>");
        b.add(l);
        if (icono != null) b.add(new JLabel(icono));
        if (probs != null) {
            List<Map.Entry<String, Object>> orden = new ArrayList<>(probs.entrySet());
            orden.sort((x, y) -> Double.compare(((Number) y.getValue()).doubleValue(), ((Number) x.getValue()).doubleValue()));
            for (Map.Entry<String, Object> e : orden.subList(0, Math.min(4, orden.size()))) {
                double v = ((Number) e.getValue()).doubleValue();
                JProgressBar pb = new JProgressBar(0, 1000);
                pb.setValue((int) (v * 1000));
                pb.setStringPainted(true);
                pb.setString(String.format("%s  %.1f %%", e.getKey(), v * 100));
                pb.setPreferredSize(new Dimension(420, 18));
                b.add(Box.createVerticalStrut(3));
                b.add(pb);
            }
        }
        fila.add(b);
        mensajes.add(fila);
        mensajes.revalidate();
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum()));
    }

    void bot(String texto) { burbuja("<b>BorgAICube:</b> " + escapar(texto), false, null, null); }

    static String escapar(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    void enviarTexto() {
        String t = entrada.getText().trim();
        entrada.setText("");
        if (t.isEmpty()) return;
        burbuja("<b>Yo:</b> " + escapar(t), true, null, null);
        switch (t) {
            case "/malaria": modelo.setSelectedIndex(0); bot("Modelo A — Malaria (Parasitada / No infectada)."); break;
            case "/sangre": modelo.setSelectedIndex(1); bot("Modelo B — 8 tipos de células sanguíneas."); break;
            case "/historial": historial(); break;
            default: bot("Para diagnosticar necesito una imagen: usa «Imagen…». Modelo actual: " + modeloActual()
                    + " (" + nombreModelo(modeloActual()) + "). Comandos: /malaria, /sangre, /historial.");
        }
    }

    void elegirImagen() {
        JFileChooser fc = new JFileChooser(new File("../datos/muestras"));
        fc.setFileFilter(new FileNameExtensionFilter("Imágenes PNG/JPG", "png", "jpg", "jpeg"));
        if (fc.showOpenDialog(ventana) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        String texto = entrada.getText().trim();
        entrada.setText("");
        String m = modeloActual();
        try {
            byte[] datos = Files.readAllBytes(f.toPath());
            ImageIcon ic = new ImageIcon(new ImageIcon(datos).getImage().getScaledInstance(96, 96, Image.SCALE_SMOOTH));
            burbuja("<b>Yo:</b> " + escapar(f.getName()) + (texto.isEmpty() ? "" : " — " + escapar(texto))
                    + "<br><i>modelo " + m + " (" + nombreModelo(m) + ")</i>", true, ic, null);
            new Thread(() -> {
                try {
                    Map<String, Object> r = con.consultaImagen(UUID.randomUUID().toString(), m,
                            ConexionBackend.formatoDe(f.getName(), datos), datos, texto);
                    if (!"ACK".equals(Json.str(r, "tipo")))
                        SwingUtilities.invokeLater(() -> bot("⚠ " + Json.str(r, "codigo") + " — " + Json.str(r, "mensaje")));
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> bot("No se pudo enviar: " + e.getMessage()));
                }
            }).start();
        } catch (Exception e) {
            bot("No se pudo leer la imagen: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    void alEvento(Map<String, Object> m) {
        if (mensajes == null) return;
        switch (Json.str(m, "tipo")) {
            case "RESULTADO":
                burbuja("<b>BorgAICube:</b> " + escapar(textoResultado(m)), false, null, (Map<String, Object>) m.get("probabilidades"));
                break;
            case "ERROR":
                bot("⚠ " + Json.str(m, "codigo") + " — " + Json.str(m, "mensaje"));
                break;
            case "DESCONECTADO":
                estado.setText("● desconectado");
                estado.setForeground(new Color(0xff, 0xa0, 0xa0));
                bot("Se perdió la conexión con el backend.");
                break;
            default:
        }
    }

    @SuppressWarnings("unchecked")
    void historial() {
        new Thread(() -> {
            try {
                Map<String, Object> h = con.historial(15);
                StringBuilder b = new StringBuilder("<b>BorgAICube:</b> tus últimas consultas:<br>");
                for (Object o : Json.lista(h, "items")) {
                    Map<String, Object> it = (Map<String, Object>) o;
                    b.append(String.format("%s · modelo %s · %s · %s %.1f%%<br>", Json.str(it, "fecha").replace('T', ' '),
                            Json.str(it, "modelo"), Json.str(it, "estado"), escapar(Json.str(it, "etiqueta")),
                            Json.dbl(it, "probabilidad", 0) * 100));
                }
                if (Json.lista(h, "items").isEmpty()) b.append("(sin consultas todavía)");
                SwingUtilities.invokeLater(() -> burbuja(b.toString(), false, null, null));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> bot("No se pudo obtener el historial: " + e.getMessage()));
            }
        }).start();
    }

    /** Prueba de saturación desde la GUI: N consultas con delay aleatorio (usa el generador de carga). */
    void estres() {
        JTextField tN = new JTextField("1000"), tMin = new JTextField("0"), tMax = new JTextField("200"),
                tConc = new JTextField("4"), tDir = new JTextField("../datos/muestras");
        JComboBox<String> tMod = new JComboBox<>(new String[]{"MIX", "A", "B"});
        JPanel p = new JPanel(new GridLayout(0, 2, 4, 4));
        p.add(new JLabel("Transacciones:")); p.add(tN);
        p.add(new JLabel("Delay mínimo (ms):")); p.add(tMin);
        p.add(new JLabel("Delay máximo (ms):")); p.add(tMax);
        p.add(new JLabel("Sesiones simultáneas:")); p.add(tConc);
        p.add(new JLabel("Modelo:")); p.add(tMod);
        p.add(new JLabel("Carpeta de muestras:")); p.add(tDir);
        if (JOptionPane.showConfirmDialog(ventana, p, "Modo estrés", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        GeneradorCarga.Config c = new GeneradorCarga.Config();
        c.plataforma = "DESKTOP";
        c.host = host;
        c.puerto = puerto;
        c.n = Integer.parseInt(tN.getText().trim());
        c.delayMin = Integer.parseInt(tMin.getText().trim());
        c.delayMax = Integer.parseInt(tMax.getText().trim());
        c.concurrencia = Integer.parseInt(tConc.getText().trim());
        c.modelo = (String) tMod.getSelectedItem();
        c.muestras = tDir.getText().trim();
        c.salida = "../resultados/carga_DESKTOP_gui.csv";
        c.config = "gui";
        bot("Iniciando prueba de estrés: " + c.n + " transacciones...");
        new Thread(() -> {
            try {
                String resumen = GeneradorCarga.ejecutar(c, prog -> SwingUtilities.invokeLater(() -> estado.setText(prog)));
                SwingUtilities.invokeLater(() -> { bot(resumen); estado.setText("● conectado"); });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> bot("Error en la prueba de estrés: " + e.getMessage()));
            }
        }).start();
    }

    public static void main(String[] argv) {
        Args a = new Args(argv);
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("No hay entorno gráfico: use cubo.desktop.ClienteTerminal");
            return;
        }
        ClienteDesktop c = new ClienteDesktop(a.get("host", "CUBO_BACKEND_HOST", "localhost"),
                a.entero("puerto", "CUBO_BACKEND_PUERTO", 5000));
        SwingUtilities.invokeLater(c::mostrarLogin);
    }
}
