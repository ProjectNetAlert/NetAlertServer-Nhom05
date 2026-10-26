# Network Alert System

Ứng dụng giám sát và cảnh báo kết nối mạng, viết bằng Java Swing, không dùng thư viện ngoài.

- **Client** kiểm tra Ping/TCP tới danh sách máy theo chu kỳ, đo độ trễ và tỉ lệ mất gói. Khi một máy mất kết nối, Client báo DOWN, bật cảnh báo, gửi email và báo cáo về Server.
- **Server** nhận báo cáo từ nhiều Client qua mạng ZeroTier. Server hiện trạng thái hiện tại của mọi máy và tự phát hiện khi chính một Client mất liên lạc.

Tài liệu này dành cho các thành viên trong nhóm: cách cài, cách chạy, cách dùng từng màn hình và kịch bản demo.

## Mục lục

1. [Cần chuẩn bị](#1-cần-chuẩn-bị)
2. [Build và chạy](#2-build-và-chạy)
3. [Nối mạng ZeroTier](#3-nối-mạng-zerotier)
4. [Dùng Server](#4-dùng-server)
5. [Dùng Client](#5-dùng-client)
6. [Gửi cảnh báo qua Gmail](#6-gửi-cảnh-báo-qua-gmail)
7. [Các file cấu hình và dữ liệu](#7-các-file-cấu-hình-và-dữ-liệu)
8. [Kiến trúc và giao thức](#8-kiến-trúc-và-giao-thức)
9. [Kịch bản demo](#9-kịch-bản-demo)
10. [Lỗi thường gặp](#10-lỗi-thường-gặp)
11. [Cấu trúc mã nguồn](#11-cấu-trúc-mã-nguồn)

## 1. Cần chuẩn bị

| Thứ cần có | Ghi chú |
| --- | --- |
| JDK 22 trở lên | Project đang build cho Java 22. Máy chỉ có Java 17–21 thì xem mục [Lỗi thường gặp](#10-lỗi-thường-gặp). |
| Apache NetBeans | Để mở và build project. |
| ZeroTier One | Để các máy trong nhóm nhìn thấy nhau như cùng mạng LAN. |
| Tài khoản Gmail có bật xác minh 2 bước | Chỉ cần nếu muốn gửi cảnh báo qua email. |

Kiểm tra phiên bản Java: mở Command Prompt, gõ `java -version`.

## 2. Build và chạy

**Build:** mở project trong NetBeans → chuột phải vào project → **Clean and Build**. File chạy được tạo ở `dist/NetAlertServer.jar`.

**Chạy:**

```
java -jar dist/NetAlertServer.jar          # hiện màn hình chọn Server hoặc Client
java -jar dist/NetAlertServer.jar server   # mở thẳng Server
java -jar dist/NetAlertServer.jar client   # mở thẳng Client
```

Trong NetBeans, bấm **Run (F6)** cũng ra màn hình chọn.

> **Thư mục chạy rất quan trọng.** Mọi file cấu hình và nhật ký (`client.properties`, `targets.txt`, `alerts.log`...) được đọc và ghi ở *thư mục đang đứng khi chạy lệnh*, không phải thư mục chứa file JAR. Mỗi lần chạy nên đứng ở cùng một thư mục, ví dụ thư mục gốc của project, để không bị "mất" cấu hình.

Có thể chạy Server và Client trên cùng một máy để thử: ở Client đặt IP Server là `127.0.0.1`.

## 3. Nối mạng ZeroTier

ZeroTier tạo một mạng ảo, giúp các máy ở nhà khác nhau gọi được nhau bằng IP. Đường truyền giữa các máy được ZeroTier mã hóa.

1. Tải và cài ZeroTier One từ https://www.zerotier.com/download/.
2. Mở ZeroTier → **Join New Network** → nhập Network ID của nhóm (hỏi người quản lý mạng).
3. Người quản lý vào https://my.zerotier.com, mở network của nhóm, tick **Auth** cho máy vừa join.
4. Sau khoảng nửa phút, máy được cấp một IP ZeroTier (dạng `10.x.x.x`). Xem IP này trong ZeroTier, hoặc ở thanh dưới cùng cửa sổ Server.

**Tường lửa trên máy chạy Server:** lần đầu mở Server, Windows hỏi có cho Java dùng mạng không. Chọn **Allow** cho cả *Private* lẫn *Public*, vì Windows thường xếp mạng ZeroTier vào loại Public. Nếu lỡ bấm Cancel, vào *Windows Defender Firewall → Allow an app* và bật Java lên.

## 4. Dùng Server

Server nên chạy trên một máy cố định trong suốt buổi demo.

**Hàng thẻ số liệu** ở đầu cửa sổ:

| Thẻ | Ý nghĩa |
| --- | --- |
| Đang mất kết nối | Số máy đang DOWN, lấy từ dữ liệu mới nhất của các Client đang online. Có máy DOWN thì thẻ chuyển đỏ. |
| Cảnh báo DOWN / Lần khôi phục UP | Tổng số cảnh báo đã nhận, kể cả những cảnh báo đọc lại từ `alerts.log`. |
| Client đang online | Dạng `online/tổng`. Thẻ chuyển đỏ khi có Client mất liên lạc. |

**Ba tab:**

- **Trạng thái hiện tại:** mọi máy mà các Client đang giám sát, kèm trạng thái và độ trễ. Bảng tự cập nhật sau mỗi chu kỳ của Client. Nếu Client mất liên lạc, các máy của nó hiện **CHƯA RÕ**.
- **Client:** danh sách Client kèm trạng thái:
  - **ONLINE:** đang gửi tín hiệu bình thường.
  - **OFFLINE:** quá hạn không nhận được tín hiệu (xem [cách tính hạn](#cách-phát-hiện-sự-cố)). Server bật cảnh báo.
  - **ĐÃ DỪNG:** người dùng bấm Dừng hoặc đóng Client. Server không báo động.
- **Nhật ký cảnh báo:** mọi sự kiện DOWN, UP, OFFLINE, ONLINE, DENIED. Có thể lọc theo trạng thái, tìm theo chữ, **Xuất CSV** (xuất đúng các dòng đang hiện) và **Xóa bảng**. Xóa bảng không xóa file `alerts.log`.

**Thanh dưới cùng:**

- Hiện IP ZeroTier của máy.
- Nút **Sao chép IP**: chép IP này để dán vào Client.
- Nút **Cài đặt**:
  - Đổi cổng heartbeat (mặc định 9000) và cổng báo cáo (mặc định 9001). Cổng mới chỉ có hiệu lực sau khi mở lại Server.
  - Đặt **mã bí mật**. Mã có hiệu lực ngay; khi đã đặt, Client nào không nhập đúng mã sẽ bị từ chối.

**Hộp cảnh báo:** nhiều cảnh báo đến cùng lúc được gom vào một cửa sổ. Bấm **Đã xem** để đóng. Tick **Tắt âm thanh** nếu không muốn máy kêu.

## 5. Dùng Client

### Tab Cấu hình (làm trước tiên)

| Ô | Ý nghĩa | Mặc định |
| --- | --- | --- |
| Server ZeroTier IP | IP của máy chạy Server. Để trống thì Client chỉ giám sát, không báo cáo về Server. | trống |
| Cổng báo cáo | Phải trùng cổng báo cáo trong Cài đặt của Server. | 9001 |
| Mã bí mật | Phải trùng mã của Server. Server không đặt mã thì để trống. | trống |
| Chu kỳ kiểm tra | Bao nhiêu giây thì kiểm tra tất cả các máy một lần. | 5 giây |
| Timeout | Chờ tối đa bao lâu cho mỗi lần Ping/TCP. | 3 giây |
| Báo DOWN sau N lần lỗi | Số lần lỗi liên tiếp trước khi báo mất kết nối, để tránh báo nhầm khi mạng chập chờn. | 3 |

Thay đổi có hiệu lực từ lần bấm **Bắt đầu** tiếp theo.

### Tab Giám sát

- **+ Thêm / Sửa / Xóa** để quản lý danh sách máy. Nháy đúp một dòng để sửa, chuột phải để mở menu. Mỗi máy có 2 cách kiểm tra:
  - **PING:** gửi lệnh ping. Nhiều máy Windows chặn ping theo mặc định, xem [Lỗi thường gặp](#10-lỗi-thường-gặp).
  - **TCP:** thử mở kết nối tới một cổng, ví dụ `443` của web hay `9000` của Server. Cách này đáng tin hơn ping.
- **▶ Bắt đầu / ■ Dừng** để chạy hoặc dừng giám sát. Khi Dừng, Client báo cho Server biết, nên Server hiện ĐÃ DỪNG chứ không báo mất liên lạc.
- Dòng DOWN được tô đỏ. Bấm tiêu đề cột để sắp xếp.
- **Nhật ký** ở phía dưới: bấm **Chỉ lỗi** để lọc, **Xóa nhật ký** để làm trống.
- **Thanh dưới cùng** cho biết Client liên lạc được với Server không:

| Thông báo | Ý nghĩa |
| --- | --- |
| đã kết nối | Bình thường. |
| không liên lạc được | Sai IP, sai cổng, Server chưa mở, hoặc tường lửa chặn. |
| từ chối kết nối (sai mã bí mật) | Mã bí mật ở Client khác mã ở Server. |
| ... báo cáo đang chờ gửi lại | Báo cáo DOWN/UP sẽ tự gửi lại khi Server liên lạc được. |

### Tab Thống kê

- Số liệu của từng máy tính từ lúc bấm Bắt đầu:
  - **Uptime:** % thời gian hoạt động.
  - **Số sự cố:** số lần chuyển sang DOWN.
  - **Thời gian gián đoạn:** tính từ lúc báo DOWN tới lúc UP lại.
  - Độ trễ trung bình, nhỏ nhất, lớn nhất; tỉ lệ mất gói.
- **Chọn một dòng** để xem biểu đồ độ trễ theo thời gian của máy đó. Lần kiểm tra lỗi hiện thành vạch đỏ. Biểu đồ giữ 600 lần kiểm tra gần nhất.
- **Xuất CSV** để lưu bảng ra file mở bằng Excel, ví dụ để đưa vào báo cáo.

### Tab Email

Xem [mục 6](#6-gửi-cảnh-báo-qua-gmail).

## 6. Gửi cảnh báo qua Gmail

Gmail không cho đăng nhập bằng mật khẩu thường, phải dùng **App Password**:

1. Vào https://myaccount.google.com/security, bật **Xác minh 2 bước**.
2. Vào https://myaccount.google.com/apppasswords, tạo một mật khẩu ứng dụng, đặt tên bất kỳ. Google hiện một chuỗi 16 ký tự.
3. Ở tab **Email** của Client:
   - Tick **Gửi email**.
   - Nhập Gmail gửi và App Password.
   - Nhập người nhận; nhiều người thì cách nhau bằng dấu phẩy.
4. Bấm **✉ Gửi thông báo** để gửi thử một email kiểm tra.

Email được gửi khi:

- **Mất kết nối:** khi máy lỗi liên tiếp đủ số lần ở ô **Gửi mail sau N lần lỗi** (mặc định 5). Mỗi sự cố chỉ gửi 1 email.
- **Khôi phục:** khi máy kết nối lại, nhưng chỉ khi trước đó đã gửi email mất kết nối.

Bỏ tick **Lưu App Password trên máy này** nếu không muốn mật khẩu bị ghi ra file; khi đó mỗi lần mở Client phải nhập lại.

## 7. Các file cấu hình và dữ liệu

Tất cả nằm ở thư mục đang đứng khi chạy app.

| File | Của | Nội dung | Đưa lên Git / nộp bài? |
| --- | --- | --- | --- |
| `client.properties` | Client | IP Server, cổng, mã bí mật, chu kỳ, timeout, các ngưỡng | Có thể, nhưng nên xóa mã bí mật trước |
| `targets.txt` | Client | Danh sách máy, mỗi dòng `tên;host;TCP hoặc PING;cổng` | Có |
| `email.properties` | Client | Gmail, App Password, người nhận | **Không**. Đã có trong `.gitignore` |
| `server.properties` | Server | Cổng heartbeat, cổng báo cáo, mã bí mật | Có thể, nhưng nên xóa mã bí mật trước |
| `alerts.log` | Server | Nhật ký mọi sự kiện; Server đọc lại khi mở | Tùy |
| `targets.txt.bak` | Client | Bản sao `targets.txt` gốc, tự tạo khi file có dòng sai định dạng | Không |

## 8. Kiến trúc và giao thức

```
┌──────────────────┐          ┌──────────────────┐
│ Client (máy A)   │          │ Client (máy B)   │
└───┬──────────┬───┘          └───┬──────────┬───┘
    │ Ping/TCP │                  │ Ping/TCP │
    ▼          │                  ▼          │
 các máy cần   │               các máy cần   │
 giám sát      │               giám sát      │
               │ cảnh báo, SNAPSHOT, BYE     │
               │ (qua mạng ZeroTier)         │
               ▼                             ▼
        ┌──────────────────────────────────────────┐
        │ Server                                   │
        │  :9001  nhận tin, trả OK hoặc DENIED     │
        │  :9000  trả PONG                         │
        │  Tab: Trạng thái hiện tại, Client,       │
        │       Nhật ký cảnh báo  →  alerts.log    │
        └──────────────────────────────────────────┘
```

- Mỗi Client tự kiểm tra các máy trong danh sách của mình và tự gửi email.
- Server không kiểm tra máy nào; Server chỉ tổng hợp báo cáo từ mọi Client.
- Muốn giám sát chính Server, thêm vào Client một máy loại TCP trỏ tới IP Server, cổng `9000`.

### Các loại tin gửi tới cổng báo cáo (9001)

Mỗi tin gửi qua một kết nối TCP riêng, mã hóa UTF-8, các trường cách nhau bằng `|`. Server trả `OK` nếu nhận, `DENIED` nếu sai mã bí mật; tin sai định dạng thì Server đóng kết nối, không trả lời.

| Tin | Khi nào gửi | Định dạng |
| --- | --- | --- |
| Cảnh báo | Một máy đổi trạng thái DOWN ↔ UP | `STATUS\|tên\|host\|loại\|tên-client\|độ-trễ\|ghi-chú\|thời-gian` (từ `độ-trễ` trở đi có thể bỏ) |
| `SNAPSHOT` | Sau mỗi chu kỳ kiểm tra | Dòng đầu `SNAPSHOT\|tên-client\|chu-kỳ\|timeout\|số-máy`, sau đó mỗi máy một dòng `tên\|host\|loại\|cổng\|trạng-thái\|độ-trễ\|TB\|mất-gói-%\|số-lần-lỗi` |
| `BYE` | Bấm Dừng hoặc đóng Client | `BYE\|tên-client` |
| `KEY` | Đứng trước mọi tin, khi có đặt mã bí mật | `KEY\|mã` |

Ví dụ một tin `SNAPSHOT` có mã bí mật:

```
KEY|nhom5-2026
SNAPSHOT|LAPTOP-AN|5|3|2
google|8.8.8.8|TCP|443|UP|21.4|23.0|0|0
router|192.168.1.1|PING|0|DOWN|-|2.1|40|3
```

Cổng heartbeat (9000) chỉ trả `PONG` cho mọi kết nối, dùng để Client kiểm tra Server còn sống.

### Cách phát hiện sự cố

| Sự kiện | Điều kiện |
| --- | --- |
| Máy **DOWN** | Lỗi liên tiếp đủ "Báo DOWN sau N lần lỗi" (mặc định 3) |
| Máy **UP** lại | Lần kiểm tra đầu tiên thành công sau khi DOWN; kèm thời gian gián đoạn |
| Gửi email DOWN | Lỗi liên tiếp đủ "Gửi mail sau N lần lỗi" (mặc định 5), mỗi sự cố 1 lần |
| Client **OFFLINE** | Server không nhận `SNAPSHOT` trong 3 × (chu kỳ + timeout + 2) giây, tối thiểu 15 giây. Với mặc định 5 và 3 giây là 30 giây. Phải cộng timeout vì một vòng kiểm tra kéo dài hơn khi máy đích không trả lời. |
| Tin bị **DENIED** | Thiếu hoặc sai mã bí mật. Mỗi IP chỉ ghi 1 dòng mỗi phút để nhật ký không bị ngập. |

## 9. Kịch bản demo

Cần 2 máy cùng mạng ZeroTier: **máy S** chạy Server, **máy C** chạy Client. Toàn bộ mất khoảng 10 phút.

1. **Chuẩn bị (trước giờ demo).**
   - Ở máy C, thêm sẵn 3 máy vào danh sách:
     - `google` loại TCP, `8.8.8.8`, cổng `443`.
     - `server-heartbeat` loại TCP, IP ZeroTier của máy S, cổng `9000`.
     - Một máy của bạn cùng nhóm có thể tắt ZeroTier lúc demo.
   - Cài Gmail và bấm **Gửi thông báo** để chắc email chạy.
   - Ở Server, bấm **Cài đặt** và đặt mã bí mật, ví dụ `nhom5`.
2. **Mở Server** ở máy S, bấm **Sao chép IP**.
3. **Mở Client** ở máy C, dán IP vào tab Cấu hình. Nhập mã bí mật **sai** rồi bấm **Bắt đầu**. Thanh dưới cùng báo *sai mã bí mật*, và Nhật ký của Server có dòng DENIED. Sửa lại mã cho đúng, bấm Dừng rồi Bắt đầu lại.
4. **Bình thường:** tab Trạng thái hiện tại của Server hiện đủ các máy UP kèm độ trễ, tab Client hiện máy C ONLINE.
5. **Mất kết nối:** bạn cùng nhóm tắt ZeroTier, hoặc rút mạng máy đích. Sau khoảng 3 chu kỳ, Client báo DOWN, Server bật hộp cảnh báo, thẻ "Đang mất kết nối" chuyển đỏ. Khi đủ 5 lần lỗi, email đến.
6. **Khôi phục:** bật lại mạng. Client báo UP kèm thời gian gián đoạn, email khôi phục đến.
7. **Thống kê:** ở Client mở tab Thống kê, chọn máy vừa bị sự cố để xem biểu đồ có vạch đỏ, rồi bấm **Xuất CSV**.
8. **Client chết đột ngột:** ở máy C, tắt Client bằng Task Manager (End task), hoặc tắt ZeroTier của máy C. Khoảng 30 giây sau, Server báo **CLIENT MẤT LIÊN LẠC** và tab Client hiện OFFLINE. Mở lại Client và bấm Bắt đầu, Server báo **CLIENT LIÊN LẠC LẠI**.
9. **Dừng bình thường:** bấm Dừng ở Client. Server hiện ĐÃ DỪNG và không báo động. So sánh với bước 8 để thấy khác biệt.
10. **Kết:** ở Server mở tab Nhật ký cảnh báo, lọc DOWN, rồi **Xuất CSV**.

## 10. Lỗi thường gặp

| Hiện tượng | Cách xử lý |
| --- | --- |
| `UnsupportedClassVersionError` khi chạy JAR | Máy đang chạy Java cũ hơn bản đã dùng để build (đang là 22). Cách 1: cài JDK 22. Cách 2: người build vào NetBeans → Project Properties → Sources → **Source/Binary Format** chọn 17, rồi Clean and Build lại. Code chạy được với Java 17. |
| Server báo "Lỗi cổng 9000/9001" khi mở | Đang có một Server khác mở sẵn, hoặc chương trình khác dùng cổng đó. Đóng Server cũ, hoặc đổi cổng trong Cài đặt. |
| Client "không liên lạc được" với Server | Kiểm tra theo thứ tự: IP đúng chưa (dùng nút Sao chép IP); cổng báo cáo trùng chưa; Server đã mở chưa; tường lửa máy Server đã Allow Java chưa (mục 3); hai máy cùng network ZeroTier và đã được Auth chưa. |
| Client "từ chối kết nối (sai mã bí mật)" | Mã ở tab Cấu hình của Client phải giống hệt mã trong Cài đặt của Server, phân biệt hoa thường. |
| Máy loại PING luôn DOWN dù vẫn chạy | Windows chặn ping theo mặc định. Đổi sang loại TCP với một cổng đang mở trên máy đó, hoặc trên máy đích bật rule *File and Printer Sharing (Echo Request - ICMPv4-In)* trong Windows Defender Firewall. |
| Gửi email báo lỗi 535 | Sai Gmail hoặc App Password. Tạo App Password mới (mục 6). Mật khẩu Gmail thường không dùng được. |
| Mở CSV bằng Excel thấy mọi thứ dồn vào 1 cột | Máy đang để định dạng vùng dùng dấu `;`. Trong Excel vào **Data → From Text/CSV** để mở, chọn dấu phân cách là dấu phẩy. |
| Mở app lên mất hết cấu hình, danh sách máy | Đang chạy ở thư mục khác lần trước (xem lưu ý ở mục 2). |
| Danh sách máy thiếu vài dòng, Nhật ký Client báo "Bỏ qua dòng sai định dạng" | Sửa lại dòng đó trong `targets.txt`. File gốc được lưu ở `targets.txt.bak`. |

## 11. Cấu trúc mã nguồn

| File | Nội dung |
| --- | --- |
| `src/netalertserver/NetAlertServer.java` | Điểm chạy của file JAR: màn hình chọn Server/Client, hoặc nhận tham số `server`/`client` |
| `src/netalertserver/ServerMain.java` | Server: 2 cổng mạng, xử lý các loại tin, phát hiện Client OFFLINE, 3 tab giao diện, Cài đặt |
| `src/netalertserver/ClientMain.java` | Client: kiểm tra Ping/TCP song song, cảnh báo, gửi Gmail qua SMTP/SSL, gửi tin về Server, thống kê |
| `src/netalertserver/UiKit.java` | Thành phần giao diện dùng chung: thẻ số liệu, bảng tô dòng DOWN, hộp cảnh báo, biểu đồ độ trễ, xuất CSV |

## Tài liệu
- [Cập nhật bảng đóng góp](CapNhatBangDongGop.docx)