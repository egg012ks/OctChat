import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.swing.*;

public class OctChatClient extends JFrame {
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss");
    private static final String DEFAULT_SERVER_IP = "127.0.0.1";
    private static final int DEFAULT_PORT = 9654;
    private static final int DEFAULT_FILE_PORT = 9008;
    private static final int DEFAULT_DISCOVERY_PORT = 9009;

    static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    // ---------- 账号相关 ----------
    private String userID;
    private String password;
    private String nickname;
    private String serverHost;

    // ---------- 加密相关 ----------
    private PrivateKey privateKey;
    private PublicKey publicKey;
    private Map<String, PublicKey> publicKeyCache = new ConcurrentHashMap<>();
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean running = true;

    // UI
    private DefaultListModel<String> userListModel = new DefaultListModel<>();
    private JList<String> userList = new JList<>(userListModel);
    private DefaultListModel<String> groupListModel = new DefaultListModel<>();
    private JList<String> groupList = new JList<>(groupListModel);
    private DefaultListModel<String> channelListModel = new DefaultListModel<>();
    private JList<String> channelList = new JList<>(channelListModel);

    private ChatBubblePanel chatArea = new ChatBubblePanel();
    private JTextField inputField = new JTextField();
    private JButton sendBtn = new JButton("发送");
    private JTabbedPane leftTabPane;
    private JButton deleteBtn;
    private String currentTarget = null;
    private String currentType = "NONE";
    private String currentDisplayName = "";
    private String currentCreator = "";
    private Map<String, Boolean> receiptMap = new ConcurrentHashMap<>();

    // 日志
    private final StringBuilder logBuffer = new StringBuilder();
    private JDialog logDialog;
    private JTextArea logTextArea;

    // 数据库管理器
    private DatabaseManager dbManager;

    // ---------- 构造函数 ----------
    public OctChatClient(LoginResult loginResult) throws Exception {
        this.userID = loginResult.userID;
        this.password = loginResult.password;
        this.nickname = loginResult.nickname;
        this.serverHost = loginResult.serverHost;
        this.privateKey = loginResult.privateKey;
        this.publicKey = loginResult.publicKey;

        dbManager = new DatabaseManager();
        dbManager.init();

        initUI();
        initLogWindow();

        socket = loginResult.socket;
        out = loginResult.out;
        in = loginResult.in;
        out.println("UPDATE_PUBLIC_KEY|" + Base64.getEncoder().encodeToString(publicKey.getEncoded()));
        running = true;
        new Thread(this::receiveLoop).start();

        setVisible(true);
        appendLog("欢迎 " + nickname + "！已连接到服务器: " + loginResult.serverHost);
        appendLog("左侧双击用户/群组/频道切换聊天上下文。");
        chatArea.addSystemMessage("聊天会话区域");
    }

    // ============ 服务器检测 ============
    private boolean checkServer(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isReachable(1000);
        } catch (Exception e) {
            return false;
        }
    }

    private void connectServer(String serverHost) throws IOException {
        InetAddress serverAddr = InetAddress.getByName(serverHost);
        socket = new Socket();
        socket.connect(new InetSocketAddress(serverAddr, AppConfig.port), 3000);
        out = new PrintWriter(socket.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        appendLog("登录公钥已生成，编码长度: " + publicKey.getEncoded().length + "字节，Base64长度: " + publicKeyBase64.length());
        out.println(buildLoginCommand(userID, password, publicKeyBase64));
        running = true;
        new Thread(this::receiveLoop).start();
    }

    private String buildLoginCommand(String id, String secret, String keyBase64) {
        // 协议: LOGIN|ID|密码|公钥（本地IP由服务器自行获取，不再拼入避免污染公钥字段）
        return "LOGIN|" + id + "|" + secret + "|" + keyBase64;
    }

    private void initLogWindow() {
        logDialog = new JDialog(this, "系统日志", false);
        logDialog.setSize(600, 400);
        logDialog.setLocationRelativeTo(this);
        logTextArea = new JTextArea();
        logTextArea.setEditable(false);
        logTextArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logDialog.add(new JScrollPane(logTextArea));
        logDialog.setVisible(false);
    }

    private void appendLog(String msg) {
        String ts = TIME_FMT.format(new Date());
        String line = String.format("[%s] %s%n", ts, msg);
        synchronized (logBuffer) {
            logBuffer.append(line);
        }
        SwingUtilities.invokeLater(() -> {
            if (logTextArea != null) {
                synchronized (logBuffer) {
                    logTextArea.setText(logBuffer.toString());
                }
            }
        });
    }

    private void generateRSAKeys() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair pair = keyGen.generateKeyPair();
        this.publicKey = pair.getPublic();
        this.privateKey = pair.getPrivate();
    }

    private PublicKey getPublicKey(String username) throws Exception {
        if (publicKeyCache.containsKey(username)) {
            appendLog("发送消息时公钥缓存命中，目标ID: " + username);
            return publicKeyCache.get(username);
        }
        appendLog("发送消息时公钥缓存未命中，目标ID: " + username);
        for (int attempt = 1; attempt <= 2; attempt++) {
            appendLog("请求公钥，目标ID: " + username + "，第" + attempt + "次");
            out.println("GET_PUBLIC_KEY|" + username);
            long start = System.currentTimeMillis();
            while (!publicKeyCache.containsKey(username) && (System.currentTimeMillis() - start) < 3000) {
                Thread.sleep(100);
            }
            if (publicKeyCache.containsKey(username)) {
                appendLog("公钥请求成功并写入缓存，目标ID: " + username);
                return publicKeyCache.get(username);
            }
        }
        throw new Exception("无法获取用户 " + username + " 的公钥");
    }

    private String encryptMessage(String message, PublicKey receiverPublicKey) throws Exception {
        KeyGenerator aesGen = KeyGenerator.getInstance("AES");
        aesGen.init(256);
        SecretKey aesKey = aesGen.generateKey();
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, receiverPublicKey);
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());
        Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, ivSpec);
        byte[] encryptedContent = aesCipher.doFinal(message.getBytes("UTF-8"));
        String ivBase64 = Base64.getEncoder().encodeToString(iv);
        String aesKeyBase64 = Base64.getEncoder().encodeToString(encryptedAesKey);
        String contentBase64 = Base64.getEncoder().encodeToString(encryptedContent);
        String combined = ivBase64 + "|" + aesKeyBase64 + "|" + contentBase64;
        return Base64.getEncoder().encodeToString(combined.getBytes("UTF-8"));
    }

    private String decryptMessage(String encryptedPayload) throws Exception {
        String combined = new String(Base64.getDecoder().decode(encryptedPayload), "UTF-8");
        String[] parts = combined.split("\\|");
        if (parts.length != 3) {
            throw new Exception("密文格式错误");
        }
        byte[] iv = Base64.getDecoder().decode(parts[0]);
        byte[] encryptedAesKey = Base64.getDecoder().decode(parts[1]);
        byte[] encryptedContent = Base64.getDecoder().decode(parts[2]);
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
        Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, ivSpec);
        byte[] plainBytes = aesCipher.doFinal(encryptedContent);
        return new String(plainBytes, "UTF-8");
    }

    private void receiveLoop() {
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                if (parts.length == 0 || parts[0].isBlank()) {
                    continue;
                }
                String type = parts[0];
                SwingUtilities.invokeLater(() -> handleMessage(type, parts));
            }
        } catch (IOException e) {
            if (running) {
                appendLog("与服务器断开连接");
                SwingUtilities.invokeLater(() -> {
                    if (isDisplayable()) {
                        JOptionPane.showMessageDialog(this, "与服务器断开连接", "连接断开", JOptionPane.WARNING_MESSAGE);
                    }
                });
            }
        }
    }

    // ---------- 消息处理器 ----------
    private void handleMessage(String type, String[] parts) {
        try {
            switch (type) {
                case "USER_LIST":
                    userListModel.clear();
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        for (String entry : parts[1].split(",")) {
                            if (entry == null || entry.isBlank()) {
                                continue;
                            }
                            String[] kv = entry.split(":", 2);
                            String id = kv.length > 0 ? kv[0] : "";
                            String name = kv.length > 1 ? kv[1] : id;
                            if (!id.isEmpty() && !id.equals(userID)) {
                                userListModel.addElement(name + " (" + id + ")");
                                if (!publicKeyCache.containsKey(id)) {
                                    appendLog("USER_LIST触发请求公钥，目标ID: " + id);
                                    if (out != null) {
                                        out.println("GET_PUBLIC_KEY|" + id);
                                    }
                                }
                            }
                        }
                    }
                    break;
                case "GROUP_LIST":
                    groupListModel.clear();
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        for (String entry : parts[1].split(",")) {
                            if (entry != null && !entry.isBlank()) {
                                groupListModel.addElement(entry);
                            }
                        }
                    }
                    break;
                case "CHANNEL_LIST":
                    channelListModel.clear();
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        for (String entry : parts[1].split(",")) {
                            if (entry != null && !entry.isBlank()) {
                                channelListModel.addElement(entry);
                            }
                        }
                    }
                    break;
                case "PUBLIC_KEY": {
                    if (parts.length < 3) {
                        appendLog("忽略无效公钥消息: " + Arrays.toString(parts));
                        break;
                    }
                    String id = parts[1];
                    String keyBase64 = parts[2];
                    String preview = keyBase64.substring(0, Math.min(20, keyBase64.length()));
                    appendLog("收到公钥，ID: " + id + "，前20字符: " + preview);
                    byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
                    X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
                    KeyFactory kf = KeyFactory.getInstance("RSA");
                    PublicKey pk = kf.generatePublic(spec);
                    publicKeyCache.put(id, pk);
                    break;
                }
                case "PRIVATE_MSG": {
                    if (parts.length < 3) {
                        appendLog("忽略无效私聊消息: " + Arrays.toString(parts));
                        break;
                    }
                    String senderId = parts[1];
                    String encryptedPayload = parts[2];
                    try {
                        String decrypted = decryptMessage(encryptedPayload);
                        if (senderId.equals(userID)) {
                            chatArea.addSelfMessage("我 -> " + currentDisplayName + ": " + decrypted);
                        } else {
                            chatArea.addOtherMessage(senderId, decrypted);
                            dbManager.saveMessage(senderId, senderId, decrypted, System.currentTimeMillis(), true, "private");
                            currentTarget = senderId;
                            currentType = "USER";
                            currentDisplayName = senderId;
                            if (deleteBtn != null) {
                                deleteBtn.setVisible(false);
                            }
                        }
                    } catch (Exception e) {
                        appendLog("解密失败:" + e.getMessage());
                    }
                    break;
                }
                case "READ_RECEIPT": {
                    if (parts.length < 3) {
                        break;
                    }
                    String msgId = parts[1];
                    String reader = parts[2];
                    receiptMap.put(msgId, true);
                    chatArea.addSystemMessage(reader + " 已读消息");
                    break;
                }
                case "GROUP_MSG": {
                    if (parts.length < 4) {
                        break;
                    }
                    String groupId = parts[1];
                    String senderId = parts[2];
                    String content = parts[3];
                    if (groupId.equals(currentTarget) || currentTarget == null) {
                        chatArea.addOtherMessage(senderId, content);
                        dbManager.saveMessage(groupId, senderId, content, System.currentTimeMillis(), true, "group");
                    }
                    break;
                }
                case "CHANNEL_MSG": {
                    if (parts.length < 4) {
                        break;
                    }
                    String channelId = parts[1];
                    String senderId = parts[2];
                    String content = parts[3];
                    if (channelId.equals(currentTarget) || currentTarget == null) {
                        chatArea.addOtherMessage(senderId, content);
                        dbManager.saveMessage(channelId, senderId, content, System.currentTimeMillis(), true, "channel");
                    }
                    break;
                }
                case "FILE_MSG": {
                    // 协议: FILE_MSG|发送者ID|文件名(URL编码)|大小|fileID
                    if (parts.length < 5) {
                        appendLog("忽略无效文件消息: " + Arrays.toString(parts));
                        break;
                    }
                    String senderId = parts[1];
                    String encodedName = parts[2];
                    String fileSizeStr = parts[3];
                    String fileId = parts[4];
                    String fileName = decodeFileName(encodedName);
                    long fileSize = parseIntSafe(fileSizeStr, 0);
                    chatArea.addSystemMessage(senderId + " 向你发送文件: " + fileName + " (" + formatSize(fileSize) + ")");
                    SwingUtilities.invokeLater(() -> promptReceiveFile(senderId, fileName, fileSize, fileId));
                    break;
                }
                case "SYSTEM":
                    if (parts.length < 2) {
                        break;
                    }
                    String sysMsg = parts[1];
                    appendLog(sysMsg);
                    chatArea.addSystemMessage(sysMsg);
                    if (sysMsg.contains("注册成功") && sysMsg.contains("你的ID:")) {
                        String[] msgParts = sysMsg.split("你的ID: ");
                        if (msgParts.length > 1) {
                            String newID = msgParts[1].trim();
                            SwingUtilities.invokeLater(() -> {
                                setTitle("OctChat - " + nickname + " (ID: " + newID + ")");
                                JOptionPane.showMessageDialog(this,
                                    "注册成功！你的用户ID是: " + newID + "\n请牢记此ID，下次登录需要使用。",
                                    "注册成功", JOptionPane.INFORMATION_MESSAGE);
                            });
                        }
                    }
                    if (sysMsg.contains("注销成功") || sysMsg.contains("账号已注销")) {
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(this, "账号已成功注销", "注销成功", JOptionPane.INFORMATION_MESSAGE);
                            System.exit(0);
                        });
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            appendLog("handleMessage异常:" + e.getMessage());
            e.printStackTrace();
        }
    }

    // ---------- UI 初始化 ----------
    private void initUI() {
        setTitle("OctChat - " + nickname + (userID.isEmpty() ? "" : " (ID: " + userID + ")"));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(820, 620);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(8, 8));

        leftTabPane = new JTabbedPane();
        leftTabPane.setPreferredSize(new Dimension(200, 0));

        // 私聊
        userList.setBackground(new Color(240, 248, 255));
        userList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String selected = userList.getSelectedValue();
                    if (selected != null) {
                        String targetId = selected;
                        if (selected.contains("(")) {
                            targetId = selected.substring(selected.lastIndexOf("(") + 1, selected.lastIndexOf(")"));
                        }
                        currentTarget = targetId;
                        currentType = "USER";
                        currentDisplayName = selected;
                        deleteBtn.setVisible(false);
                        chatArea.clearMessages();
                        java.util.List<DatabaseManager.MessageRecord> history = dbManager.getHistory(targetId);
                        for (DatabaseManager.MessageRecord rec : history) {
                            if (rec.isReceived) {
                                chatArea.addOtherMessage(rec.sender, rec.content);
                            } else {
                                chatArea.addSelfMessage("我 -> " + currentDisplayName + ": " + rec.content);
                            }
                        }
                        chatArea.addSystemMessage("=== 正在私聊 " + selected + " ===");
                    }
                }
            }
        });
        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setBorder(BorderFactory.createTitledBorder("在线用户 (双击私聊)"));
        leftTabPane.addTab("私聊", userScroll);

        // 群组
        groupList.setBackground(new Color(255, 250, 240));
        groupList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String selected = groupList.getSelectedValue();
                    if (selected != null) {
                        String[] parts = selected.split(":", 3);
                        String id = parts[0];
                        String name = parts.length > 1 ? parts[1] : id;
                        String creator = parts.length > 2 ? parts[2] : "";
                        currentTarget = id;
                        currentType = "GROUP";
                        currentDisplayName = name;
                        currentCreator = creator;
                        deleteBtn.setVisible(creator.equals(userID));
                        chatArea.clearMessages();
                        java.util.List<DatabaseManager.MessageRecord> history = dbManager.getHistory(id);
                        for (DatabaseManager.MessageRecord rec : history) {
                            if (rec.isReceived) {
                                chatArea.addOtherMessage(rec.sender, rec.content);
                            } else {
                                chatArea.addSelfMessage("我: " + rec.content);
                            }
                        }
                        chatArea.addSystemMessage("=== 进入群组 " + name + " (创建者: " + creator + ") ===");
                        out.println("JOIN_GROUP|" + id);
                    }
                }
            }
        });
        JScrollPane groupScroll = new JScrollPane(groupList);
        groupScroll.setBorder(BorderFactory.createTitledBorder("群组 (双击加入)"));
        leftTabPane.addTab("群组", groupScroll);

        // 频道
        channelList.setBackground(new Color(230, 245, 255));
        channelList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String selected = channelList.getSelectedValue();
                    if (selected != null) {
                        String[] parts = selected.split(":", 3);
                        String id = parts[0];
                        String name = parts.length > 1 ? parts[1] : id;
                        String creator = parts.length > 2 ? parts[2] : "";
                        currentTarget = id;
                        currentType = "CHANNEL";
                        currentDisplayName = name;
                        currentCreator = creator;
                        deleteBtn.setVisible(creator.equals(userID));
                        chatArea.clearMessages();
                        java.util.List<DatabaseManager.MessageRecord> history = dbManager.getHistory(id);
                        for (DatabaseManager.MessageRecord rec : history) {
                            if (rec.isReceived) {
                                chatArea.addOtherMessage(rec.sender, rec.content);
                            } else {
                                chatArea.addSelfMessage("我: " + rec.content);
                            }
                        }
                        chatArea.addSystemMessage("=== 订阅频道 " + name + " (创建者: " + creator + ") ===");
                        chatArea.addSystemMessage("提示: 只有创建者可以发送消息");
                        out.println("JOIN_CHANNEL|" + id);
                    }
                }
            }
        });
        JScrollPane channelScroll = new JScrollPane(channelList);
        channelScroll.setBorder(BorderFactory.createTitledBorder("频道 (双击订阅)"));
        leftTabPane.addTab("频道", channelScroll);

        add(leftTabPane, BorderLayout.WEST);

        chatArea.setBackground(new Color(235, 235, 235));
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setBorder(null);
        chatScroll.getViewport().setBackground(new Color(235, 235, 235));
        add(chatScroll, BorderLayout.CENTER);

        // 底部面板
        JPanel bottomPanel = new JPanel(new GridBagLayout());
        JPanel leftBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton createGroupBtn = new JButton("创建群组");
        createGroupBtn.addActionListener(e -> {
            String name = JOptionPane.showInputDialog("输入群组名:");
            if (name != null && !name.trim().isEmpty()) {
                out.println("CREATE_GROUP|" + name.trim());
            }
        });
        leftBtnPanel.add(createGroupBtn);

        JButton createChannelBtn = new JButton("创建频道");
        createChannelBtn.addActionListener(e -> {
            String name = JOptionPane.showInputDialog("输入频道名:");
            if (name != null && !name.trim().isEmpty()) {
                out.println("CREATE_CHANNEL|" + name.trim());
            }
        });
        leftBtnPanel.add(createChannelBtn);

        deleteBtn = new JButton("删除此群组/频道");
        deleteBtn.setBackground(new Color(220, 80, 80));
        deleteBtn.setForeground(Color.WHITE);
        deleteBtn.setVisible(false);
        deleteBtn.addActionListener(e -> {
            if (currentTarget == null) return;
            int confirm = JOptionPane.showConfirmDialog(this,
                    "确定要删除 " + currentDisplayName + " 吗？此操作不可撤销！",
                    "确认删除", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                if (currentType.equals("GROUP")) {
                    out.println("DELETE_GROUP|" + currentTarget);
                } else if (currentType.equals("CHANNEL")) {
                    out.println("DELETE_CHANNEL|" + currentTarget);
                }
                chatArea.addSystemMessage("已请求删除 " + currentDisplayName);
                deleteBtn.setVisible(false);
            }
        });
        leftBtnPanel.add(deleteBtn);

        JButton moreBtn = new JButton("更多");
        JPopupMenu moreMenu = new JPopupMenu();

        JMenuItem exportItem = new JMenuItem("导出 Markdown");
        exportItem.addActionListener(e -> exportChatHistory());
        moreMenu.add(exportItem);

        JMenuItem reconnectItem = new JMenuItem("刷新连接");
        reconnectItem.addActionListener(e -> doReconnect());
        moreMenu.add(reconnectItem);

        JMenuItem disconnectItem = new JMenuItem("断开连接");
        disconnectItem.addActionListener(e -> doDisconnect());
        moreMenu.add(disconnectItem);

        JMenuItem viewLogItem = new JMenuItem("查看日志");
        viewLogItem.addActionListener(e -> {
            logDialog.setLocationRelativeTo(this);
            logDialog.setVisible(true);
        });
        moreMenu.add(viewLogItem);

        JMenuItem deleteAccountItem = new JMenuItem("申请注销账号");
        deleteAccountItem.addActionListener(e -> requestDeleteAccount());
        moreMenu.add(deleteAccountItem);

        moreBtn.addActionListener(e -> moreMenu.show(moreBtn, 0, moreBtn.getHeight()));
        leftBtnPanel.add(moreBtn);

        inputField.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        sendBtn.setBackground(new Color(70, 130, 200));
        sendBtn.setForeground(Color.WHITE);
        sendBtn.setFocusPainted(false);
        inputField.addActionListener(e -> sendMessage());
        sendBtn.addActionListener(e -> sendMessage());

        JPanel rightBtnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton fileBtn = new JButton("发送文件");
        fileBtn.setBackground(new Color(90, 160, 90));
        fileBtn.setForeground(Color.WHITE);
        fileBtn.setFocusPainted(false);
        fileBtn.addActionListener(e -> chooseAndSendFile());
        rightBtnPanel.add(fileBtn);
        rightBtnPanel.add(sendBtn);

        GridBagConstraints leftConstraints = new GridBagConstraints();
        leftConstraints.gridx = 0;
        leftConstraints.gridy = 0;
        leftConstraints.weightx = 0;
        leftConstraints.fill = GridBagConstraints.NONE;
        leftConstraints.anchor = GridBagConstraints.WEST;
        leftConstraints.insets = new Insets(0, 0, 0, 5);
        bottomPanel.add(leftBtnPanel, leftConstraints);

        GridBagConstraints inputConstraints = new GridBagConstraints();
        inputConstraints.gridx = 1;
        inputConstraints.gridy = 0;
        inputConstraints.weightx = 1;
        inputConstraints.fill = GridBagConstraints.HORIZONTAL;
        inputConstraints.insets = new Insets(0, 0, 0, 5);
        bottomPanel.add(inputField, inputConstraints);

        GridBagConstraints rightConstraints = new GridBagConstraints();
        rightConstraints.gridx = 2;
        rightConstraints.gridy = 0;
        rightConstraints.weightx = 0;
        rightConstraints.fill = GridBagConstraints.NONE;
        rightConstraints.anchor = GridBagConstraints.EAST;
        bottomPanel.add(rightBtnPanel, rightConstraints);
        add(bottomPanel, BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                running = false;
                try { socket.close(); } catch (Exception ignored) {}
                dbManager.close();
            }
        });
    }

    private void doDisconnect() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ex) {
            appendLog("断开连接异常:" + ex.getMessage());
        }
        appendLog("已手动断开服务器连接");
        chatArea.addSystemMessage("已断开连接");
    }

    private void doReconnect() {
        doDisconnect();
        String serverHost = JOptionPane.showInputDialog("输入服务器地址重新连接：", AppConfig.hostPort());
        if (serverHost == null || serverHost.isBlank()) return;
        try {
            connectServer(serverHost.trim());
            appendLog("重连服务器成功:" + serverHost);
            chatArea.addSystemMessage("已重新连接");
        } catch (Exception ex) {
            appendLog("重连失败:" + ex.getMessage());
            JOptionPane.showMessageDialog(this, "重连失败：" + ex.getMessage());
        }
    }

    private void requestDeleteAccount() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "确定要申请注销账号吗？\n提交后 5 分钟内可取消，超时将自动删除。",
                "申请注销", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        String password = JOptionPane.showInputDialog(this, "请输入密码确认：");
        if (password == null || password.trim().isEmpty()) return;

        out.println("DELETE_ACCOUNT|" + password.trim());
        chatArea.addSystemMessage("注销申请已提交，5 分钟后账号将自动注销");
        appendLog("已提交注销申请");
    }

    private void exportChatHistory() {
        String chatText = chatArea.getTextContent();
        if (chatText.isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前没有聊天记录可导出");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存聊天记录");
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        chooser.setSelectedFile(new File("OctChat_聊天记录_" + nickname + "_" + timestamp + ".md"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try (FileWriter fw = new FileWriter(chooser.getSelectedFile())) {
                fw.write("# OctChat 聊天记录\n\n");
                fw.write("- 昵称: " + nickname + "\n");
                fw.write("- ID: " + userID + "\n");
                fw.write("- 导出时间: " + new Date() + "\n");
                fw.write("- 服务器: " + (serverHost == null ? AppConfig.hostPort() : serverHost + ":" + AppConfig.port) + "\n\n");
                fw.write("---\n\n");
                fw.write(chatText);
                JOptionPane.showMessageDialog(this, "导出成功！\n" + chooser.getSelectedFile().getAbsolutePath());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "导出失败: " + ex.getMessage());
            }
        }
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;
        if (currentTarget == null || currentType.equals("NONE")) {
            chatArea.addSystemMessage("请先在左侧双击选择一个用户、群组或频道");
            return;
        }
        String msgId = UUID.randomUUID().toString();
        switch (currentType) {
            case "USER": {
                String target = currentTarget;
                String display = currentDisplayName;
                String msg = text;
                String id = msgId;
                new Thread(() -> {
                    try {
                        PublicKey receiverPubKey = getPublicKey(target);
                        String encrypted = encryptMessage(msg, receiverPubKey);
                        out.println("SEND_PRIVATE|" + target + "|" + encrypted + "|" + id);
                        SwingUtilities.invokeLater(() -> {
                            chatArea.addSelfMessage(display + ": " + msg);
                            receiptMap.put(id, false);
                            dbManager.saveMessage(target, userID, msg, System.currentTimeMillis(), false, "private");
                        });
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> appendLog("消息发送失败: " + e.getMessage()));
                    }
                }).start();
                break;
            }
            case "GROUP":
                out.println("SEND_GROUP|" + currentTarget + "|" + text);
                chatArea.addSelfMessage("我: " + text);
                dbManager.saveMessage(currentTarget, userID, text, System.currentTimeMillis(), false, "group");
                break;
            case "CHANNEL":
                out.println("SEND_CHANNEL|" + currentTarget + "|" + text);
                chatArea.addSelfMessage("我: " + text);
                dbManager.saveMessage(currentTarget, userID, text, System.currentTimeMillis(), false, "channel");
                break;
            default:
                chatArea.addSystemMessage("未知的聊天类型");
                break;
        }
        inputField.setText("");
    }

    // ============ 文件传输（服务端中转） ============
    private void chooseAndSendFile() {
        if (currentTarget == null || currentType.equals("NONE")) {
            chatArea.addSystemMessage("请先在左侧双击选择一个私聊用户，再发送文件");
            return;
        }
        if (!currentType.equals("USER")) {
            chatArea.addSystemMessage("文件传输当前仅支持私聊，请双击选择一个在线用户");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择要发送的文件");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file == null || !file.isFile()) {
            return;
        }
        String target = currentTarget;
        String display = currentDisplayName;
        String name = file.getName();
        long size = file.length();
        chatArea.addSystemMessage("正在上传文件: " + name + " (" + formatSize(size) + ")...");
        new Thread(() -> {
            try {
                String fileId = uploadFile(file);
                String encodedName = encodeFileName(name);
                out.println("SEND_FILE|" + target + "|" + encodedName + "|" + size + "|" + fileId);
                SwingUtilities.invokeLater(() ->
                        chatArea.addSystemMessage("文件已发送给 " + display + "（等待对方接收）"));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    chatArea.addSystemMessage("文件发送失败: " + ex.getMessage());
                    appendLog("文件上传失败: " + ex.getMessage());
                });
            }
        }, "octchat-upload").start();
    }

    private String fileServerBase() {
        return "http://" + AppConfig.serverHost + ":" + DEFAULT_FILE_PORT;
    }

    private String uploadFile(File file) throws Exception {
        String url = fileServerBase() + "/upload?name=" + encodeFileName(file.getName()) + "&size=" + file.length();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(600000); // 大文件上传可能较久
        conn.setFixedLengthStreamingMode(file.length());
        try (FileInputStream fis = new FileInputStream(file);
             OutputStream os = conn.getOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("上传失败 HTTP " + code);
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            String fileId = br.readLine();
            if (fileId == null || fileId.isBlank()) {
                throw new IOException("服务器未返回文件ID");
            }
            return fileId.trim();
        }
    }

    private void promptReceiveFile(String senderId, String fileName, long fileSize, String fileId) {
        int confirm = JOptionPane.showConfirmDialog(this,
                senderId + " 向你发送文件：\n" + fileName + " (" + formatSize(fileSize) + ")\n是否接收？",
                "收到文件", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            chatArea.addSystemMessage("已拒绝接收文件: " + fileName);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存文件到");
        chooser.setSelectedFile(new File(sanitizeFileName(fileName)));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            chatArea.addSystemMessage("已取消接收文件: " + fileName);
            return;
        }
        File dest = chooser.getSelectedFile();
        chatArea.addSystemMessage("正在接收文件: " + fileName + " ...");
        new Thread(() -> {
            try {
                downloadFile(fileId, dest);
                SwingUtilities.invokeLater(() ->
                        chatArea.addSystemMessage("文件接收完成: " + dest.getAbsolutePath()));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    chatArea.addSystemMessage("文件接收失败: " + ex.getMessage());
                    appendLog("文件下载失败: " + ex.getMessage());
                });
            }
        }, "octchat-download").start();
    }

    private void downloadFile(String fileId, File dest) throws Exception {
        String url = fileServerBase() + "/download?file=" + fileId;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(600000);
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("下载失败 HTTP " + code);
        }
        try (InputStream is = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
    }

    private static String encodeFileName(String name) throws Exception {
        return URLEncoder.encode(name, "UTF-8");
    }

    private static String decodeFileName(String encoded) {
        try {
            return URLDecoder.decode(encoded, "UTF-8");
        } catch (Exception e) {
            return encoded;
        }
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static class LoginResult {
        String userID;
        String password;
        String nickname;
        String serverHost;
        PrivateKey privateKey;
        PublicKey publicKey;
        Socket socket;
        PrintWriter out;
        BufferedReader in;

        LoginResult(String userID, String password, String nickname, String serverHost,
                PrivateKey privateKey, PublicKey publicKey,
                Socket socket, PrintWriter out, BufferedReader in) {
            this.userID = userID;
            this.password = password;
            this.nickname = nickname;
            this.serverHost = serverHost;
            this.privateKey = privateKey;
            this.publicKey = publicKey;
            this.socket = socket;
            this.out = out;
            this.in = in;
        }
    }

    private static class LoginDialog extends JDialog {
        private final JTextField serverField = new JTextField(AppConfig.hostPort(), 18);
        private JButton discoverBtn;
        private JLabel statusLabel;

        LoginDialog() {
            super((Frame) null, "OctChat 登录", true);
            setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            setSize(400, 150);
            setLocationRelativeTo(null);

            JPanel content = new JPanel(new BorderLayout(8, 8));
            content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            statusLabel = new JLabel("支持格式: 主机 或 主机:端口，如 192.168.1.10:9654");
            statusLabel.setForeground(new Color(120, 130, 150));
            content.add(statusLabel, BorderLayout.NORTH);
            JPanel serverPanel = new JPanel(new BorderLayout(8, 8));
            serverPanel.add(new JLabel("服务器地址:"), BorderLayout.WEST);
            serverPanel.add(serverField, BorderLayout.CENTER);
            discoverBtn = new JButton("自动发现");
            discoverBtn.addActionListener(e -> discoverInBackground());
            serverPanel.add(discoverBtn, BorderLayout.EAST);
            content.add(serverPanel, BorderLayout.CENTER);

            JButton loginButton = new JButton("登录");
            JButton registerButton = new JButton("注册");
            JButton exitButton = new JButton("退出");
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.add(loginButton);
            buttonPanel.add(registerButton);
            buttonPanel.add(exitButton);
            content.add(buttonPanel, BorderLayout.SOUTH);
            setContentPane(content);

            loginButton.addActionListener(e -> showLoginForm());
            registerButton.addActionListener(e -> showRegisterForm());
            exitButton.addActionListener(e -> {
                dispose();
                System.exit(0);
            });

            // 若地址仍是默认值，启动时后台自动探测一次服务器
            autoDiscoverOnOpen();
        }

        private String[] getServerAddress() {
            String text = serverField.getText().trim();
            if (text.isEmpty()) {
                return new String[]{AppConfig.serverHost, String.valueOf(AppConfig.port)};
            }
            if (text.contains(":")) {
                int idx = text.lastIndexOf(':');
                String host = text.substring(0, idx).trim();
                String prt = text.substring(idx + 1).trim();
                if (host.isEmpty()) host = AppConfig.serverHost;
                return new String[]{host, prt};
            }
            return new String[]{text, String.valueOf(AppConfig.port)};
        }

        private void discoverInBackground() {
            if (discoverBtn == null) return;
            discoverBtn.setEnabled(false);
            statusLabel.setText("正在自动发现服务器...");
            new Thread(() -> {
                java.util.List<String> found = ServerDiscovery.discover(AppConfig.discoveryPort);
                SwingUtilities.invokeLater(() -> {
                    discoverBtn.setEnabled(true);
                    if (found.isEmpty()) {
                        statusLabel.setText("未发现服务器，请手动输入地址");
                    } else {
                        statusLabel.setText("发现 " + found.size() + " 台服务器，已填入第一台");
                        serverField.setText(found.get(0));
                    }
                });
            }, "octchat-discover").start();
        }

        private void autoDiscoverOnOpen() {
            String cur = serverField.getText().trim();
            if (cur.isEmpty() || cur.equals(DEFAULT_SERVER_IP) || cur.equals(DEFAULT_SERVER_IP + ":" + DEFAULT_PORT)) {
                discoverInBackground();
            }
        }

        private void showLoginForm() {
            JPanel panel = new JPanel(new GridLayout(2, 2, 8, 8));
            JTextField idField = new JTextField();
            JPasswordField passwordField = new JPasswordField();
            panel.add(new JLabel("用户ID:"));
            panel.add(idField);
            panel.add(new JLabel("密码:"));
            panel.add(passwordField);
            int result = JOptionPane.showConfirmDialog(this, panel, "登录账号",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return;

            String userID = idField.getText().trim();
            String password = new String(passwordField.getPassword());
            if (userID.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(this, "用户ID和密码不能为空");
                return;
            }
            authenticateAsync("LOGIN|" + userID + "|" + password, userID, password, null);
        }

        private void showRegisterForm() {
            JPanel panel = new JPanel(new GridLayout(2, 2, 8, 8));
            JTextField nicknameField = new JTextField();
            JPasswordField passwordField = new JPasswordField();
            panel.add(new JLabel("昵称（可选）:"));
            panel.add(nicknameField);
            panel.add(new JLabel("密码:"));
            panel.add(passwordField);
            int result = JOptionPane.showConfirmDialog(this, panel, "注册账号",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return;

            String nickname = nicknameField.getText();
            String password = new String(passwordField.getPassword());
            if (password.isEmpty()) {
                JOptionPane.showMessageDialog(this, "密码不能为空");
                return;
            }
            authenticateAsync("REGISTER|" + nickname + "|" + password, null, password, nickname);
        }

        private void authenticateAsync(String command, String requestedID,
                                       String password, String requestedNickname) {
            setButtonsEnabled(false);
            new Thread(() -> {
                try {
                    String[] addr = getServerAddress();
                    int prt = parseIntSafe(addr[1], AppConfig.port);
                    LoginResult result = authenticate(addr[0], prt, command, requestedID, password, requestedNickname);
                    AppConfig.saveToConfig(addr[0], prt);
                    SwingUtilities.invokeLater(() -> {
                        dispose();
                        try {
                            new OctChatClient(result);
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(null, "启动主界面失败: " + ex.getMessage());
                            System.exit(1);
                        }
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        setButtonsEnabled(true);
                        JOptionPane.showMessageDialog(this, ex.getMessage(), "连接失败",
                                JOptionPane.ERROR_MESSAGE);
                    });
                }
            }, "octchat-login").start();
        }

        private void setButtonsEnabled(boolean enabled) {
            for (Component component : getContentPane().getComponents()) {
                component.setEnabled(enabled);
                if (component instanceof Container) {
                    for (Component child : ((Container) component).getComponents()) {
                        child.setEnabled(enabled);
                    }
                }
            }
        }

        private static LoginResult authenticate(String host, int port, String command, String requestedID,
                                                String password, String requestedNickname) throws Exception {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), 3000);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                KeyPairGenerator keyGenerator = KeyPairGenerator.getInstance("RSA");
                keyGenerator.initialize(2048);
                KeyPair keyPair = keyGenerator.generateKeyPair();
                String loginCommand = command;
                if (command.startsWith("LOGIN|")) {
                    String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
                    loginCommand = command + "|" + publicKeyBase64;
                }
                out.println(loginCommand);
                String response = in.readLine();
                if (response == null) throw new IOException("服务器未返回响应");
                String[] parts = response.split("\\|", 2);
                if (parts.length == 0 || response.startsWith("REGISTER_FAIL|") || response.startsWith("LOGIN_FAIL|")) {
                    throw new IOException(parts.length > 1 ? parts[1] : "认证失败");
                }
                if (response.startsWith("REGISTER_SUCCESS|")) {
                    String userID = parts.length > 1 ? parts[1] : "";
                    socket.close();
                    return authenticate(host, port, "LOGIN|" + userID + "|" + password, userID, password,
                            requestedNickname);
                }
                if (!response.startsWith("LOGIN_SUCCESS|")) {
                    throw new IOException("服务器响应格式错误: " + response);
                }
                String nickname = parts.length > 1 ? parts[1] : requestedNickname;
                return new LoginResult(requestedID, password, nickname, host,
                        keyPair.getPrivate(), keyPair.getPublic(), socket, out, in);
            } catch (Exception ex) {
                try {
                    socket.close();
                } catch (IOException ignored) {}
                throw ex;
            }
        }
    }

    // ---------- 主入口 ----------
    public static void main(String[] args) {
        String serverArg = null;
        int portArg = 0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server": case "-s":
                    if (i + 1 < args.length) serverArg = args[++i];
                    break;
                case "--port": case "-p":
                    if (i + 1 < args.length) portArg = parseIntSafe(args[++i], 0);
                    break;
                default:
                    break;
            }
        }
        AppConfig.loadFromConfig();
        if (serverArg != null && !serverArg.isBlank()) AppConfig.serverHost = serverArg.trim();
        if (portArg > 0) AppConfig.port = portArg;
        SwingUtilities.invokeLater(() -> new LoginDialog().setVisible(true));
    }

    // ================================================================
    // 内部类：ChatBubblePanel（气泡聊天面板）
    // ================================================================
    class ChatBubblePanel extends JPanel {
        private JPanel contentPanel;
        private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm");
        private final StringBuilder textContent = new StringBuilder();

        public ChatBubblePanel() {
            setLayout(new BorderLayout());
            setBackground(new Color(235, 235, 235));

            contentPanel = new JPanel();
            contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
            contentPanel.setBackground(new Color(235, 235, 235));

            JScrollPane scroll = new JScrollPane(contentPanel);
            scroll.setBorder(null);
            scroll.getViewport().setBackground(new Color(235, 235, 235));
            scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            add(scroll, BorderLayout.CENTER);
        }

        public String getTextContent() {
            return textContent.toString();
        }

        public void addSelfMessage(String content) {
            addBubble(content, true, null);
            textContent.append("[我] ").append(content).append("\n");
        }

        public void addOtherMessage(String sender, String content) {
            addBubble(content, false, sender);
            textContent.append("[").append(sender).append("] ").append(content).append("\n");
        }

        public void addSystemMessage(String content) {
            SwingUtilities.invokeLater(() -> {
                JLabel label = new JLabel("\u00b7 " + content);
                label.setFont(new Font("微软雅黑", Font.PLAIN, 11));
                label.setForeground(new Color(160, 160, 160));
                label.setAlignmentX(Component.CENTER_ALIGNMENT);
                label.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
                contentPanel.add(label);
                textContent.append("[系统] ").append(content).append("\n");
                scrollToBottom();
            });
        }

        public void clearMessages() {
            SwingUtilities.invokeLater(() -> {
                contentPanel.removeAll();
                contentPanel.add(Box.createVerticalStrut(10));
                contentPanel.revalidate();
                contentPanel.repaint();
                textContent.setLength(0);
            });
        }

        private void addBubble(String content, boolean isSelf, String sender) {
            SwingUtilities.invokeLater(() -> {
                String timeStr = timeFmt.format(new Date());

                JLabel timeLabel = new JLabel(timeStr);
                timeLabel.setFont(new Font("微软雅黑", Font.PLAIN, 10));
                timeLabel.setForeground(new Color(180, 180, 180));
                timeLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                timeLabel.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
                contentPanel.add(timeLabel);

                if (!isSelf && sender != null) {
                    JLabel nameLabel = new JLabel(sender);
                    nameLabel.setFont(new Font("微软雅黑", Font.PLAIN, 11));
                    nameLabel.setForeground(new Color(120, 120, 120));
                    nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                    nameLabel.setBorder(BorderFactory.createEmptyBorder(0, 12, 2, 0));
                    contentPanel.add(nameLabel);
                }

                JTextPane bubble = new JTextPane();
                bubble.setEditable(false);
                bubble.setFont(new Font("微软雅黑", Font.PLAIN, 14));
                bubble.setText(content);
                bubble.setOpaque(true);

                if (isSelf) {
                    bubble.setBackground(new Color(74, 144, 217));
                    bubble.setForeground(Color.WHITE);
                    bubble.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(new Color(74, 144, 217), 1),
                            BorderFactory.createEmptyBorder(6, 12, 6, 12)
                    ));
                } else {
                    bubble.setBackground(Color.WHITE);
                    bubble.setForeground(new Color(51, 51, 51));
                    bubble.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(new Color(210, 210, 210), 1),
                            BorderFactory.createEmptyBorder(6, 12, 6, 12)
                    ));
                }

                bubble.setPreferredSize(new Dimension(360, Math.min(bubble.getPreferredSize().height + 16, 180)));

                JPanel wrapper = new JPanel(new FlowLayout(isSelf ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
                wrapper.setOpaque(false);
                wrapper.add(bubble);
                contentPanel.add(wrapper);
                contentPanel.add(Box.createVerticalStrut(2));

                scrollToBottom();
            });
        }

        private void scrollToBottom() {
            SwingUtilities.invokeLater(() -> {
                Container parent = contentPanel.getParent();
                while (parent != null && !(parent instanceof JViewport)) {
                    parent = parent.getParent();
                }
                if (parent != null) {
                    JViewport viewport = (JViewport) parent;
                    viewport.setViewPosition(new Point(0, contentPanel.getPreferredSize().height));
                }
                contentPanel.revalidate();
                contentPanel.repaint();
            });
        }
    }

    // ================================================================
    // 内部类：ConfigManager（跨平台路径）
    // ================================================================
    static class ConfigManager {
        private static Path appDir;

        public static Path getAppDir() {
            if (appDir != null) return appDir;
            String os = System.getProperty("os.name").toLowerCase();
            String home = System.getProperty("user.home");
            if (os.contains("win")) {
                String appData = System.getenv("APPDATA");
                if (appData == null || appData.isEmpty()) {
                    appData = System.getenv("LOCALAPPDATA");
                }
                appDir = Paths.get(appData, "OctChat");
            } else {
                appDir = Paths.get(home, ".octchat");
            }
            try {
                Files.createDirectories(appDir);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return appDir;
        }

        public static Path getDbPath() {
            return getAppDir().resolve("octchat.mv.db");
        }
    }

    // ================================================================
    // 内部类：AppConfig（运行配置：服务器地址/端口，支持 CLI 参数与配置文件记忆）
    // ================================================================
    static class AppConfig {
        static volatile String serverHost = DEFAULT_SERVER_IP;
        static volatile int port = DEFAULT_PORT;
        static volatile int discoveryPort = DEFAULT_DISCOVERY_PORT;

        static void loadFromConfig() {
            try {
                Path f = ConfigManager.getAppDir().resolve("server.properties");
                if (Files.exists(f)) {
                    Properties p = new Properties();
                    try (InputStream in = Files.newInputStream(f)) {
                        p.load(in);
                    }
                    String h = p.getProperty("server");
                    if (h != null && !h.isBlank()) serverHost = h.trim();
                    String pt = p.getProperty("port");
                    if (pt != null && !pt.isBlank()) port = parseIntSafe(pt, DEFAULT_PORT);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        static void saveToConfig(String host, int prt) {
            serverHost = host;
            port = prt;
            try {
                Path f = ConfigManager.getAppDir().resolve("server.properties");
                Properties p = new Properties();
                p.setProperty("server", host);
                p.setProperty("port", String.valueOf(prt));
                try (OutputStream out = Files.newOutputStream(f)) {
                    p.store(out, "OctChat server config");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        static String hostPort() {
            return serverHost + ":" + port;
        }
    }

    // ================================================================
    // 内部类：ServerDiscovery（服务器自动发现）
    // 1) UDP 广播 OCTCHAT_DISCOVER 探测局域网 / WSL 内的服务器
    // 2) Windows 下调用 wsl.exe hostname -I 探测 WSL 内的服务器
    // 3) 回环 127.0.0.1
    // 最终只返回 TCP 端口可达的候选，解决"本机跑 / WSL 跑 / 远程跑"地址不确定的问题。
    // ================================================================
    static class ServerDiscovery {
        static java.util.List<String> discover(int discoveryPort) {
            java.util.List<String> candidates = new ArrayList<>();
            java.util.Set<String> seen = new HashSet<>();

            // 1) UDP 广播
            DatagramSocket ds = null;
            try {
                ds = new DatagramSocket(null);
                ds.setReuseAddress(true);
                ds.setBroadcast(true);
                ds.bind(new InetSocketAddress(0));
                ds.setSoTimeout(1500);
                byte[] payload = "OCTCHAT_DISCOVER".getBytes("UTF-8");
                try {
                    ds.send(new DatagramPacket(payload, payload.length,
                            InetAddress.getByName("255.255.255.255"), discoveryPort));
                } catch (Exception ignored) {}
                try {
                    ds.send(new DatagramPacket(payload, payload.length,
                            InetAddress.getByName("127.0.0.1"), discoveryPort));
                } catch (Exception ignored) {}
                long deadline = System.currentTimeMillis() + 1500;
                byte[] buf = new byte[512];
                while (System.currentTimeMillis() < deadline) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        ds.receive(pkt);
                        String resp = new String(pkt.getData(), 0, pkt.getLength(), "UTF-8").trim();
                        String[] parts = resp.split("\\|");
                        if (parts.length >= 3 && parts[0].equals("OCTCHAT_HERE")) {
                            addCandidate(candidates, seen, parts[1], parseIntSafe(parts[2], DEFAULT_PORT));
                        }
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (ds != null) ds.close();
            }

            // 2) Windows 下探测 WSL 内服务器的 IP
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                try {
                    Process p = new ProcessBuilder("wsl.exe", "-e", "bash", "-lc", "hostname -I")
                            .redirectErrorStream(true).start();
                    String out = new String(p.getInputStream().readAllBytes(), "UTF-8");
                    for (String ip : out.trim().split("\\s+")) {
                        if (!ip.isBlank() && ip.contains(".")) {
                            addCandidate(candidates, seen, ip, DEFAULT_PORT);
                        }
                    }
                    p.destroy();
                } catch (Exception ignored) {}
            }

            // 3) 本机回环
            addCandidate(candidates, seen, "127.0.0.1", DEFAULT_PORT);

            // 4) 只保留 TCP 可达的候选
            java.util.List<String> reachable = new ArrayList<>();
            for (String candidate : candidates) {
                if (tcpReachable(candidate, 400)) {
                    reachable.add(candidate);
                }
            }
            return reachable;
        }

        private static void addCandidate(java.util.List<String> list, java.util.Set<String> seen,
                                         String host, int port) {
            if (host == null || host.isBlank() || host.equals("0.0.0.0")) return;
            String hp = host + ":" + port;
            if (seen.add(hp)) list.add(hp);
        }

        private static boolean tcpReachable(String hostPort, int timeoutMs) {
            int idx = hostPort.lastIndexOf(':');
            String host = idx >= 0 ? hostPort.substring(0, idx) : hostPort;
            int prt = idx >= 0 ? parseIntSafe(hostPort.substring(idx + 1), DEFAULT_PORT) : DEFAULT_PORT;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, prt), timeoutMs);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ================================================================
    // 内部类：DatabaseManager（H2本地数据库）
    // ================================================================
    class DatabaseManager {
        private Connection conn;

        public void init() {
            try {
                Class.forName("org.h2.Driver");
                String url = "jdbc:h2:" + ConfigManager.getDbPath().toString() + ";DB_CLOSE_ON_EXIT=FALSE";
                conn = DriverManager.getConnection(url, "sa", "");
                createTable();
            } catch (Exception e) {
                e.printStackTrace();
                appendLog("数据库初始化失败: " + e.getMessage());
            }
        }

        private void createTable() throws SQLException {
            String sql = "CREATE TABLE IF NOT EXISTS messages (" +
                    "id INTEGER PRIMARY KEY AUTO_INCREMENT," +
                    "target_id VARCHAR(255)," +
                    "sender VARCHAR(255)," +
                    "content TEXT," +
                    "timestamp BIGINT," +
                    "is_received INT," +
                    "type VARCHAR(50)" +
                    ")";
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
        }

        public void saveMessage(String targetId, String sender, String content, long timestamp, boolean isReceived, String type) {
            if (conn == null) {
                appendLog("保存消息失败: 数据库连接未初始化");
                return;
            }
            String sql = "INSERT INTO messages (target_id, sender, content, timestamp, is_received, type) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, targetId);
                pstmt.setString(2, sender);
                pstmt.setString(3, content);
                pstmt.setLong(4, timestamp);
                pstmt.setInt(5, isReceived ? 1 : 0);
                pstmt.setString(6, type);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
                appendLog("保存消息失败: " + e.getMessage());
            }
        }

        public java.util.List<MessageRecord> getHistory(String targetId) {
            java.util.List<MessageRecord> list = new ArrayList<>();
            if (conn == null) {
                return list;
            }
            String sql = "SELECT sender, content, timestamp, is_received FROM messages WHERE target_id = ? ORDER BY timestamp ASC";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, targetId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String sender = rs.getString("sender");
                        String content = rs.getString("content");
                        long timestamp = rs.getLong("timestamp");
                        boolean isReceived = rs.getInt("is_received") == 1;
                        list.add(new MessageRecord(sender, content, timestamp, isReceived));
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
                appendLog("加载历史消息失败: " + e.getMessage());
            }
            return list;
        }

        public void close() {
            try {
                if (conn != null && !conn.isClosed()) conn.close();
            } catch (SQLException ignored) {}
        }

        class MessageRecord {
            String sender;
            String content;
            long timestamp;
            boolean isReceived;
            MessageRecord(String sender, String content, long timestamp, boolean isReceived) {
                this.sender = sender;
                this.content = content;
                this.timestamp = timestamp;
                this.isReceived = isReceived;
            }
        }
    }
}