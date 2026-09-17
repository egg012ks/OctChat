# OctChat IM

局域网（内网）**非商用**即时通讯软件：Go 后端 + Java Swing 前端。

> **定位声明**：OctChat 面向局域网 / 内网环境设计，为非商用项目。请勿用于公网运营或商业分发。

## 功能

- 私聊：RSA-2048 交换 AES-256 会话密钥，端到端加密
- 群组、频道
- 局域网文件传输（服务端中转，一次下载即删除）
- 消息本地历史（H2 嵌入式数据库）
- 服务器自动发现（UDP 广播 / WSL IP 探测）

## 项目结构

| 文件 | 说明 |
|---|---|
| `OctChatClient.java` | 前端：Java Swing 桌面客户端（单文件） |
| `server.go` | 后端：Go 服务器（纯 Go 实现，无 cgo / libc 依赖） |

## 启动方式

### 服务端（WSL / Linux）

```bash
cd ~/code
./build.sh          # 或 go build -o octchat-server .
./run_server.sh     # 消息端口 9654，文件端口 9008
```

### 客户端（Windows）

- 双击 `OctChatClient.jar`（需 Java 17+），或双击 `OctChat.exe`（自带 JRE，免安装）
- 客户端自动发现服务器；未发现时手动填写 `WSL IP:9654`（如 `172.26.246.95:9654`）
- 配置自动持久化在 `%APPDATA%\OctChat\server.properties`

## 安全边界（重要）

- 私聊消息为端到端加密（RSA + AES）
- **文件传输走 HTTP 明文**（9008 端口），仅建议在可信局域网内使用
- 密码使用 bcrypt 加盐存储
- 群组 / 频道消息及用户数据存于服务端 `octchat.db`，仅服务端可见

## 第三方组件与许可

| 组件 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| H2 Database | 2.2.220 | **MPL-2.0 或 EPL-1.0（双许可）** | 前端本地消息历史（未修改） |
| Oracle JDK | 25 (LTS) | Oracle NFTC | 仅内嵌于 `OctChat.exe` 运行时 |

- H2 源码获取：<https://github.com/h2database/h2database>
- H2 许可全文：<https://www.h2database.com/html/license.html>（MPL-2.0 / EPL-1.0 双许可，允许免费集成与分发）
- Oracle JDK 25 依据 Oracle No-Fee Terms and Conditions（NFTC）免费使用与再分发，禁止收费再分发；非商用免费分发不受影响

## 许可证

本项目以 **MIT License** 发布。详见仓库 `LICENSE` 文件。