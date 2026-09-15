# 服务端部署

## 启动

安装 Java 21，将 Release 中的 `MsgAT-Console-1.1.jar` 放入服务目录：

```bash
java -jar MsgAT-Console-1.1.jar
```

默认监听 TCP 34478。可通过第一个参数指定其他端口：

```bash
java -jar MsgAT-Console-1.1.jar 34479
```

程序在当前目录创建 `data/`，保存用户资料和离线包。升级时先停止旧进程、备份并保留数据目录，再更换 JAR。不要同时运行多个进程访问同一数据库。原程序内置数据库密钥已恢复，本版无需 `MSGAT_DATABASE_KEY` 或额外数据库密钥文件；混淆 JAR 支持正常 JVM 校验，无需 `-noverify`。

## TLS

默认自动生成临时自签名证书。三端客户端默认允许不受信任的证书，后端重启无需手动更新指纹。

若需要固定证书，可同时设置：

```bash
export MSGAT_TLS_CERT=/path/to/fullchain.pem
export MSGAT_TLS_KEY=/path/to/privkey.pem
java -jar MsgAT-Console-1.1.jar
```

只配置其中一个变量会导致启动失败。客户端填写指纹时，指纹必须与服务端实际证书匹配。未填写指纹并关闭兼容选项时，使用系统证书校验。

## 持久运行和网络

建议用操作系统服务管理器设置工作目录、自动重启和日志。程序的数据目录与当前工作目录有关，也可用 `-Dmsgat.data.dir=/path/to/data` 显式指定，参数放在 `-jar` 前。

服务端仅承担聊天和通话信令中继。语音媒体通过 WebRTC 连接，跨 NAT 或严格防火墙环境可能需要独立 TURN 服务及相应网络规则。

正常关闭和重启保留离线消息。`MSGAT_ENABLE_KILL_SWITCH` 默认不启用；不要在普通部署中开启，该功能包含破坏性清理逻辑。安全边界见 [SECURITY.md](../SECURITY.md)。
