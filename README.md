# Burp MCP 扩展方案

本项目是一个让 Claude Code 通过 MCP (Model Context Protocol) 协议实时分析 Burp Suite 代理流量的集成方案。

项目分为两部分：
1. **Burp 插件 (Java)**：暴露 Burp 内部数据为本地 HTTP REST API。
2. **MCP Server (Python)**：连接 Claude Code 与 Burp 插件。

---

## 1. 安装与构建 Burp 插件

该插件使用 Java 和 Gradle 构建，推荐使用 Burp Suite Professional 版本。

### 构建步骤
1. 确保你已安装 JDK 17 或以上版本。
2. 进入 `burp-extension` 目录：
   ```bash
   cd burp-extension
   ```
3. 使用 Gradle 打包成单个可执行 jar（Fat JAR）：
   ```bash
   # 如果你没有安装 gradle，可以使用你的环境自带的方式，或者使用 brew install gradle 安装
   gradle shadowJar
   ```
4. 构建成功后，产物位于 `burp-extension/build/libs/burp-extension-1.0.0-all.jar` (或者类似的带 `-all.jar` 后缀的文件)。

### 安装到 Burp
1. 打开 Burp Suite Professional。
2. 导航到 **Extensions** -> **Installed** 标签页。
3. 点击 **Add** 按钮。
4. 在 Extension Details 窗口中：
   - Extension type 选择 **Java**。
   - Extension file 选择刚才构建好的 `-all.jar` 文件。
5. 点击 **Next**。你可以在 `Output` 标签看到“HTTP API 服务已成功启动，监听端口: 8181”的提示。

---

## 2. 运行 Python MCP Server

MCP Server 是 Claude Code 与 Burp 插件通信的桥梁。

### 环境要求
- **Python**: 3.10 及以上版本。
- 建议使用虚拟环境或者全局安装依赖。

### 安装依赖
我们使用 `uv` 或 `pip` 安装所需的库。
进入 `mcp-server` 目录：
```bash
cd mcp-server
pip install mcp httpx
```

### 测试连接
在启动 MCP Server 之前，可以先测试它是否能连接到 Burp 插件：
```bash
python test_connection.py
```
如果输出 `✅ 连接成功！`，说明准备就绪。

---

## 3. 配置 Claude Code

要让 Claude Code 能够调用我们的 MCP Server，需要在配置中注册。

将本项目根目录下的 `claude_mcp_config.json` 文件中的内容添加到你的 Claude MCP 配置文件中（或者直接告诉 Claude 读取该文件）。

配置示例：
```json
{
  "mcpServers": {
    "burp": {
      "command": "python",
      "args": [
        "/Users/abcd/开发/java/BurpMcp/mcp-server/burp_mcp_server.py"
      ],
      "env": {
        "BURP_API_BASE_URL": "http://127.0.0.1:8181"
      }
    }
  }
}
```
*注意：请根据你的实际项目路径修改 `args` 中的文件路径。*

---

## 4. 常见问题排查 (FAQ)

### Q1: HTTP 服务启动失败 / 端口占用
**现象**：Burp 的 Extension Output 报错 `HTTP API 服务启动失败: Address already in use`。
**解决**：端口 8181 被其他程序占用了。你可以关闭占用 8181 端口的程序，或者在源码 `BurpMcpExtension.java` 中将 `8181` 改为其他端口，并重新构建。记得同时更新 Claude 配置中的 `BURP_API_BASE_URL`。

### Q2: Python 运行报错 "SyntaxError" 或 "ModuleNotFoundError"
**现象**：执行 Python 脚本时提示语法错误或者找不到模块。
**解决**：
- 请确保使用的是 Python 3.10+。可以通过 `python --version` 检查。
- 确保执行了 `pip install mcp httpx`，如果使用了虚拟环境，请确保使用的是该虚拟环境中的 Python。

### Q3: Burp 版本兼容性问题
**现象**：插件加载时提示 `java.lang.NoClassDefFoundError` 或类似错误。
**解决**：本项目使用了最新版的 Montoya API（`2023.12.1`）。请确保你的 Burp Suite 是较新的版本（建议 2023.12 或更高版本）。

### Q4: Claude 无法调用 Burp 工具
**现象**：Claude 返回工具执行超时或连接被拒绝。
**解决**：
1. 检查 Burp Suite 是否处于开启状态，并且插件已勾选启用。
2. 运行 `test_connection.py` 检查是否是网络问题。
3. 检查 `claude_mcp_config.json` 中配置的 `python` 命令是否为你的系统中的正确 Python 路径（如果在虚拟环境中，可能需要配置为绝对路径，如 `/path/to/venv/bin/python`）。
