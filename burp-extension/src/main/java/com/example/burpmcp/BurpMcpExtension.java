package com.example.burpmcp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.extension.ExtensionUnloadingHandler;

import javax.swing.*;
import java.awt.*;

public class BurpMcpExtension implements BurpExtension {
    private HttpApiServer httpServer;

    @Override
    public void initialize(MontoyaApi api) {
        // 设置插件名称
        api.extension().setName("Burp MCP 插件");

        // 输出启动日志
        api.logging().logToOutput("正在初始化 Burp MCP 插件...");

        boolean isRunning = false;
        String statusMessage = "HTTP API 服务未启动";

        try {
            // 启动 HTTP 服务，监听 8181 端口
            httpServer = new HttpApiServer(api, 8181);
            httpServer.start();
            isRunning = true;
            statusMessage = "HTTP API 服务运行中，监听端口: 8181";
            api.logging().logToOutput(statusMessage);
        } catch (Exception e) {
            statusMessage = "HTTP API 服务启动失败: " + e.getMessage();
            api.logging().logToError(statusMessage);
            api.logging().logToError(e.toString());
        }

        // 注册插件卸载时的回调，确保关闭 HTTP 服务，释放端口
        api.extension().registerUnloadingHandler(new ExtensionUnloadingHandler() {
            @Override
            public void extensionUnloaded() {
                if (httpServer != null) {
                    api.logging().logToOutput("插件正在卸载，准备停止 HTTP API 服务...");
                    httpServer.stop();
                    api.logging().logToOutput("HTTP API 服务已成功停止，端口已释放。");
                }
            }
        });

        // 注册 UI Tab，显示当前运行状态
        JPanel panel = new JPanel(new BorderLayout());
        JLabel statusLabel = new JLabel(statusMessage, SwingConstants.CENTER);
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        if (isRunning) {
            statusLabel.setForeground(new Color(34, 139, 34)); // 绿色表示成功
        } else {
            statusLabel.setForeground(Color.RED); // 红色表示失败
        }
        panel.add(statusLabel, BorderLayout.CENTER);

        api.userInterface().registerSuiteTab("Burp MCP", panel);
    }
}
