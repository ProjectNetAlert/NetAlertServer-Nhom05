
package netalertserver;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

/**
 * Các thành phần giao diện dùng chung cho Client và Server.
 */
final class UiKit {

    // Màu sắc dùng chung cho giao diện
    static final Color GOOD = new Color(0, 130, 0);
    static final Color BAD = new Color(200, 0, 0);
    static final Color DOWN_ROW = new Color(253, 228, 228);
    static final Color LINE = new Color(210, 210, 210);

    private UiKit() { }

    /** Sử dụng giao diện của hệ điều hành. */
    static void useSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { /* giữ giao diện mặc định */ }
    }

    /** Đặt kích thước cửa sổ phù hợp với màn hình. */
    static void sizeToScreen(Window w, int width, int height) {
        Rectangle b = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        w.setSize(Math.min(width, b.width - 40), Math.min(height, b.height - 40));
        w.setMinimumSize(new Dimension(Math.min(820, b.width), Math.min(540, b.height)));
        w.setLocationRelativeTo(null);
    }

    /** So sánh các cột số, ô không có giá trị được xếp cuối. */
    static final Comparator<Object> NUMERIC = Comparator.comparingDouble(UiKit::toNumber);

    private static double toNumber(Object v) {
        try {
            return Double.parseDouble(String.valueOf(v).replace("%", "").trim());
        } catch (NumberFormatException e) {
            return Double.MAX_VALUE;
        }
    }

    /** Tạo nhãn gợi ý có màu xám. */
    static JLabel hint(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Color.GRAY);
        return l;
    }

    /** Tạo thanh trạng thái ở cuối cửa sổ. */
    static JPanel statusBar(JComponent left, JComponent right) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, LINE),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        bar.add(left, BorderLayout.WEST);
        if (right != null) bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ------------------------------------------------------------ CSV

    /** Xuất dữ liệu bảng ra file CSV theo thứ tự đang hiển thị. */
    static void exportCsv(Component parent, JTable table, String baseName) {
        JFileChooser fc = new JFileChooser();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        fc.setSelectedFile(new File(baseName + "-" + stamp + ".csv"));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        Path path = fc.getSelectedFile().toPath();
        try {
            writeCsv(table, path);
            JOptionPane.showMessageDialog(parent, "Đã xuất " + table.getRowCount() + " dòng ra\n" + path);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent, "Không ghi được file: " + e.getMessage(),
                    "Lỗi xuất CSV", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Ghi file CSV bằng UTF-8 để hiển thị đúng tiếng Việt. */
    static void writeCsv(JTable table, Path path) throws IOException {
        StringBuilder sb = new StringBuilder("\uFEFF");
        int cols = table.getColumnCount();
        for (int c = 0; c < cols; c++)
            sb.append(c == 0 ? "" : ",").append(csvCell(table.getColumnName(c)));
        sb.append("\r\n");

        for (int r = 0; r < table.getRowCount(); r++) {
            for (int c = 0; c < cols; c++)
                sb.append(c == 0 ? "" : ",").append(csvCell(table.getValueAt(r, c)));
            sb.append("\r\n");
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Xử lý dữ liệu có dấu phẩy hoặc dấu ngoặc kép trong CSV. */
    private static String csvCell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        return quote ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    // ------------------------------------------------------------ Biểu đồ

    /** Biểu đồ độ trễ theo thời gian, vẽ bằng Java2D. */
    static class LatencyChart extends JPanel {

        /** Một lần kiểm tra; ms < 0 nghĩa là kiểm tra lỗi. */
        record Sample(long time, double ms) { }

        private static final Color LINE_COLOR = new Color(30, 100, 200);
        private static final Color FAIL_COLOR = new Color(200, 0, 0, 70);
        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

        private String title;
        private List<Sample> samples = List.of();

        LatencyChart() {
            setPreferredSize(new Dimension(400, 240));
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(LINE));
        }

        /** Cập nhật dữ liệu và vẽ lại biểu đồ. */
        void setData(String title, List<Sample> samples) {
            this.title = title;
            this.samples = samples;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int left = 64, right = 20, top = 34, bottom = 30;
            int w = getWidth() - left - right, h = getHeight() - top - bottom;
            Font base = getFont();

            g.setFont(base.deriveFont(Font.BOLD));
            g.setColor(Color.DARK_GRAY);
            g.drawString(title == null ? "Chọn một máy trong bảng để xem biểu đồ độ trễ"
                    : "Độ trễ theo thời gian: " + title, left, 22);

            // Chưa chọn máy hoặc cửa sổ quá nhỏ
            if (title == null || w <= 20 || h <= 20) {
                g.dispose();
                return;
            }

            g.setFont(base.deriveFont(11f));
            FontMetrics fm = g.getFontMetrics();

            // Cần ít nhất hai lần kiểm tra để vẽ biểu đồ
            if (samples.size() < 2) {
                g.setColor(Color.GRAY);
                g.drawString("Chưa đủ dữ liệu, cần ít nhất 2 lần kiểm tra.", left, top + h / 2);
                g.dispose();
                return;
            }

            double max = 10;
            for (Sample s : samples) max = Math.max(max, s.ms());
            max = niceCeil(max * 1.1);

            long t0 = samples.get(0).time(), t1 = samples.get(samples.size() - 1).time();
            double span = Math.max(1, t1 - t0);

            // Vẽ lưới ngang và nhãn trục độ trễ
            for (int i = 0; i <= 4; i++) {
                int y = top + h - h * i / 4;
                g.setColor(LINE);
                g.drawLine(left, y, left + w, y);

                double v = max * i / 4;
                String lbl = (v == Math.rint(v) ? String.format(Locale.ROOT, "%.0f", v)
                        : String.format(Locale.ROOT, "%.1f", v)) + " ms";

                g.setColor(Color.GRAY);
                g.drawString(lbl, left - 8 - fm.stringWidth(lbl), y + 4);
            }

            // Đánh dấu màu đỏ tại những lần kiểm tra lỗi
            g.setColor(FAIL_COLOR);
            for (Sample s : samples) {
                if (s.ms() < 0)
                    g.fillRect((int) Math.round(left + (s.time() - t0) / span * w) - 1, top, 3, h);
            }

            // Vẽ đường biểu diễn độ trễ
            g.setColor(LINE_COLOR);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            Path2D path = null;
            boolean dots = samples.size() <= 60;

            for (Sample s : samples) {
                // Ngắt đường khi gặp lần kiểm tra lỗi
                if (s.ms() < 0) {
                    if (path != null) g.draw(path);
                    path = null;
                    continue;
                }

                double x = left + (s.time() - t0) / span * w;
                double y = top + h - s.ms() / max * h;

                if (path == null) {
                    path = new Path2D.Double();
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }

                // Vẽ thêm chấm khi số lượng điểm không quá lớn
                if (dots) g.fill(new Ellipse2D.Double(x - 2.5, y - 2.5, 5, 5));
            }

            if (path != null) g.draw(path);

            // Hiển thị thời gian đầu và cuối biểu đồ
            String a = time(t0), b = time(t1);
            g.setColor(Color.GRAY);
            g.drawString(a, left, top + h + 18);
            g.drawString(b, left + w - fm.stringWidth(b), top + h + 18);

            String legend = "Vạch đỏ = lần kiểm tra lỗi";
            g.drawString(legend, left + (w - fm.stringWidth(legend)) / 2, top + h + 18);

            g.dispose();
        }

        /** Làm tròn giá trị trục để nhãn hiển thị dễ đọc. */
        private static double niceCeil(double v) {
            double p = Math.pow(10, Math.floor(Math.log10(v)));
            for (double m : new double[]{1, 2, 5, 10})
                if (m * p >= v) return m * p;
            return 10 * p;
        }

        /** Chuyển thời gian sang định dạng giờ:phút:giây. */
        private static String time(long ms) {
            return TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
        }
    }

    // ------------------------------------------------------------ Bảng

    /** Bảng tô đỏ nhạt các máy có trạng thái DOWN hoặc OFFLINE. */
    static class StatusTable extends JTable {
        private final int statusCol;

        StatusTable(DefaultTableModel model, int statusCol) {
            super(model);
            this.statusCol = statusCol;
            setRowHeight(26);
            setFillsViewportHeight(true);
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        }

        @Override
        public Component prepareRenderer(TableCellRenderer r, int row, int col) {
            Component c = super.prepareRenderer(r, row, col);

            // Đổi màu nền theo trạng thái máy
            if (!isRowSelected(row)) {
                Object st = getModel().getValueAt(convertRowIndexToModel(row), statusCol);
                c.setBackground("DOWN".equals(st) || "OFFLINE".equals(st)
                        ? DOWN_ROW : getBackground());
            }

            return c;
        }

        /** Thiết lập độ rộng cho từng cột. */
        void setColumnWidths(int... widths) {
            for (int i = 0; i < widths.length; i++)
                getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
    }

    // ------------------------------------------------------------ Thẻ số liệu

    /** Hiển thị số liệu tổng quan như tổng số máy, UP và DOWN. */
    static class StatCard extends JPanel {
        private final JLabel value = new JLabel("-");

        StatCard(String title) {
            super(new BorderLayout(0, 2));
            value.setFont(value.getFont().deriveFont(Font.BOLD, 22f));
            add(hint(title), BorderLayout.NORTH);
            add(value, BorderLayout.CENTER);
            setAlert(false);
        }

        /** Cập nhật giá trị hiển thị trên thẻ. */
        void setValue(Object v) {
            value.setText(String.valueOf(v));
        }

        /** Tô đỏ thẻ khi có sự cố. */
        void setAlert(boolean alert) {
            setBackground(alert ? DOWN_ROW : UIManager.getColor("Panel.background"));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(alert ? BAD : LINE, alert ? 2 : 1, true),
                    BorderFactory.createEmptyBorder(8, 12, 8, 12)));
            value.setForeground(alert ? BAD : UIManager.getColor("Label.foreground"));
        }
    }

    // ------------------------------------------------------------ Hộp cảnh báo

    /**
     * Một cửa sổ cảnh báo duy nhất, luôn nằm trên cùng.
     * Cảnh báo mới được thêm lên đầu danh sách.
     */
    static class AlertBox {
        private static final int MAX_ITEMS = 100;
        private final JDialog dialog;
        private final DefaultListModel<String> items = new DefaultListModel<>();
        private final JCheckBox chkMute = new JCheckBox("Tắt âm thanh");

        AlertBox(Frame owner) {
            dialog = new JDialog(owner, "Cảnh báo", false);
            dialog.setAlwaysOnTop(true);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

            // Xử lý khi người dùng đóng cửa sổ cảnh báo
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    dismiss();
                }
            });

            JList<String> list = new JList<>(items);
            list.setCellRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> l, Object v, int i,
                                                              boolean sel, boolean focus) {
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, focus);
                    c.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, 0, 1, 0, LINE),
                            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
                    return c;
                }
            });

            // Nút xác nhận đã xem cảnh báo
            JButton btnOk = new JButton("Đã xem");
            btnOk.addActionListener(e -> dismiss());

            JPanel south = new JPanel(new BorderLayout());
            south.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            south.add(chkMute, BorderLayout.WEST);
            south.add(btnOk, BorderLayout.EAST);

            dialog.add(new JScrollPane(list), BorderLayout.CENTER);
            dialog.add(south, BorderLayout.SOUTH);
            dialog.setSize(440, 340);
            dialog.setLocationRelativeTo(owner);
        }

        /** Thêm cảnh báo mới và hiển thị cửa sổ. */
        void show(String title, String body, boolean down) {
            // Phát âm thanh nếu chưa tắt
            if (!chkMute.isSelected()) Toolkit.getDefaultToolkit().beep();

            String color = down ? "#c80000" : "#008200";
            items.add(0, "<html><font color='" + color + "'><b>" + esc(title) + "</b></font><br>"
                    + esc(body).replace("\n", "<br>") + "</html>");

            // Giới hạn tối đa 100 cảnh báo
            while (items.size() > MAX_ITEMS) items.remove(items.size() - 1);

            dialog.setTitle("Cảnh báo (" + items.size() + ")");
            if (!dialog.isVisible()) dialog.setVisible(true);
            dialog.toFront();
        }

        /** Xóa danh sách và ẩn cửa sổ cảnh báo. */
        private void dismiss() {
            items.clear();
            dialog.setVisible(false);
        }

        /** Chuyển ký tự đặc biệt để hiển thị đúng trong HTML. */
        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
