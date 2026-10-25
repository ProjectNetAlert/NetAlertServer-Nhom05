package netalertserver;

import java.awt.*;
import javax.swing.*;

/**
 * Điểm chạy chính của file JAR: màn hình chọn chạy Server hoặc Client.
 * Có thể bỏ qua màn hình này bằng tham số: java -jar NetAlertServer.jar server | client
 */
public class NetAlertServer {

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0].trim().toLowerCase() : "";
        if (mode.equals("server")) {
            ServerMain.main(args);
            return;
        }
        if (mode.equals("client")) {
            ClientMain.main(args);
            return;
        }
        UiKit.useSystemLookAndFeel();
        SwingUtilities.invokeLater(NetAlertServer::showLauncher);
    }

    private static void showLauncher() {
        JFrame f = new JFrame("Network Alert System");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JLabel title = new JLabel("Network Alert System", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        JLabel sub = UiKit.hint("Chọn chương trình muốn chạy trên máy này");
        sub.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel head = new JPanel(new GridLayout(0, 1, 0, 4));
        head.add(title);
        head.add(sub);

        JButton btnServer = new JButton("<html><center><b>Server</b><br>Nhận cảnh báo từ các Client</center></html>");
        JButton btnClient = new JButton("<html><center><b>Client</b><br>Giám sát các máy và gửi cảnh báo</center></html>");
        // mở cửa sổ mới trước rồi mới đóng màn hình chọn, để chương trình không tự thoát
        btnServer.addActionListener(e -> { new ServerMain().setVisible(true); f.dispose(); });
        btnClient.addActionListener(e -> { new ClientMain().setVisible(true); f.dispose(); });
        JPanel buttons = new JPanel(new GridLayout(1, 2, 12, 0));
        buttons.add(btnServer);
        buttons.add(btnClient);
        buttons.setPreferredSize(new Dimension(440, 90));

        JPanel p = new JPanel(new BorderLayout(0, 16));
        p.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        p.add(head, BorderLayout.NORTH);
        p.add(buttons, BorderLayout.CENTER);
        f.add(p);
        f.pack();
        f.setResizable(false);
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }
}
