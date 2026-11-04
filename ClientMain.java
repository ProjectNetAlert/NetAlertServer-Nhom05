package netalertserver;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

/**
 * Network Alert System - CLIENT
 * Liên tục kiểm tra Ping/TCP tới danh sách máy chủ, đo tốc độ kết nối (độ trễ),
 * cảnh báo khi mất kết nối và gửi báo cáo về Server (qua mạng ZeroTier).
 */
public class ClientMain extends JFrame {

    // ------------------------------------------------------------ dữ liệu
    static class Target {
        final String name, host, type; // type: TCP | PING
        final int port;
        volatile String status = "UNKNOWN";
        volatile int fails = 0;
        volatile long downSince = 0;
        volatile String lastCheck = "-";
        volatile boolean mailSent = false; // đã gửi mail DOWN cho lần sự cố này chưa

        // --- đo tốc độ kết nối
        volatile double latency = -1;   // ms của lần kiểm tra gần nhất (-1 = lỗi / chưa đo)
        volatile double sumLatency = 0; // tổng ms các lần thành công
        volatile int okCount = 0;       // số lần thành công
        volatile int totalCount = 0;    // tổng số lần kiểm tra

        // --- thống kê (tính từ lúc bấm Bắt đầu)
        volatile double minLatency = -1, maxLatency = -1;
        volatile int incidents = 0;     // số lần chuyển sang DOWN
        volatile long downMsTotal = 0;  // tổng thời gian DOWN của các sự cố đã kết thúc
        volatile long startedAt = 0;    // lần kiểm tra đầu tiên
        volatile long lastCheckMs = 0;  // lần kiểm tra gần nhất
        /** Độ trễ các lần kiểm tra gần đây cho biểu đồ (ms < 0 = lỗi). Đồng bộ trên chính nó. */
        final ArrayDeque<UiKit.LatencyChart.Sample> history = new ArrayDeque<>();

        Target(String name, String host, String type, int port) {
            this.name = name; this.host = host; this.type = type; this.port = port;
        }

        void resetState() {
            status = "UNKNOWN"; fails = 0; downSince = 0; mailSent = false;
            lastCheck = "-"; latency = -1; sumLatency = 0; okCount = 0; totalCount = 0;
            minLatency = -1; maxLatency = -1; incidents = 0; downMsTotal = 0; startedAt = 0; lastCheckMs = 0;
            synchronized (history) { history.clear(); }
        }

        double avgLatency() { return okCount == 0 ? -1 : sumLatency / okCount; }
        double lossPercent() { return totalCount == 0 ? 0 : 100.0 * (totalCount - okCount) / totalCount; }

        /** Tổng thời gian DOWN tính tới lần kiểm tra gần nhất, kể cả sự cố đang diễn ra. */
        long downMs() {
            return downMsTotal + ("DOWN".equals(status) && downSince > 0 ? lastCheckMs - downSince : 0);
        }

        /** % thời gian hoạt động theo thời gian (không theo số lần kiểm tra); -1 nếu chưa đo. */
        double uptimePercent() {
            long elapsed = lastCheckMs - startedAt;
            return startedAt == 0 || elapsed <= 0 ? -1 : 100.0 * (elapsed - downMs()) / elapsed;
        }

        void addHistory(long time, double ms) {
            synchronized (history) {
                history.addLast(new UiKit.LatencyChart.Sample(time, ms));
                while (history.size() > MAX_HISTORY) history.removeFirst();
            }
        }

        List<UiKit.LatencyChart.Sample> historyCopy() {
            synchronized (history) { return new ArrayList<>(history); }
        }
    }

    private static final String TARGET_FILE = "targets.txt";
    private static final String CONFIG_FILE = "client.properties";
    private static final String MAIL_FILE = "email.properties";
    private static final int MAX_PENDING_REPORTS = 200;
    private static final int MAX_LOG_LINES = 2000;
    /** Số lần kiểm tra gần nhất giữ lại cho biểu đồ (chu kỳ 5 giây = 50 phút). */
    private static final int MAX_HISTORY = 600;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FMT_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Lấy thời gian phản hồi từ output của lệnh ping: "time=12.3 ms", "time<1ms", "time=12ms". */
    private static final Pattern PING_TIME = Pattern.compile("[=<]\\s*([0-9]+(?:[.,][0-9]+)?)\\s*ms",
            Pattern.CASE_INSENSITIVE);

    private final List<Target> targets = Collections.synchronizedList(new ArrayList<>());
    private final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    private final boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");
    private final ConcurrentLinkedQueue<String> pendingReports = new ConcurrentLinkedQueue<>();

    // ------------------------------------------------------------ giao diện
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Tên", "Địa chỉ", "Loại", "Cổng", "Trạng thái",
                    "Độ trễ (ms)", "TB (ms)", "Mất gói", "Số lần lỗi", "Kiểm tra lúc"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final UiKit.StatusTable table = new UiKit.StatusTable(model, 4);
    private final DefaultTableModel statsModel = new DefaultTableModel(
            new String[]{"Tên", "Địa chỉ", "Uptime", "Số sự cố", "Thời gian gián đoạn", "TB (ms)",
                    "Min (ms)", "Max (ms)", "Mất gói", "Số lần kiểm tra", "Thời gian giám sát"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable statsTable = new JTable(statsModel);
    private final UiKit.LatencyChart chart = new UiKit.LatencyChart();
    private final JTextArea logArea = new JTextArea(8, 60);
    private final JToggleButton btnLogErrors = new JToggleButton("Chỉ lỗi");
    private final List<LogLine> logLines = new ArrayList<>(); // chỉ truy cập trên EDT
    private final UiKit.StatCard cardTotal = new UiKit.StatCard("Tổng số máy");
    private final UiKit.StatCard cardUp = new UiKit.StatCard("UP");
    private final UiKit.StatCard cardDown = new UiKit.StatCard("DOWN");
    private final UiKit.StatCard cardLatency = new UiKit.StatCard("Độ trễ trung bình (ms)");
    private final JLabel lblServerStatus = new JLabel();
    private final JLabel lblMonitorStatus = new JLabel("Chưa bắt đầu giám sát");
    private UiKit.AlertBox alertBox;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextField txtServer = new JTextField(12);
    private final JTextField txtServerPort = new JTextField("9001", 5);
    private final JTextField txtSecret = new JTextField(12);
    private final JSpinner spnInterval = new JSpinner(new SpinnerNumberModel(5, 1, 3600, 1));
    private final JSpinner spnThreshold = new JSpinner(new SpinnerNumberModel(3, 1, 20, 1));
    private final JSpinner spnTimeout = new JSpinner(new SpinnerNumberModel(3, 1, 30, 1));
    private final JButton btnStart = new JButton("▶ Bắt đầu");
    private final JButton btnStop = new JButton("■ Dừng");
    private final JButton btnEdit = new JButton("Sửa");

    // --- cấu hình Gmail
    private final JCheckBox chkEmail = new JCheckBox("Gửi email");
    private final JTextField txtMailUser = new JTextField(16);
    private final JPasswordField txtMailPass = new JPasswordField(12);
    private final JTextField txtMailTo = new JTextField(22);
    private final JButton btnSendNotice = new JButton("✉ Gửi thông báo");
    /** Sau số lần kiểm tra lỗi liên tiếp này thì tự động gửi mail cảnh báo. */
    private final JSpinner spnMailAfter = new JSpinner(new SpinnerNumberModel(5, 1, 100, 1));
    private final JCheckBox chkSavePass = new JCheckBox("Lưu App Password trên máy này");
    private volatile int mailAfterFails;
    private volatile boolean mailEnabled;
    private volatile String mailUser = "", mailPass = "", mailTo = "";

    // ------------------------------------------------------------ chạy nền
    private volatile int intervalSec, threshold, timeoutSec, serverPort;
    private volatile String serverHost, secret = "";
    /** Kết quả lần liên lạc gần nhất với Server: null = chưa liên lạc, "OK", "DENIED" (sai mã bí mật), "FAIL". */
    private volatile String serverState = null;
    private ScheduledExecutorService scheduler;
    private ExecutorService pool;

    private record LogLine(String text, boolean error) { }

    public ClientMain() {
        super("Network Alert Client");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        UiKit.sizeToScreen(this, 1200, 780);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (isMonitoring()) sendBye(); // tắt chủ động, không phải mất liên lạc
            }
        });
        alertBox = new UiKit.AlertBox(this);
        buildUi();
        loadConfig();
        loadMailSettings();
        loadTargets();
        refreshTable();
        updateStatusBar();
        btnStop.setEnabled(false);
    }

    // 4 tab: Giám sát (số liệu + bảng + nhật ký), Thống kê, Cấu hình, Email; thanh trạng thái ở dưới cùng
    private void buildUi() {
        tabs.addTab("Giám sát", buildMonitorTab());
        tabs.addTab("Thống kê", buildStatsTab());
        tabs.addTab("Cấu hình", buildConfigTab());
        tabs.addTab("Email", buildMailTab());
        add(tabs, BorderLayout.CENTER);
        add(UiKit.statusBar(lblServerStatus, lblMonitorStatus), BorderLayout.SOUTH);

        btnStart.addActionListener(e -> startMonitoring());
        btnStop.addActionListener(e -> stopMonitoring());
        btnSendNotice.addActionListener(e -> sendNotice());
    }

    private JPanel buildMonitorTab() {
        JPanel cards = new JPanel(new GridLayout(1, 4, 12, 0));
        cards.add(cardTotal); cards.add(cardUp); cards.add(cardDown); cards.add(cardLatency);

        JButton btnAdd = new JButton("+ Thêm");
        JButton btnDel = new JButton("Xóa");
        btnAdd.addActionListener(e -> addTargetDialog());
        btnEdit.addActionListener(e -> editSelected());
        btnDel.addActionListener(e -> removeSelected());
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tools.add(btnStart); tools.add(btnStop);
        tools.add(Box.createHorizontalStrut(16));
        tools.add(btnAdd); tools.add(btnEdit); tools.add(btnDel);

        JPanel north = new JPanel(new BorderLayout(0, 10));
        north.add(cards, BorderLayout.NORTH);
        north.add(tools, BorderLayout.SOUTH);

        setupTable();
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), buildLogPanel());
        split.setResizeWeight(0.7);
        split.setBorder(null);

        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        p.add(north, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private void setupTable() {
        table.getColumnModel().getColumn(4).setCellRenderer(new StatusRenderer());
        LatencyRenderer lr = new LatencyRenderer();
        table.getColumnModel().getColumn(5).setCellRenderer(lr);
        table.getColumnModel().getColumn(6).setCellRenderer(lr);
        table.setColumnWidths(150, 130, 50, 50, 80, 85, 70, 65, 75, 95);

        // bấm tiêu đề cột để sắp xếp; các cột số so sánh theo giá trị chứ không theo chữ
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        for (int c : new int[]{3, 5, 6, 7, 8}) sorter.setComparator(c, UiKit.NUMERIC);
        table.setRowSorter(sorter);

        // nháy đúp để sửa, chuột phải để mở menu Sửa / Xóa
        JPopupMenu menu = new JPopupMenu();
        JMenuItem miEdit = new JMenuItem("Sửa"), miDel = new JMenuItem("Xóa");
        miEdit.addActionListener(e -> editSelected());
        miDel.addActionListener(e -> removeSelected());
        menu.add(miEdit); menu.add(miDel);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { showMenu(e); }
            @Override public void mouseReleased(MouseEvent e) { showMenu(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) editSelected();
            }
            private void showMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int r = table.rowAtPoint(e.getPoint());
                if (r < 0) return;
                table.setRowSelectionInterval(r, r);
                menu.show(table, e.getX(), e.getY());
            }
        });
    }

    private JPanel buildLogPanel() {
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JButton btnClear = new JButton("Xóa nhật ký");
        btnLogErrors.addActionListener(e -> renderLog());
        btnClear.addActionListener(e -> { logLines.clear(); renderLog(); });

        JLabel title = new JLabel("Nhật ký");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(btnLogErrors); buttons.add(btnClear);
        JPanel head = new JPanel(new BorderLayout());
        head.add(title, BorderLayout.WEST);
        head.add(buttons, BorderLayout.EAST);

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        p.add(head, BorderLayout.NORTH);
        p.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildStatsTab() {
        statsTable.setRowHeight(26);
        statsTable.setFillsViewportHeight(true);
        statsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(statsModel);
        for (int c : new int[]{2, 3, 5, 6, 7, 8, 9}) sorter.setComparator(c, UiKit.NUMERIC);
        statsTable.setRowSorter(sorter);
        statsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateChart();
        });

        JButton btnExport = new JButton("Xuất CSV");
        btnExport.addActionListener(e -> UiKit.exportCsv(this, statsTable, "thong-ke"));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tools.add(btnExport);
        tools.add(UiKit.hint("Số liệu tính từ lúc bấm Bắt đầu. Chọn một dòng để xem biểu đồ độ trễ của máy đó."));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(statsTable), chart);
        split.setResizeWeight(0.45);
        split.setBorder(null);

        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        p.add(tools, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    /** Vẽ biểu đồ cho máy đang chọn ở tab Thống kê. Gọi trên EDT. */
    private void updateChart() {
        int r = statsTable.getSelectedRow();
        int m = r < 0 ? -1 : statsTable.convertRowIndexToModel(r);
        if (m < 0 || m >= targets.size()) {
            chart.setData(null, List.of());
            return;
        }
        Target t = targets.get(m);
        chart.setData(t.name + " (" + t.host + ("TCP".equals(t.type) ? ":" + t.port : "") + ")", t.historyCopy());
    }

    private JPanel buildConfigTab() {
        JPanel form = new JPanel(new GridBagLayout());
        addFormRow(form, 0, "Server ZeroTier IP:", txtServer,
                "IP ZeroTier của máy chạy Server (xem ở thanh dưới cùng cửa sổ Server)");
        addFormRow(form, 1, "Cổng báo cáo:", txtServerPort, "Cổng nhận cảnh báo của Server, mặc định 9001");
        addFormRow(form, 2, "Mã bí mật:", txtSecret,
                "Phải giống mã đặt trong Cài đặt của Server; để trống nếu Server không đặt mã");
        addFormRow(form, 3, "Chu kỳ kiểm tra (giây):", spnInterval, "Bao lâu thì kiểm tra tất cả các máy một lần");
        addFormRow(form, 4, "Timeout (giây):", spnTimeout,
                "Chờ tối đa bao lâu cho mỗi lần Ping/TCP trước khi tính là lỗi");
        addFormRow(form, 5, "Báo DOWN sau N lần lỗi:", spnThreshold,
                "Số lần lỗi liên tiếp trước khi báo mất kết nối, tránh báo nhầm khi mạng chập chờn");
        addFormNote(form, 6, "Thay đổi có hiệu lực từ lần bấm Bắt đầu tiếp theo.");
        return wrapForm(form);
    }

    private JPanel buildMailTab() {
        JPanel form = new JPanel(new GridBagLayout());
        addFormRow(form, 0, "", chkEmail, "Bật để gửi email khi có máy mất kết nối hoặc khôi phục");
        addFormRow(form, 1, "Gmail gửi:", txtMailUser, "Tài khoản Gmail dùng để gửi cảnh báo");
        addFormRow(form, 2, "App Password:", txtMailPass, "Mật khẩu ứng dụng 16 ký tự (cần bật xác minh 2 bước)");
        addFormRow(form, 3, "", chkSavePass,
                "Bỏ chọn thì mỗi lần mở Client phải nhập lại, mật khẩu không bị ghi vào " + MAIL_FILE);
        addFormRow(form, 4, "Gửi tới:", txtMailTo, "Nhiều địa chỉ thì cách nhau bằng dấu phẩy");
        addFormRow(form, 5, "Gửi mail sau N lần lỗi:", spnMailAfter,
                "Đếm số lần lỗi liên tiếp; nếu nhỏ hơn ngưỡng báo DOWN thì mail được gửi ngay khi báo DOWN");
        addFormRow(form, 6, "", btnSendNotice, "Gửi thử một email để kiểm tra cấu hình");
        return wrapForm(form);
    }

    /** Một dòng trong form: nhãn | ô nhập | gợi ý (gợi ý cũng hiện thành tooltip của ô nhập). */
    private static void addFormRow(JPanel form, int row, String label, JComponent field, String hint) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(6, 6, 6, 6);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; form.add(new JLabel(label), c);
        c.gridx = 1; form.add(field, c);
        c.gridx = 2; c.weightx = 1; form.add(UiKit.hint(hint), c);
        field.setToolTipText(hint);
    }

    private static void addFormNote(JPanel form, int row, String note) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row; c.gridx = 0; c.gridwidth = 3;
        c.insets = new Insets(14, 6, 6, 6);
        c.anchor = GridBagConstraints.WEST;
        form.add(UiKit.hint(note), c);
    }

    private static JPanel wrapForm(JPanel form) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        p.add(form, BorderLayout.NORTH);
        return p;
    }

    /** Cập nhật thanh trạng thái: liên lạc được với Server không, còn bao nhiêu báo cáo chờ gửi. Gọi trên EDT. */
    private void updateStatusBar() {
        String host = serverHost != null ? serverHost : txtServer.getText().trim();
        String st = serverState;
        String text = "Server " + host + ": " + (st == null ? "chưa liên lạc"
                : st.equals("OK") ? "đã kết nối"
                : st.equals("DENIED") ? "từ chối kết nối (sai mã bí mật)" : "không liên lạc được");
        int pending = pendingReports.size();
        if (pending > 0) text += "  ·  " + pending + " báo cáo đang chờ gửi lại";
        lblServerStatus.setText(text);
        lblServerStatus.setForeground(st == null ? Color.GRAY : st.equals("OK") ? UiKit.GOOD : UiKit.BAD);
    }

    /** Ghi nhận câu trả lời của Server (null = không liên lạc được) để hiện ở thanh trạng thái. */
    private void setServerState(String response) {
        serverState = "OK".equals(response) || "DENIED".equals(response) ? response : "FAIL";
        SwingUtilities.invokeLater(this::updateStatusBar);
    }

    // ------------------------------------------------------------ điều khiển
    private void startMonitoring() {
        int port;
        try {
            port = Integer.parseInt(txtServerPort.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Cổng báo cáo phải là số từ 1 đến 65535.");
            showConfigField(txtServerPort);
            return;
        }
        String host = txtServer.getText().trim();
        if (host.isEmpty() && JOptionPane.showConfirmDialog(this,
                "Chưa nhập IP Server ở tab Cấu hình.\n"
                        + "Vẫn bắt đầu giám sát nhưng KHÔNG báo cáo về Server?",
                "Thiếu IP Server", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            showConfigField(txtServer);
            return;
        }
        if (targets.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Danh sách giám sát đang trống. Bấm \"+ Thêm\" để thêm máy.");
            return;
        }
        serverPort = port;
        serverHost = host;
        secret = txtSecret.getText().trim();
        intervalSec = (Integer) spnInterval.getValue();
        threshold = (Integer) spnThreshold.getValue();
        timeoutSec = (Integer) spnTimeout.getValue();
        mailAfterFails = (Integer) spnMailAfter.getValue();
        if (!applyMailSettings()) return;
        saveConfig();

        synchronized (targets) { for (Target t : targets) t.resetState(); }
        refreshTable();

        pool = Executors.newCachedThreadPool();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::cycle, 0, intervalSec, TimeUnit.SECONDS);

        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        serverState = null;
        updateStatusBar();
        lblMonitorStatus.setText("Đang giám sát " + targets.size() + " máy, mỗi " + intervalSec + " giây");
        log("Bắt đầu giám sát " + targets.size() + " mục tiêu (mỗi " + intervalSec + "s)");
    }

    private void stopMonitoring() {
        boolean wasMonitoring = isMonitoring();
        if (scheduler != null) scheduler.shutdownNow();
        if (pool != null) pool.shutdownNow();
        if (wasMonitoring) {
            // đợi SNAPSHOT đang gửi dở xong trước, để BYE luôn là tin cuối cùng Server nhận
            ExecutorService old = pool;
            new Thread(() -> {
                try { old.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                sendBye();
            }, "send-bye").start();
        }
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        lblMonitorStatus.setText("Đã dừng giám sát");
        log("Đã dừng giám sát");
    }

    /** Chuyển sang tab Cấu hình và đặt con trỏ vào ô cần sửa. */
    private void showConfigField(JComponent field) {
        tabs.setSelectedIndex(tabs.indexOfTab("Cấu hình"));
        field.requestFocusInWindow();
    }

    private boolean isMonitoring() {
        return scheduler != null && !scheduler.isShutdown();
    }

    /** Một vòng kiểm tra: tất cả mục tiêu được kiểm tra song song. */
    private void cycle() {
        try {
            List<Target> snap;
            synchronized (targets) { snap = new ArrayList<>(targets); }

            List<Callable<Double>> tasks = new ArrayList<>();
            for (Target t : snap) tasks.add(() -> check(t));
            List<Future<Double>> results = pool.invokeAll(tasks);

            for (int i = 0; i < snap.size(); i++) {
                Target t = snap.get(i);
                double lat;
                try { lat = results.get(i).get(); } catch (Exception e) { lat = -1; }
                boolean ok = lat >= 0;
                long now = System.currentTimeMillis();
                t.lastCheck = LocalDateTime.now().format(FMT_TIME);
                if (t.startedAt == 0) t.startedAt = now;
                t.lastCheckMs = now;
                t.totalCount++;
                t.latency = ok ? lat : -1;
                t.addHistory(now, t.latency);

                if (ok) {
                    t.sumLatency += lat;
                    t.okCount++;
                    if (t.minLatency < 0 || lat < t.minLatency) t.minLatency = lat;
                    if (lat > t.maxLatency) t.maxLatency = lat;
                    if ("DOWN".equals(t.status)) {
                        t.downMsTotal += now - t.downSince;
                        long sec = (now - t.downSince) / 1000;
                        boolean sendMail = t.mailSent; // chỉ báo "khôi phục" nếu trước đó đã báo "mất kết nối"
                        raiseAlert("UP", t, lat, "Thời gian gián đoạn: " + formatDuration(sec), sendMail);
                    }
                    t.status = "UP"; t.fails = 0; t.downSince = 0; t.mailSent = false;
                } else {
                    t.fails++;
                    logError("LỖI  " + t.name + " (" + t.fails + "/" + threshold + ")");
                    if (t.fails >= threshold && !"DOWN".equals(t.status)) {
                        t.status = "DOWN";
                        t.downSince = now;
                        t.incidents++;
                        raiseAlert("DOWN", t, -1, "", false);
                    }
                    // đủ N lần lỗi liên tiếp và đã xác nhận DOWN -> tự động gửi mail (mỗi sự cố 1 lần)
                    if ("DOWN".equals(t.status) && t.fails >= mailAfterFails && !t.mailSent) {
                        t.mailSent = true;
                        raiseMail("DOWN", t, -1, "Số lần kiểm tra lỗi liên tiếp: " + t.fails);
                    }
                }
            }
            // gửi lại các báo cáo còn tồn đọng (do server tạm thời không liên lạc được), rồi gửi SNAPSHOT
            String snapshot = buildSnapshot(snap);
            pool.submit(() -> {
                if (!pendingReports.isEmpty()) flushReports();
                sendSnapshot(snapshot);
            });

            String time = LocalDateTime.now().format(FMT_TIME);
            int count = snap.size();
            SwingUtilities.invokeLater(() -> {
                refreshTable();
                if (isMonitoring()) {
                    lblMonitorStatus.setText("Đang giám sát " + count + " máy  ·  kiểm tra lúc " + time);
                }
            });
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable ex) {
            logError("Lỗi vòng kiểm tra: " + ex);
        }
    }

    // ------------------------------------------------------------ kiểm tra + đo tốc độ
    /** Trả về độ trễ (ms) nếu kết nối được, -1 nếu lỗi. */
    private double check(Target t) {
        return "TCP".equals(t.type) ? checkTcp(t.host, t.port) : checkPing(t.host);
    }

    /** Độ trễ = thời gian hoàn tất bắt tay TCP (không tính thời gian phân giải DNS). */
    private double checkTcp(String host, int port) {
        InetSocketAddress addr = new InetSocketAddress(host, port); // phân giải DNS ở đây
        if (addr.isUnresolved()) return -1;
        long t0 = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(addr, timeoutSec * 1000);
            return (System.nanoTime() - t0) / 1_000_000.0;
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Đọc độ trễ (ms) từ output của lệnh ping đã chạy thành công.
     * Trả về -1 nếu thực ra là lỗi, NaN nếu không tìm thấy con số nào.
     */
    private static double parsePingTime(String out, boolean windows) {
        // Windows trả exit code 0 cả khi "Destination host unreachable" -> phải có TTL= mới là thành công
        if (windows && !out.toUpperCase(Locale.ROOT).contains("TTL=")) return -1;
        Matcher m = PING_TIME.matcher(out);
        return m.find() ? Double.parseDouble(m.group(1).replace(',', '.')) : Double.NaN;
    }

    /** Độ trễ = thời gian "time=..." do lệnh ping báo cáo. */
    private double checkPing(String host) {
        Process p = null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("ping");
            if (isWindows) { cmd.add("-n"); cmd.add("1"); cmd.add("-w"); cmd.add(String.valueOf(timeoutSec * 1000)); }
            else if (isMac) { cmd.add("-c"); cmd.add("1"); cmd.add("-W"); cmd.add(String.valueOf(timeoutSec * 1000)); }
            else { cmd.add("-c"); cmd.add("1"); cmd.add("-W"); cmd.add(String.valueOf(timeoutSec)); }
            cmd.add(host);

            long t0 = System.nanoTime();
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(timeoutSec + 2, TimeUnit.SECONDS)) return -1;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            if (p.exitValue() != 0) return -1;
            double ms = parsePingTime(sb.toString(), isWindows);
            if (Double.isNaN(ms)) return (System.nanoTime() - t0) / 1_000_000.0; // không đọc được -> dùng thời gian chạy lệnh
            return ms;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }

    // ------------------------------------------------------------ cảnh báo
    private void raiseAlert(String status, Target t, double latency, String extra, boolean sendMail) {
        boolean down = "DOWN".equals(status);
        String title = (down ? "MẤT KẾT NỐI: " : "ĐÃ KHÔI PHỤC: ") + t.name;
        String addr = t.host + ("TCP".equals(t.type) ? ":" + t.port : "");
        String now = LocalDateTime.now().format(FMT);
        String body = "Thời gian : " + now + "\n"
                + "Máy chủ   : " + t.name + "\n"
                + "Địa chỉ   : " + addr + " (" + t.type + ")\n"
                + "Trạng thái: " + status
                + (latency >= 0 ? "\nĐộ trễ    : " + fmtMs(latency) + " ms" : "")
                + (extra.isEmpty() ? "" : "\n" + extra);
        log(title, down);

        // 1) hộp cảnh báo (gom nhiều cảnh báo vào một cửa sổ)
        SwingUtilities.invokeLater(() -> alertBox.show(title, body, down));

        // 2) báo cáo về server qua ZeroTier (có hàng đợi gửi lại nếu server tạm thời không liên lạc được)
        String line = clean(status) + "|" + clean(t.name) + "|" + clean(t.host) + "|" + clean(t.type) + "|"
                + clean(localName()) + "|" + (latency >= 0 ? fmtMs(latency) : "-") + "|"
                + clean(extra) + "|" + now;
        pendingReports.add(line);
        while (pendingReports.size() > MAX_PENDING_REPORTS) pendingReports.poll();
        pool.submit(this::flushReports);

        // 3) gửi email Gmail (chỉ khi được yêu cầu, ví dụ báo khôi phục)
        if (sendMail) raiseMail(status, t, latency, extra);
    }

    /** Soạn nội dung chuẩn và gửi mail cảnh báo ở luồng nền. Nếu gửi lỗi sẽ tự thử lại ở vòng sau. */
    private void raiseMail(String status, Target t, double latency, String extra) {
        if (!mailEnabled) return;
        boolean down = "DOWN".equals(status);
        String addr = t.host + ("TCP".equals(t.type) ? ":" + t.port : "");
        String subject = (down ? "[CẢNH BÁO] Mất kết nối: " : "[ĐÃ KHÔI PHỤC] Kết nối trở lại: ")
                + t.name + " (" + addr + ")";
        String body = (down
                ? "Hệ thống giám sát phát hiện máy chủ sau đây đã MẤT KẾT NỐI.\n\n"
                : "Máy chủ sau đây đã KẾT NỐI TRỞ LẠI bình thường.\n\n")
                + "Thời gian : " + LocalDateTime.now().format(FMT) + "\n"
                + "Máy chủ   : " + t.name + "\n"
                + "Địa chỉ   : " + addr + " (" + t.type + ")\n"
                + "Trạng thái: " + (down ? "MẤT KẾT NỐI (DOWN)" : "HOẠT ĐỘNG (UP)")
                + (latency >= 0 ? "\nĐộ trễ    : " + fmtMs(latency) + " ms" : "")
                + (extra.isEmpty() ? "" : "\n" + extra)
                + "\n\n-- Network Alert Client (" + localName() + ")";
        final String to = mailTo;
        pool.submit(() -> {
            try {
                sendGmail(subject, body);
                log("Đã gửi email " + (down ? "cảnh báo" : "khôi phục") + " tới " + to);
            } catch (Exception e) {
                if (down) t.mailSent = false; // gửi lỗi -> thử lại ở vòng kiểm tra kế tiếp
                logError("Gửi email lỗi: " + e.getMessage());
            }
        });
    }

    private static String localName() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "client"; }
    }

    private static String clean(String s) {
        return s.replace("|", "/").replace("\r", " ").replace("\n", " ");
    }

    private static String fmtMs(double ms) {
        return ms < 0 ? "-" : String.format(Locale.ROOT, "%.1f", ms);
    }

    private static String formatDuration(long sec) {
        if (sec < 60) return sec + " giây";
        long m = sec / 60, h = m / 60;
        if (h > 0) return h + " giờ " + (m % 60) + " phút " + (sec % 60) + " giây";
        return m + " phút " + (sec % 60) + " giây";
    }

    /** Gửi lần lượt các báo cáo trong hàng đợi; dừng ở báo cáo đầu tiên bị lỗi để gửi lại sau. */
    private synchronized void flushReports() {
        if (serverHost == null || serverHost.isEmpty()) { pendingReports.clear(); return; }
        String line;
        while ((line = pendingReports.peek()) != null) {
            String resp = sendReport(line);
            setServerState(resp);
            if (!"OK".equals(resp)) {
                logError(("DENIED".equals(resp) ? "Server " + serverHost + " từ chối báo cáo vì sai mã bí mật"
                        : "Không báo cáo được về server " + serverHost)
                        + " (còn " + pendingReports.size() + " báo cáo chờ gửi lại)");
                return;
            }
            pendingReports.poll();
            log("Đã báo cáo về server " + serverHost);
        }
    }

    /**
     * Gói trạng thái mọi máy thành tin SNAPSHOT, gửi Server sau mỗi chu kỳ. Server dùng tin này để
     * hiện trạng thái hiện tại và để biết Client còn sống. Định dạng:
     *   SNAPSHOT|tên-client|chu-kỳ-s|timeout-s|số-máy
     *   tên|host|loại|cổng|trạng thái|độ-trễ|TB|mất-gói-%|số-lần-lỗi   (mỗi máy một dòng)
     */
    private String buildSnapshot(List<Target> snap) {
        StringBuilder sb = new StringBuilder("SNAPSHOT|").append(clean(localName())).append('|')
                .append(intervalSec).append('|').append(timeoutSec).append('|').append(snap.size());
        for (Target t : snap) {
            sb.append('\n').append(clean(t.name)).append('|').append(clean(t.host)).append('|')
                    .append(t.type).append('|').append(t.port).append('|').append(t.status).append('|')
                    .append(fmtMs(t.latency)).append('|').append(fmtMs(t.avgLatency())).append('|')
                    .append(t.totalCount == 0 ? "-" : String.format(Locale.ROOT, "%.0f", t.lossPercent()))
                    .append('|').append(t.fails);
        }
        return sb.toString();
    }

    private void sendSnapshot(String snapshot) {
        if (serverHost == null || serverHost.isEmpty()) return;
        setServerState(sendReport(snapshot));
    }

    /** Báo Server là Client chủ động dừng, để Server không cảnh báo mất liên lạc. */
    private void sendBye() {
        if (serverHost == null || serverHost.isEmpty()) return;
        sendReport("BYE|" + clean(localName()));
    }

    /**
     * Gửi một tin (có thể nhiều dòng) tới cổng báo cáo. Nếu có mã bí mật thì gửi trước dòng "KEY|mã".
     * Trả về câu trả lời của Server ("OK", "DENIED"...), null nếu không liên lạc được.
     */
    private String sendReport(String line) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(serverHost, serverPort), 3000);
            s.setSoTimeout(3000);
            String key = secret.isEmpty() ? "" : "KEY|" + clean(secret) + "\n";
            s.getOutputStream().write((key + line + "\n").getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            return new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ Gmail (SMTP qua SSL, không cần thư viện ngoài)
    private void readMailFields() {
        mailEnabled = chkEmail.isSelected();
        mailUser = txtMailUser.getText().trim();
        // Google hiển thị App Password dạng "abcd efgh ijkl mnop" -> bỏ khoảng trắng
        mailPass = new String(txtMailPass.getPassword()).replace(" ", "");
        mailTo = txtMailTo.getText().trim();
    }

    /** Đọc cấu hình từ giao diện. Trả về false nếu bật email mà thiếu thông tin. */
    private boolean applyMailSettings() {
        readMailFields();
        if (mailEnabled && (mailUser.isEmpty() || mailPass.isEmpty() || mailTo.isEmpty())) {
            JOptionPane.showMessageDialog(this,
                    "Hãy nhập đủ Gmail gửi, App Password và người nhận\n(hoặc bỏ tick \"Gửi email\").");
            return false;
        }
        saveMailSettings();
        return true;
    }

    /**
     * Nút "Gửi thông báo": gửi email xác nhận kênh cảnh báo đã hoạt động.
     */
    private void sendNotice() {
        readMailFields();

        if (mailUser.isEmpty() || mailPass.isEmpty() || mailTo.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Vui lòng nhập đầy đủ thông tin:\n"
                            + "• Gmail gửi\n"
                            + "• App Password\n"
                            + "• Email người nhận",
                    "Thiếu thông tin cấu hình", JOptionPane.WARNING_MESSAGE);
            return;
        }
        saveMailSettings();

        final String to = mailTo;
        int confirm = JOptionPane.showConfirmDialog(this,
                "Bạn có muốn gửi email xác nhận đến:\n\n" + to + "\n\n"
                        + "Nội dung: Xác nhận kênh cảnh báo Network Alert.",
                "Xác nhận gửi email", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        btnSendNotice.setEnabled(false);
        btnSendNotice.setText("Đang gửi...");

        new Thread(() -> {
            try {
                int targetCount;
                synchronized (targets) { targetCount = targets.size(); }
                String time = LocalDateTime.now().format(FMT);

                String subject = "[NETWORK ALERT] Xác nhận kênh cảnh báo email";
                String body =
                        "==================================================\n"
                        + "          NETWORK ALERT CLIENT\n"
                        + "       XÁC NHẬN KÊNH CẢNH BÁO EMAIL\n"
                        + "==================================================\n\n"
                        + "Kính gửi Quý khách,\n\n"
                        + "Hệ thống giám sát mạng Network Alert Client đã "
                        + "thực hiện gửi email xác nhận cấu hình kênh cảnh báo.\n\n"
                        + "THÔNG TIN HỆ THỐNG\n"
                        + "--------------------------------------------------\n"
                        + "Thời gian xác nhận : " + time + "\n"
                        + "Máy giám sát       : " + localName() + "\n"
                        + "Gmail gửi          : " + mailUser + "\n"
                        + "Email nhận         : " + to + "\n"
                        + "Số mục tiêu        : " + targetCount + "\n"
                        + "Ngưỡng cảnh báo    : " + spnMailAfter.getValue() + " lần lỗi liên tiếp\n\n"
                        + "CHỨC NĂNG GIÁM SÁT\n"
                        + "--------------------------------------------------\n"
                        + "- Theo dõi trạng thái và tốc độ kết nối các máy chủ.\n"
                        + "- Phát hiện sự cố mất kết nối mạng.\n"
                        + "- Ghi nhận thời điểm phát sinh lỗi.\n"
                        + "- Gửi email cảnh báo khi đạt ngưỡng lỗi cấu hình.\n"
                        + "- Thông báo khi kết nối được khôi phục.\n\n"
                        + "Trân trọng,\n"
                        + "NETWORK ALERT CLIENT\n"
                        + "Hệ thống giám sát và cảnh báo mạng tự động\n\n"
                        + "==================================================\n"
                        + "Email được gửi tự động. Vui lòng không trả lời thư này.";

                sendGmail(subject, body);
                log("Đã gửi email xác nhận kênh cảnh báo tới " + to);

                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "GỬI EMAIL THÀNH CÔNG!\n\n"
                                + "Người nhận: " + to + "\n"
                                + "Thời gian: " + time + "\n"
                                + "Số mục tiêu giám sát: " + targetCount + "\n\n"
                                + "Vui lòng kiểm tra hộp thư đến.",
                        "Network Alert - Thành công", JOptionPane.INFORMATION_MESSAGE));

            } catch (Exception ex) {
                logError("Lỗi gửi email xác nhận: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "KHÔNG THỂ GỬI EMAIL!\n\n"
                                + "Người nhận: " + to + "\n"
                                + "Nguyên nhân: " + ex.getMessage() + "\n\n"
                                + "Vui lòng kiểm tra:\n"
                                + "• Kết nối Internet.\n"
                                + "• Gmail gửi và App Password.\n"
                                + "• Địa chỉ email người nhận.",
                        "Network Alert - Lỗi gửi email", JOptionPane.ERROR_MESSAGE));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    btnSendNotice.setEnabled(true);
                    btnSendNotice.setText("✉ Gửi thông báo");
                });
            }
        }, "send-notice").start();
    }

    /** Gửi email qua smtp.gmail.com:465 (SSL) bằng AUTH LOGIN. */
    private void sendGmail(String subject, String body) throws IOException {
        final String user = mailUser, pass = mailPass;
        List<String> rcpts = new ArrayList<>();
        for (String r : mailTo.split("[,;]")) {
            if (!r.trim().isEmpty()) rcpts.add(r.trim());
        }
        if (rcpts.isEmpty()) throw new IOException("Chưa có người nhận");

        final String smtpHost = "smtp.gmail.com";
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(smtpHost, 465), 10000);
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        // bọc SSL lên socket đã kết nối -> có SNI và kiểm tra đúng tên máy chủ trong chứng chỉ
        try (SSLSocket s = (SSLSocket) factory.createSocket(plain, smtpHost, 465, true)) {
            SSLParameters sp = s.getSSLParameters();
            sp.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(sp);
            s.setSoTimeout(15000);
            s.startHandshake();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

            smtpRead(in, "220");
            smtpCmd(out, in, "EHLO localhost", "250");
            smtpCmd(out, in, "AUTH LOGIN", "334");
            smtpCmd(out, in, b64(user), "334");
            smtpCmd(out, in, b64(pass), "235");
            smtpCmd(out, in, "MAIL FROM:<" + user + ">", "250");
            for (String r : rcpts) smtpCmd(out, in, "RCPT TO:<" + r + ">", "250");
            smtpCmd(out, in, "DATA", "354");

            StringBuilder msg = new StringBuilder();
            msg.append("From: ").append(user).append("\r\n");
            msg.append("To: ").append(String.join(", ", rcpts)).append("\r\n");
            msg.append("Subject: =?UTF-8?B?").append(b64(subject)).append("?=\r\n");
            msg.append("MIME-Version: 1.0\r\n");
            msg.append("Content-Type: text/plain; charset=UTF-8\r\n");
            msg.append("Content-Transfer-Encoding: base64\r\n\r\n");
            msg.append(Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));
            msg.append("\r\n.\r\n");
            out.write(msg.toString());
            out.flush();
            smtpRead(in, "250");

            try { smtpCmd(out, in, "QUIT", "221"); } catch (IOException ignored) { }
        }
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void smtpCmd(BufferedWriter out, BufferedReader in, String cmd, String code)
            throws IOException {
        out.write(cmd + "\r\n");
        out.flush();
        smtpRead(in, code);
    }

    /** Đọc hết một phản hồi SMTP (có thể nhiều dòng) và kiểm tra mã trả về. */
    private static void smtpRead(BufferedReader in, String expectCode) throws IOException {
        String line, last = "";
        while ((line = in.readLine()) != null) {
            last = line;
            if (line.length() < 4 || line.charAt(3) == ' ') break; // dòng cuối của phản hồi
        }
        if (last.isEmpty()) throw new IOException("Server SMTP đóng kết nối");
        if (!last.startsWith(expectCode)) {
            if (last.startsWith("535")) {
                throw new IOException("Sai Gmail hoặc App Password (535).\n"
                        + "Hãy bật xác minh 2 bước và tạo App Password mới.");
            }
            throw new IOException("SMTP trả về: " + last);
        }
    }

    // ------------------------------------------------------------ lưu / đọc cấu hình
    private void saveMailSettings() {
        Properties pr = new Properties();
        pr.setProperty("enabled", String.valueOf(mailEnabled));
        pr.setProperty("user", mailUser);
        pr.setProperty("savePass", String.valueOf(chkSavePass.isSelected()));
        if (chkSavePass.isSelected()) pr.setProperty("pass", mailPass);
        pr.setProperty("to", mailTo);
        try (OutputStream o = Files.newOutputStream(Paths.get(MAIL_FILE))) {
            pr.store(o, "Gmail settings - KHONG chia se file nay");
        } catch (IOException e) {
            logError("Không lưu được " + MAIL_FILE);
        }
    }

    private void loadMailSettings() {
        Path path = Paths.get(MAIL_FILE);
        if (!Files.exists(path)) return;
        Properties pr = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            pr.load(in);
            chkEmail.setSelected(Boolean.parseBoolean(pr.getProperty("enabled", "false")));
            txtMailUser.setText(pr.getProperty("user", ""));
            txtMailPass.setText(pr.getProperty("pass", ""));
            txtMailTo.setText(pr.getProperty("to", ""));
            // file cũ chưa có "savePass": coi như đang lưu nếu trong file có mật khẩu
            chkSavePass.setSelected(Boolean.parseBoolean(
                    pr.getProperty("savePass", String.valueOf(pr.containsKey("pass")))));
        } catch (IOException e) { /* bỏ qua */ }
    }

    /** Lưu cấu hình giám sát để lần sau mở lại không phải nhập lại. */
    private void saveConfig() {
        Properties pr = new Properties();
        pr.setProperty("server", txtServer.getText().trim());
        pr.setProperty("port", txtServerPort.getText().trim());
        pr.setProperty("secret", txtSecret.getText().trim());
        pr.setProperty("interval", String.valueOf(spnInterval.getValue()));
        pr.setProperty("timeout", String.valueOf(spnTimeout.getValue()));
        pr.setProperty("threshold", String.valueOf(spnThreshold.getValue()));
        pr.setProperty("mailAfter", String.valueOf(spnMailAfter.getValue()));
        try (OutputStream o = Files.newOutputStream(Paths.get(CONFIG_FILE))) {
            pr.store(o, "Network Alert Client config");
        } catch (IOException e) {
            logError("Không lưu được " + CONFIG_FILE);
        }
    }

    private void loadConfig() {
        Path path = Paths.get(CONFIG_FILE);
        if (!Files.exists(path)) return;
        Properties pr = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            pr.load(in);
            txtServer.setText(pr.getProperty("server", txtServer.getText()));
            txtServerPort.setText(pr.getProperty("port", txtServerPort.getText()));
            txtSecret.setText(pr.getProperty("secret", ""));
            spnInterval.setValue(Integer.parseInt(pr.getProperty("interval", "5")));
            spnTimeout.setValue(Integer.parseInt(pr.getProperty("timeout", "3")));
            spnThreshold.setValue(Integer.parseInt(pr.getProperty("threshold", "3")));
            spnMailAfter.setValue(Integer.parseInt(pr.getProperty("mailAfter", "5")));
        } catch (Exception e) { /* bỏ qua, dùng mặc định */ }
    }

    // ------------------------------------------------------------ quản lý danh sách
    private void addTargetDialog() {
        Target t = showTargetDialog("Thêm máy chủ", null);
        if (t == null) return;
        targets.add(t);
        saveTargets();
        refreshTable();
    }

    private void editSelected() {
        int idx = selectedTargetIndex();
        if (idx < 0) {
            JOptionPane.showMessageDialog(this, "Hãy chọn một dòng trong bảng trước.");
            return;
        }
        Target t = showTargetDialog("Sửa máy chủ", targets.get(idx));
        if (t == null) return;
        targets.set(idx, t);
        saveTargets();
        refreshTable();
    }

    private void removeSelected() {
        int idx = selectedTargetIndex();
        if (idx < 0) return;
        Target t = targets.get(idx);
        if (JOptionPane.showConfirmDialog(this, "Xóa \"" + t.name + "\" (" + t.host + ") khỏi danh sách giám sát?",
                "Xác nhận xóa", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        targets.remove(idx);
        saveTargets();
        refreshTable();
    }

    /** Vị trí trong danh sách targets của dòng đang chọn (bảng có thể đang sắp xếp khác thứ tự). */
    private int selectedTargetIndex() {
        int r = table.getSelectedRow();
        if (r < 0) return -1;
        int m = table.convertRowIndexToModel(r);
        return m < targets.size() ? m : -1;
    }

    /** Hộp thoại nhập máy chủ; old = null là thêm mới. Trả về null nếu bấm Hủy hoặc dữ liệu không hợp lệ. */
    private Target showTargetDialog(String title, Target old) {
        JTextField n = new JTextField(old == null ? "" : old.name, 18);
        JTextField h = new JTextField(old == null ? "" : old.host, 18);
        JTextField p = new JTextField(old == null || old.port == 0 ? "9000" : String.valueOf(old.port), 6);
        JComboBox<String> ty = new JComboBox<>(new String[]{"TCP", "PING"});
        if (old != null) ty.setSelectedItem(old.type);
        p.setEnabled("TCP".equals(ty.getSelectedItem()));
        ty.addActionListener(e -> p.setEnabled("TCP".equals(ty.getSelectedItem())));
        JPanel pnl = new JPanel(new GridLayout(0, 2, 5, 5));
        pnl.add(new JLabel("Tên:")); pnl.add(n);
        pnl.add(new JLabel("Địa chỉ IP/host:")); pnl.add(h);
        pnl.add(new JLabel("Loại:")); pnl.add(ty);
        pnl.add(new JLabel("Cổng (nếu TCP):")); pnl.add(p);

        if (JOptionPane.showConfirmDialog(this, pnl, title,
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return null;
        if (h.getText().trim().isEmpty()) return null;
        try {
            int port = "TCP".equals(ty.getSelectedItem()) ? Integer.parseInt(p.getText().trim()) : 0;
            // dấu ";" là ký tự phân cách trong targets.txt nên không cho phép trong tên/host
            String host = h.getText().trim().replace(";", "");
            String name = n.getText().trim().isEmpty() ? host : n.getText().trim().replace(";", ",");
            return new Target(name, host, (String) ty.getSelectedItem(), port);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Cổng không hợp lệ");
            return null;
        }
    }

    /** Vẽ lại bảng và các thẻ số liệu nhưng giữ nguyên dòng đang chọn (chu kỳ nào cũng gọi hàm này). */
    private void refreshTable() {
        int sel = table.getSelectedRow();
        int selModel = sel >= 0 ? table.convertRowIndexToModel(sel) : -1;
        int statsSel = statsTable.getSelectedRow();
        int statsSelModel = statsSel >= 0 ? statsTable.convertRowIndexToModel(statsSel) : -1;
        int up = 0, down = 0, measured = 0;
        double sumLatency = 0;
        model.setRowCount(0);
        statsModel.setRowCount(0);
        synchronized (targets) {
            for (Target t : targets) {
                String st = "UP".equals(t.status) ? "UP" : "DOWN".equals(t.status) ? "DOWN" : "CHƯA RÕ";
                String loss = t.totalCount == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", t.lossPercent());
                model.addRow(new Object[]{t.name, t.host, t.type,
                        "TCP".equals(t.type) ? t.port : "-", st,
                        fmtMs(t.latency), fmtMs(t.avgLatency()), loss, t.fails, t.lastCheck});
                if ("UP".equals(st)) up++;
                if ("DOWN".equals(st)) down++;
                if (t.latency >= 0) { sumLatency += t.latency; measured++; }

                double uptime = t.uptimePercent();
                boolean started = t.startedAt > 0;
                statsModel.addRow(new Object[]{t.name, t.host,
                        uptime < 0 ? "-" : String.format(Locale.ROOT, "%.2f%%", uptime),
                        t.incidents,
                        started ? formatDuration(t.downMs() / 1000) : "-",
                        fmtMs(t.avgLatency()), fmtMs(t.minLatency), fmtMs(t.maxLatency), loss, t.totalCount,
                        started ? formatDuration((t.lastCheckMs - t.startedAt) / 1000) : "-"});
            }
        }
        if (selModel >= 0 && selModel < model.getRowCount()) {
            int v = table.convertRowIndexToView(selModel);
            table.setRowSelectionInterval(v, v);
        }
        if (statsSelModel >= 0 && statsSelModel < statsModel.getRowCount()) {
            int v = statsTable.convertRowIndexToView(statsSelModel);
            statsTable.setRowSelectionInterval(v, v);
        }
        updateChart();
        cardTotal.setValue(model.getRowCount());
        cardUp.setValue(up);
        cardDown.setValue(down);
        cardDown.setAlert(down > 0);
        cardLatency.setValue(measured == 0 ? "-" : fmtMs(sumLatency / measured));
    }

    private void saveTargets() {
        List<String> lines = new ArrayList<>();
        synchronized (targets) {
            for (Target t : targets) {
                lines.add(t.name + ";" + t.host + ";" + t.type + ";" + t.port);
            }
        }
        try { Files.write(Paths.get(TARGET_FILE), lines, StandardCharsets.UTF_8); }
        catch (IOException e) { logError("Không lưu được " + TARGET_FILE); }
    }

    /**
     * Đọc targets.txt, mỗi dòng: tên;host;TCP|PING;cổng. Dòng sai định dạng được bỏ qua từng dòng
     * (không làm hỏng cả danh sách); khi có dòng sai thì sao lưu file gốc ra targets.txt.bak
     * vì lần lưu kế tiếp sẽ không còn các dòng đó.
     */
    private void loadTargets() {
        Path p = Paths.get(TARGET_FILE);
        if (!Files.exists(p)) {
            log("Chưa có " + TARGET_FILE + ". Bấm \"+ Thêm\" để thêm máy cần giám sát.");
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logError("Không đọc được " + TARGET_FILE + ": " + e.getMessage());
            return;
        }
        int bad = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            String[] a = line.split(";");
            try {
                String type = a.length == 4 ? a[2].trim().toUpperCase(Locale.ROOT) : "";
                if (!type.equals("TCP") && !type.equals("PING")) throw new IllegalArgumentException();
                int port = Integer.parseInt(a[3].trim());
                if (a[1].trim().isEmpty() || (type.equals("TCP") && (port < 1 || port > 65535))) {
                    throw new IllegalArgumentException();
                }
                targets.add(new Target(a[0].trim(), a[1].trim(), type, port));
            } catch (IllegalArgumentException e) { // gồm cả NumberFormatException
                bad++;
                logError("Bỏ qua dòng sai định dạng trong " + TARGET_FILE + ": " + line);
            }
        }
        if (bad > 0) {
            try {
                Files.copy(p, Paths.get(TARGET_FILE + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                logError("Đã sao lưu file gốc ra " + TARGET_FILE + ".bak");
            } catch (IOException e) {
                logError("Không sao lưu được " + TARGET_FILE + ": " + e.getMessage());
            }
        }
    }

    private void log(String msg) { log(msg, false); }

    private void logError(String msg) { log(msg, true); }

    private void log(String msg, boolean error) {
        String line = "[" + LocalDateTime.now().format(FMT) + "] " + msg;
        SwingUtilities.invokeLater(() -> {
            logLines.add(new LogLine(line, error));
            if (logLines.size() > MAX_LOG_LINES) logLines.remove(0);
            if (error || !btnLogErrors.isSelected()) {
                logArea.append(line + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
    }

    /** Vẽ lại ô nhật ký theo bộ lọc "Chỉ lỗi". Gọi trên EDT. */
    private void renderLog() {
        boolean onlyErrors = btnLogErrors.isSelected();
        StringBuilder sb = new StringBuilder();
        for (LogLine l : logLines) {
            if (!onlyErrors || l.error()) sb.append(l.text()).append('\n');
        }
        logArea.setText(sb.toString());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    // ------------------------------------------------------------ renderer
    static class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                       boolean focus, int r, int c) {
            Component comp = super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            if (!sel) {
                if ("UP".equals(v)) comp.setForeground(new Color(0, 130, 0));
                else if ("DOWN".equals(v)) comp.setForeground(new Color(200, 0, 0));
                else comp.setForeground(Color.GRAY);
            }
            setFont(getFont().deriveFont(Font.BOLD));
            return comp;
        }
    }

    /** Tô màu độ trễ: xanh < 100ms, cam < 300ms, đỏ còn lại, xám nếu chưa có số liệu. */
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
        SwingUtilities.invokeLater(() -> new ClientMain().setVisible(true));
    }
}
