package netalertserver;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

/**
 * Network Alert System - SERVER
 *  - Cổng 9000: heartbeat TCP (client kiểm tra kết nối vào đây, trả về "PONG")
 *  - Cổng 9001: nhận tin từ client, Server trả "OK". Có 3 loại tin:
 *    + Cảnh báo, 1 dòng: STATUS|tên|host|loại|tên-client[|độ-trễ-ms[|ghi-chú[|thời-gian-sự-kiện]]]
 *      (các trường sau tên-client là tùy chọn nên vẫn tương thích với client cũ)
 *    + SNAPSHOT, gửi sau mỗi chu kỳ: dòng đầu SNAPSHOT|tên-client|chu-kỳ-s|timeout-s|số-máy,
 *      sau đó mỗi máy một dòng tên|host|loại|cổng|trạng thái|độ-trễ|TB|mất-gói-%|số-lần-lỗi
 *    + BYE|tên-client: client chủ động dừng giám sát
 *  Nếu Server đặt mã bí mật thì mọi tin phải có dòng "KEY|mã" đứng trước; sai hoặc thiếu mã thì
 *  Server trả "DENIED". Cổng và mã bí mật đặt trong hộp Cài đặt, lưu ở server.properties.
 *  Client không gửi SNAPSHOT quá MISSED_CYCLES chu kỳ thì bị coi là mất liên lạc (OFFLINE).
 */
public class ServerMain extends JFrame {

    static final int DEFAULT_HEARTBEAT_PORT = 9000;
    static final int DEFAULT_ALERT_PORT = 9001;
    static final String CONFIG_FILE = "server.properties";
    static final String LOG_FILE = "alerts.log";
    /** Tin bị từ chối từ cùng một IP chỉ ghi nhật ký 1 lần trong khoảng này, tránh ngập bảng. */
    static final long DENIED_LOG_INTERVAL_MS = 60_000;
    static final int MAX_ROWS = 1000;
    static final int MAX_SNAPSHOT_ROWS = 1000;
    static final int MISSED_CYCLES = 3;
    static final long OFFLINE_MIN_MS = 15_000;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Một dòng trong alerts.log (xem appendLog); latency và ghi chú có thể không có. */
    private static final Pattern LOG_LINE = Pattern.compile(
            "^\\[(.+?)\\] (\\w+) \\| (.+?) \\(([^()]*) - (\\w+)\\)(?: \\| latency: (.*?) ms)? \\| client: (.*?)(?: \\| (.*))?$");
    private static final String FILTER_ALL = "Tất cả";
    private static final String STOPPED = "ĐÃ DỪNG";

    /** Thông tin một Client lấy từ SNAPSHOT gần nhất. Chỉ truy cập trên EDT. */
    static class ClientInfo {
        final String name;
        String ip = "-", state = "ONLINE"; // ONLINE | OFFLINE | ĐÃ DỪNG
        int intervalSec;
        long cycleMs, lastSeen;
        List<String[]> rows = new ArrayList<>(); // mỗi máy: tên|host|loại|cổng|trạng thái|độ trễ|TB|mất gói|số lần lỗi

        ClientInfo(String name) { this.name = name; }

        int downCount() {
            int n = 0;
            for (String[] r : rows) if ("DOWN".equals(r[4])) n++;
            return n;
        }
    }

    // --- tab Trạng thái hiện tại
    private final DefaultTableModel liveModel = readOnlyModel("Client", "Tên", "Địa chỉ", "Loại", "Cổng",
            "Trạng thái", "Độ trễ (ms)", "TB (ms)", "Mất gói", "Số lần lỗi");
    private final UiKit.StatusTable liveTable = new UiKit.StatusTable(liveModel, 5);

    // --- tab Client
    private final DefaultTableModel clientModel = readOnlyModel("Client", "IP", "Trạng thái", "Số máy giám sát",
            "Đang DOWN", "Chu kỳ (s)", "Tín hiệu cuối");
    private final UiKit.StatusTable clientTable = new UiKit.StatusTable(clientModel, 2);

    // --- tab Nhật ký cảnh báo
    private final DefaultTableModel model = readOnlyModel("Thời gian", "Trạng thái", "Máy chủ", "Địa chỉ", "Loại",
            "Độ trễ (ms)", "Ghi chú", "Client");
    private final UiKit.StatusTable table = new UiKit.StatusTable(model, 1);
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
    private final JComboBox<String> cboFilter = new JComboBox<>(
            new String[]{FILTER_ALL, "DOWN", "UP", "OFFLINE", "ONLINE", "DENIED"});
    private final JTextField txtSearch = new JTextField(16);

    private final JLabel lblStatus = new JLabel(" Đang khởi động...");
    private final UiKit.StatCard cardNowDown = new UiKit.StatCard("Đang mất kết nối");
    private final UiKit.StatCard cardDown = new UiKit.StatCard("Cảnh báo DOWN");
    private final UiKit.StatCard cardUp = new UiKit.StatCard("Lần khôi phục UP");
    private final UiKit.StatCard cardClients = new UiKit.StatCard("Client đang online");
    private final UiKit.AlertBox alertBox;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });
    private final Object logLock = new Object();

    // --- cấu hình (server.properties); cổng chỉ đổi được khi khởi động lại
    private int heartbeatPort = DEFAULT_HEARTBEAT_PORT, alertPort = DEFAULT_ALERT_PORT;
    private volatile String secretKey = "";

    // --- số liệu, chỉ truy cập trên EDT
    private int downCount = 0, upCount = 0;
    private final Map<String, ClientInfo> clientInfos = new LinkedHashMap<>();
    private final Map<String, Long> lastDenied = new HashMap<>();
    private final List<String> ips = new ArrayList<>();
    private String zeroTierIp = null;

    public ServerMain() {
        super("Network Alert Server");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        UiKit.sizeToScreen(this, 1100, 680);
        alertBox = new UiKit.AlertBox(this);

        JPanel cards = new JPanel(new GridLayout(1, 4, 12, 0));
        cards.add(cardNowDown); cards.add(cardDown); cards.add(cardUp); cards.add(cardClients);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Trạng thái hiện tại", buildLiveTab());
        tabs.addTab("Client", buildClientTab());
        tabs.addTab("Nhật ký cảnh báo", buildLogTab());

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(cards, BorderLayout.NORTH);
        content.add(tabs, BorderLayout.CENTER);

        JButton btnCopyIp = new JButton("Sao chép IP");
        btnCopyIp.setToolTipText("Sao chép IP ZeroTier của máy này để dán vào ô Server của Client");
        btnCopyIp.addActionListener(e -> copyIp());
        JButton btnSettings = new JButton("Cài đặt");
        btnSettings.setToolTipText("Đổi cổng và mã bí mật của Server");
        btnSettings.addActionListener(e -> showSettings());
        JPanel statusButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        statusButtons.add(btnSettings);
        statusButtons.add(btnCopyIp);

        add(content, BorderLayout.CENTER);
        add(UiKit.statusBar(lblStatus, statusButtons), BorderLayout.SOUTH);

        loadConfig();
        updateCount();
        showIps();
        loadLog();
        startHeartbeat();
        startAlertReceiver();
        new Timer(2000, e -> checkClients()).start();
    }

    private static DefaultTableModel readOnlyModel(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private JPanel buildLiveTab() {
        liveTable.getColumnModel().getColumn(5).setCellRenderer(new StatusRenderer());
        LatencyRenderer lr = new LatencyRenderer();
        liveTable.getColumnModel().getColumn(6).setCellRenderer(lr);
        liveTable.getColumnModel().getColumn(7).setCellRenderer(lr);
        liveTable.setColumnWidths(140, 140, 120, 50, 50, 80, 85, 70, 65, 75);
        TableRowSorter<DefaultTableModel> s = new TableRowSorter<>(liveModel);
        for (int c : new int[]{4, 6, 7, 8, 9}) s.setComparator(c, UiKit.NUMERIC);
        liveTable.setRowSorter(s);
        return tabPanel(new JScrollPane(liveTable),
                "Mỗi Client gửi trạng thái các máy nó giám sát sau mỗi chu kỳ kiểm tra. "
                        + "Client mất liên lạc thì các máy của nó hiện CHƯA RÕ.");
    }

    private JPanel buildClientTab() {
        clientTable.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());
        clientTable.setColumnWidths(160, 120, 90, 110, 90, 80, 120);
        TableRowSorter<DefaultTableModel> s = new TableRowSorter<>(clientModel);
        for (int c : new int[]{3, 4, 5}) s.setComparator(c, UiKit.NUMERIC);
        clientTable.setRowSorter(s);
        return tabPanel(new JScrollPane(clientTable),
                "Quá " + MISSED_CYCLES + " chu kỳ (tối thiểu " + OFFLINE_MIN_MS / 1000
                        + " giây) không nhận được tín hiệu thì Client bị coi là OFFLINE. "
                        + "Bấm Dừng ở Client thì hiện " + STOPPED + " và không báo động.");
    }

    private JPanel buildLogTab() {
        table.getColumnModel().getColumn(1).setCellRenderer(new StatusRenderer());
        table.getColumnModel().getColumn(5).setCellRenderer(new LatencyRenderer());
        table.setColumnWidths(140, 80, 140, 120, 60, 80, 220, 130);
        sorter.setComparator(5, UiKit.NUMERIC);
        table.setRowSorter(sorter);

        cboFilter.addActionListener(e -> applyFilter());
        txtSearch.setToolTipText("Tìm theo tên máy, địa chỉ, client hoặc ghi chú");
        txtSearch.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        JButton btnClear = new JButton("Xóa bảng");
        btnClear.addActionListener(e -> clearTable());
        JButton btnExport = new JButton("Xuất CSV");
        btnExport.setToolTipText("Xuất các dòng đang hiện (theo bộ lọc) ra file CSV");
        btnExport.addActionListener(e -> UiKit.exportCsv(this, table, "nhat-ky-canh-bao"));

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tools.add(new JLabel("Trạng thái:")); tools.add(cboFilter);
        tools.add(Box.createHorizontalStrut(8));
        tools.add(new JLabel("Tìm:")); tools.add(txtSearch);
        tools.add(Box.createHorizontalStrut(16));
        tools.add(btnExport); tools.add(btnClear);

        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        p.add(tools, BorderLayout.NORTH);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        return p;
    }

    private static JPanel tabPanel(JComponent body, String hint) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        p.add(body, BorderLayout.CENTER);
        p.add(UiKit.hint(hint), BorderLayout.SOUTH);
        return p;
    }

    // ------------------------------------------------------------ mạng
    /** Cổng heartbeat (mặc định 9000): ai kết nối vào cũng được trả lời PONG. */
    private void startHeartbeat() {
        int port = heartbeatPort;
        Thread t = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(port)) {
                while (true) {
                    Socket s = ss.accept();
                    pool.submit(() -> {
                        try (Socket c = s) {
                            c.getOutputStream().write("PONG\n".getBytes(StandardCharsets.UTF_8));
                            c.getOutputStream().flush();
                        } catch (IOException ignored) { }
                    });
                }
            } catch (IOException e) {
                portError(port, e);
            }
        }, "heartbeat");
        t.setDaemon(true);
        t.start();
    }

    /** Cổng báo cáo (mặc định 9001): nhận tin từ client. */
    private void startAlertReceiver() {
        int port = alertPort;
        Thread t = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(port)) {
                while (true) {
                    Socket s = ss.accept();
                    pool.submit(() -> handleConnection(s));
                }
            } catch (IOException e) {
                portError(port, e);
            }
        }, "alert-receiver");
        t.setDaemon(true);
        t.start();
    }

    private void portError(int port, IOException e) {
        String msg = "Lỗi cổng " + port + ": " + e.getMessage()
                + " (cổng đang bị chương trình khác dùng? Server đã mở sẵn chưa?)";
        SwingUtilities.invokeLater(() -> {
            lblStatus.setText(" " + msg);
            lblStatus.setForeground(UiKit.BAD);
            JOptionPane.showMessageDialog(this, msg, "Lỗi khởi động", JOptionPane.ERROR_MESSAGE);
        });
    }

    /** Đọc một tin, xử lý theo loại rồi trả "OK". Tin sai định dạng thì đóng kết nối, không trả lời. */
    private void handleConnection(Socket s) {
        try (Socket c = s) {
            c.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) return;
            String ip = c.getInetAddress().getHostAddress();

            // mã bí mật: dòng "KEY|mã" đứng trước tin; Server không đặt mã thì bỏ qua dòng này
            String key = secretKey, given = null;
            if (line.startsWith("KEY|")) {
                given = line.substring(4);
                line = in.readLine();
                if (line == null) return;
            }
            if (!key.isEmpty() && (given == null || !MessageDigest.isEqual(
                    given.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8)))) {
                c.getOutputStream().write("DENIED\n".getBytes(StandardCharsets.UTF_8));
                c.getOutputStream().flush();
                boolean missing = given == null;
                SwingUtilities.invokeLater(() -> noteDenied(ip, missing));
                return;
            }

            boolean ok;
            if (line.startsWith("SNAPSHOT|")) ok = handleSnapshot(line, in, ip);
            else if (line.startsWith("BYE|")) ok = handleBye(line);
            else ok = handleAlert(line);
            if (!ok) return;

            c.getOutputStream().write("OK\n".getBytes(StandardCharsets.UTF_8));
            c.getOutputStream().flush();
        } catch (IOException ignored) { }
    }

    private boolean handleAlert(String line) {
        String[] p = line.split("\\|", -1);
        if (p.length < 5) return false;

        String status = p[0], name = p[1], host = p[2], type = p[3], client = p[4];
        String latency = p.length > 5 && !p[5].isEmpty() ? p[5] : "-";
        String note = p.length > 6 ? p[6] : "";
        String time = p.length > 7 && !p[7].isEmpty() ? p[7] : LocalDateTime.now().format(FMT);
        appendLog(time, status, name, host, type, latency, client, note);

        final boolean down = "DOWN".equals(status);
        SwingUtilities.invokeLater(() -> {
            addRow(time, status, name, host, type, latency, note, client);
            alertBox.show((down ? "MẤT KẾT NỐI: " : "ĐÃ KHÔI PHỤC: ") + name,
                    name + " (" + host + ")\nBáo cáo từ client: " + client
                            + ("-".equals(latency) ? "" : "\nĐộ trễ: " + latency + " ms")
                            + (note.isEmpty() ? "" : "\n" + note),
                    down);
        });
        return true;
    }

    private boolean handleSnapshot(String header, BufferedReader in, String ip) throws IOException {
        String[] h = header.split("\\|", -1);
        if (h.length < 5) return false;
        String client = h[1];
        int interval = parseInt(h[2], 5), timeout = parseInt(h[3], 3);
        int n = Math.min(parseInt(h[4], 0), MAX_SNAPSHOT_ROWS);
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String l = in.readLine();
            if (l == null) return false;
            String[] p = l.split("\\|", -1);
            if (p.length >= 9) rows.add(p);
        }
        // một chu kỳ của client dài tối đa: chờ interval + timeout của lệnh ping/TCP + ~2 giây chạy lệnh
        long cycleMs = (interval + timeout + 2) * 1000L;
        SwingUtilities.invokeLater(() -> applySnapshot(client, ip, interval, cycleMs, rows));
        return true;
    }

    private boolean handleBye(String line) {
        String[] p = line.split("\\|", -1);
        if (p.length < 2) return false;
        SwingUtilities.invokeLater(() -> {
            ClientInfo ci = clientInfos.get(p[1]);
            if (ci == null) return;
            ci.state = STOPPED;
            refreshLive();
        });
        return true;
    }

    /** Ghi nhận tin bị từ chối vì sai/thiếu mã bí mật (mỗi IP tối đa 1 dòng mỗi phút). Gọi trên EDT. */
    private void noteDenied(String ip, boolean missing) {
        long now = System.currentTimeMillis();
        Long last = lastDenied.get(ip);
        if (last != null && now - last < DENIED_LOG_INTERVAL_MS) return;
        lastDenied.put(ip, now);
        String time = LocalDateTime.now().format(FMT);
        String note = (missing ? "Tin không có mã bí mật" : "Sai mã bí mật") + ", đã từ chối";
        appendLog(time, "DENIED", "(không rõ)", ip, "KEY", "-", "?", note);
        addRow(time, "DENIED", "(không rõ)", ip, "KEY", "-", note, "?");
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    /** Ghi một dòng vào alerts.log (nhiều luồng cùng ghi nên phải đồng bộ). */
    private void appendLog(String time, String status, String name, String host, String type,
                           String latency, String client, String note) {
        synchronized (logLock) {
            try (PrintWriter w = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(LOG_FILE, true), StandardCharsets.UTF_8))) {
                w.println(String.format("[%s] %s | %s (%s - %s) | latency: %s ms | client: %s%s",
                        time, status, name, host, type, latency, client,
                        note.isEmpty() ? "" : " | " + note));
            } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------ trạng thái client (EDT)
    private void applySnapshot(String client, String ip, int intervalSec, long cycleMs, List<String[]> rows) {
        ClientInfo ci = clientInfos.computeIfAbsent(client, ClientInfo::new);
        boolean wasOffline = "OFFLINE".equals(ci.state);
        ci.ip = ip;
        ci.intervalSec = intervalSec;
        ci.cycleMs = cycleMs;
        ci.rows = rows;
        ci.lastSeen = System.currentTimeMillis();
        ci.state = "ONLINE";
        if (wasOffline) clientEvent("ONLINE", ci, "Client liên lạc lại được");
        refreshLive();
    }

    /** Chạy mỗi 2 giây: đánh dấu OFFLINE các client quá lâu không gửi tín hiệu, cập nhật "Tín hiệu cuối". */
    private void checkClients() {
        long now = System.currentTimeMillis();
        for (ClientInfo ci : clientInfos.values()) {
            long limit = Math.max(MISSED_CYCLES * ci.cycleMs, OFFLINE_MIN_MS);
            if ("ONLINE".equals(ci.state) && now - ci.lastSeen > limit) {
                ci.state = "OFFLINE";
                clientEvent("OFFLINE", ci, "Không nhận được tín hiệu trong " + (now - ci.lastSeen) / 1000 + " giây");
            }
        }
        refreshLive();
    }

    /** Ghi sự kiện của chính client (OFFLINE / ONLINE) vào nhật ký và báo động. */
    private void clientEvent(String status, ClientInfo ci, String note) {
        boolean offline = "OFFLINE".equals(status);
        String time = LocalDateTime.now().format(FMT);
        appendLog(time, status, ci.name, ci.ip, "CLIENT", "-", ci.name, note);
        addRow(time, status, ci.name, ci.ip, "CLIENT", "-", note, ci.name);
        alertBox.show((offline ? "CLIENT MẤT LIÊN LẠC: " : "CLIENT LIÊN LẠC LẠI: ") + ci.name,
                ci.name + " (" + ci.ip + ")\n" + note, offline);
    }

    /** Vẽ lại tab Trạng thái hiện tại, tab Client và các thẻ số liệu. */
    private void refreshLive() {
        long now = System.currentTimeMillis();
        List<Object[]> live = new ArrayList<>(), cls = new ArrayList<>();
        for (ClientInfo ci : clientInfos.values()) {
            boolean online = "ONLINE".equals(ci.state);
            for (String[] r : ci.rows) {
                String st = online && ("UP".equals(r[4]) || "DOWN".equals(r[4])) ? r[4] : "CHƯA RÕ";
                live.add(new Object[]{ci.name, r[0], r[1], r[2], "TCP".equals(r[2]) ? r[3] : "-",
                        st, r[5], r[6], "-".equals(r[7]) ? "-" : r[7] + "%", r[8]});
            }
            cls.add(new Object[]{ci.name, ci.ip, ci.state, ci.rows.size(), ci.downCount(), ci.intervalSec,
                    (now - ci.lastSeen) / 1000 + " giây trước"});
        }
        refill(liveTable, liveModel, live);
        refill(clientTable, clientModel, cls);
        updateCount();
    }

    /** Thay toàn bộ dòng của bảng nhưng giữ dòng đang chọn. */
    private static void refill(JTable t, DefaultTableModel m, List<Object[]> rows) {
        int sel = t.getSelectedRow();
        int selModel = sel >= 0 ? t.convertRowIndexToModel(sel) : -1;
        m.setRowCount(0);
        for (Object[] r : rows) m.addRow(r);
        if (selModel >= 0 && selModel < m.getRowCount()) {
            int v = t.convertRowIndexToView(selModel);
            if (v >= 0) t.setRowSelectionInterval(v, v);
        }
    }

    // ------------------------------------------------------------ nhật ký cảnh báo (EDT)
    /** Thêm một sự kiện lên đầu bảng nhật ký và cập nhật các thẻ số liệu. */
    private void addRow(String time, String status, String name, String host, String type,
                        String latency, String note, String client) {
        model.insertRow(0, new Object[]{time, status, name, host, type, latency, note, client});
        if (model.getRowCount() > MAX_ROWS) model.removeRow(model.getRowCount() - 1);
        if ("DOWN".equals(status)) downCount++;
        else if ("UP".equals(status)) upCount++;
        updateCount();
    }

    /** Đọc lại alerts.log khi mở Server để vẫn thấy các cảnh báo cũ. */
    private void loadLog() {
        Path path = Paths.get(LOG_FILE);
        if (!Files.exists(path)) return;
        int loaded = 0;
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines.subList(Math.max(0, lines.size() - MAX_ROWS), lines.size())) {
                Matcher m = LOG_LINE.matcher(line);
                if (!m.matches()) continue;
                addRow(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5),
                        m.group(6) == null ? "-" : m.group(6), m.group(8) == null ? "" : m.group(8), m.group(7));
                loaded++;
            }
        } catch (IOException e) { /* bỏ qua, bắt đầu với bảng trống */ }
        if (loaded > 0) lblStatus.setText(lblStatus.getText() + "  ·  Đã nạp " + loaded + " cảnh báo từ " + LOG_FILE);
    }

    private void clearTable() {
        if (JOptionPane.showConfirmDialog(this,
                "Xóa toàn bộ cảnh báo trên bảng?\n(File " + LOG_FILE + " vẫn được giữ nguyên.)",
                "Xác nhận xóa", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        model.setRowCount(0);
        downCount = 0;
        upCount = 0;
        updateCount();
    }

    private void applyFilter() {
        List<RowFilter<Object, Object>> filters = new ArrayList<>();
        String st = (String) cboFilter.getSelectedItem();
        if (!FILTER_ALL.equals(st)) filters.add(RowFilter.regexFilter("^" + st + "$", 1));
        String q = txtSearch.getText().trim();
        if (!q.isEmpty()) filters.add(RowFilter.regexFilter("(?iu)" + Pattern.quote(q)));
        sorter.setRowFilter(filters.isEmpty() ? null : RowFilter.andFilter(filters));
    }

    private void updateCount() {
        int nowDown = 0, online = 0;
        boolean anyOffline = false;
        for (ClientInfo ci : clientInfos.values()) {
            if ("ONLINE".equals(ci.state)) {
                online++;
                nowDown += ci.downCount();
            }
            if ("OFFLINE".equals(ci.state)) anyOffline = true;
        }
        cardNowDown.setValue(nowDown);
        cardNowDown.setAlert(nowDown > 0);
        cardDown.setValue(downCount);
        cardUp.setValue(upCount);
        cardClients.setValue(online + "/" + clientInfos.size());
        cardClients.setAlert(anyOffline);
    }

    // ------------------------------------------------------------ IP
    /** Hiển thị các IP của máy (trong đó có IP ZeroTier) để dán vào client. */
    private void showIps() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                boolean zt = ni.getDisplayName().toLowerCase().contains("zerotier") || ni.getName().startsWith("zt");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        ips.add(a.getHostAddress());
                        if (zt && zeroTierIp == null) zeroTierIp = a.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) { }
        StringBuilder sb = new StringBuilder(" Server đang chạy  ·  heartbeat :" + heartbeatPort
                + "  ·  alert :" + alertPort + (secretKey.isEmpty() ? "" : "  ·  có mã bí mật") + "  ·  ");
        sb.append(zeroTierIp != null ? "IP ZeroTier: " + zeroTierIp : "Chưa thấy ZeroTier, IP máy: " + String.join("  ", ips));
        lblStatus.setText(sb.toString());
    }

    private void copyIp() {
        String ip = zeroTierIp != null ? zeroTierIp : ips.isEmpty() ? null : ips.get(0);
        if (ip == null) {
            JOptionPane.showMessageDialog(this, "Không tìm thấy IP nào của máy này.");
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(ip), null);
        JOptionPane.showMessageDialog(this, "Đã sao chép " + ip
                + (zeroTierIp == null ? " (không phải IP ZeroTier)" : "")
                + "\nDán vào ô \"Server ZeroTier IP\" ở tab Cấu hình của Client.");
    }

    // ------------------------------------------------------------ cấu hình
    private void loadConfig() {
        Path path = Paths.get(CONFIG_FILE);
        if (!Files.exists(path)) return;
        Properties pr = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            pr.load(in);
            heartbeatPort = parsePort(pr.getProperty("heartbeatPort"), DEFAULT_HEARTBEAT_PORT);
            alertPort = parsePort(pr.getProperty("alertPort"), DEFAULT_ALERT_PORT);
            secretKey = pr.getProperty("secret", "").trim();
        } catch (IOException e) { /* bỏ qua, dùng mặc định */ }
    }

    private static int parsePort(String s, int def) {
        int p = s == null ? def : parseInt(s, def);
        return p >= 1 && p <= 65535 ? p : def;
    }

    /** Hộp Cài đặt: mã bí mật có hiệu lực ngay, cổng mới có hiệu lực sau khi mở lại Server. */
    private void showSettings() {
        JTextField txtHb = new JTextField(String.valueOf(heartbeatPort), 6);
        JTextField txtAlert = new JTextField(String.valueOf(alertPort), 6);
        JTextField txtKey = new JTextField(secretKey, 16);
        JPanel p = new JPanel(new GridLayout(0, 2, 8, 6));
        p.add(new JLabel("Cổng heartbeat:")); p.add(txtHb);
        p.add(new JLabel("Cổng báo cáo:")); p.add(txtAlert);
        p.add(new JLabel("Mã bí mật:")); p.add(txtKey);
        JPanel box = new JPanel(new BorderLayout(0, 10));
        box.add(p, BorderLayout.CENTER);
        box.add(UiKit.hint("<html>Để trống mã bí mật thì nhận tin từ mọi Client.<br>"
                + "Có mã thì Client phải nhập đúng mã ở tab Cấu hình, nếu không sẽ bị từ chối.<br>"
                + "Đổi cổng thì phải mở lại Server và sửa Cổng báo cáo ở các Client.</html>"), BorderLayout.SOUTH);

        if (JOptionPane.showConfirmDialog(this, box, "Cài đặt Server",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        int hb = parsePort(txtHb.getText().trim(), -1), al = parsePort(txtAlert.getText().trim(), -1);
        if (hb < 0 || al < 0 || hb == al) {
            JOptionPane.showMessageDialog(this, "Cổng phải là số từ 1 đến 65535 và hai cổng phải khác nhau.");
            return;
        }
        boolean portsChanged = hb != heartbeatPort || al != alertPort;
        secretKey = txtKey.getText().trim();

        Properties pr = new Properties();
        pr.setProperty("heartbeatPort", String.valueOf(hb));
        pr.setProperty("alertPort", String.valueOf(al));
        pr.setProperty("secret", secretKey);
        try (OutputStream o = Files.newOutputStream(Paths.get(CONFIG_FILE))) {
            pr.store(o, "Network Alert Server config");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Không lưu được " + CONFIG_FILE + ": " + e.getMessage());
            return;
        }
        if (!portsChanged) showIps();
        JOptionPane.showMessageDialog(this, portsChanged
                ? "Đã lưu. Mở lại Server để dùng cổng " + hb + " / " + al + "."
                : "Đã lưu. Mã bí mật có hiệu lực ngay.");
    }

    // ------------------------------------------------------------ renderer
    /** Đỏ: DOWN / OFFLINE / DENIED, xanh: UP / ONLINE, xám: còn lại (CHƯA RÕ, ĐÃ DỪNG). */
    static class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                       boolean focus, int r, int c) {
            Component comp = super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            if (!sel) {
                comp.setForeground("DOWN".equals(v) || "OFFLINE".equals(v) || "DENIED".equals(v) ? UiKit.BAD
                        : "UP".equals(v) || "ONLINE".equals(v) ? UiKit.GOOD : Color.GRAY);
            }
            setFont(getFont().deriveFont(Font.BOLD));
            return comp;
        }
    }

    /** Tô màu độ trễ: xanh < 100ms, cam < 300ms, đỏ còn lại. */
    static class LatencyRenderer extends DefaultTableCellRenderer {
        LatencyRenderer() { setHorizontalAlignment(CENTER); }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                       boolean focus, int r, int c) {
            Component comp = super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            if (!sel) {
                Color col = Color.GRAY;
                try {
                    double ms = Double.parseDouble(String.valueOf(v));
                    col = ms < 100 ? new Color(0, 130, 0)
                            : ms < 300 ? new Color(200, 120, 0) : new Color(200, 0, 0);
                } catch (NumberFormatException ignored) { }
                comp.setForeground(col);
            }
            return comp;
        }
    }

    public static void main(String[] args) {
        UiKit.useSystemLookAndFeel();
        SwingUtilities.invokeLater(() -> new ServerMain().setVisible(true));
    }
}
