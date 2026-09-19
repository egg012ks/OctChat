# OctChat IM

局域网（内网）**非商用**即时通讯客户端：Java Swing 桌面程序。

> **定位声明**：OctChat 面向局域网 / 内网环境设计，为非商用项目。请勿用于公网运营或商业分发。
>
> **本仓库仅为客户端**，服务端（Go 实现）独立发布，不在本仓库中。

## 功能

- 私聊：RSA-2048 交换 AES-256 会话密钥，端到端加密
- 群组、频道
- 局域网文件传输（一次下载即删除）
- 消息本地历史（H2 嵌入式数据库）
- 服务器自动发现（UDP 广播 / WSL IP 探测）

## 下载

请到 [Releases](https://github.com/sakura01266/OctChat/releases) 页面下载对应平台的安装包：

| 平台 | 格式 | 下载链接 |
|---|---|---|
| Linux | DEB | [octchat_0.1.1-1_amd64.deb](https://github.com/sakura01266/OctChat/releases/download/v0.1.1/octchat_0.1.1-1_amd64.deb) |
| Windows | EXE | [OctChat-0.1.1.exe](https://github.com/sakura01266/OctChat/releases/download/v0.1.1/OctChat-0.1.1.exe) |
| Windows | MSI | [OctChat-0.1.1.msi](https://github.com/sakura01266/OctChat/releases/download/v0.1.1/OctChat-0.1.1.msi) |
| 通用 | JAR | [OctChatClient.jar](https://github.com/sakura01266/OctChat/releases/download/v0.1.1/OctChatClient.jar) |

> **通用 JAR 说明**：需要本机已安装 Java 17 或更高版本。Windows 的 EXE / MSI 安装包已内嵌 JRE，双击即用。

## 使用方式

### 启动客户端

- **Windows**：双击 `OctChat.exe` 或 `OctChat-0.1.1.exe`（自带 JRE，免安装）
- **Linux**：安装 DEB / RPM 后从应用菜单启动，或运行 `octchat`
- **通用 JAR**：双击 `OctChatClient.jar`（需 Java 17+）

### 连接服务器

- 客户端会自动发现局域网内的服务器
- 未发现时手动填写服务器地址，如 `192.168.1.10:9654`
- 配置自动持久化在 `%APPDATA%\OctChat\server.properties`（Windows）或 `~/.octchat/server.properties`（Linux）

## 安全边界（重要）

- 私聊消息为端到端加密（RSA + AES）
- **文件传输走 HTTP 明文**，仅建议在可信局域网内使用
- 密码使用 bcrypt 加盐存储
- 群组 / 频道消息及用户数据存于服务端数据库，仅服务端可见

## 第三方组件与许可

| 组件 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| H2 Database | 2.2.220 | **MPL-2.0 或 EPL-1.0（双许可）** | 本地消息历史（未修改） |

- H2 源码获取：<https://github.com/h2database/h2database>
- H2 许可全文：<https://www.h2database.com/html/license.html>（MPL-2.0 / EPL-1.0 双许可，允许免费集成与分发）

## 许可证

本项目以 **MIT License** 发布。详见仓库 `LICENSE` 文件。
