package com.example.burpmcp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HttpApiServer {
    private final MontoyaApi api;
    private final int port;
    private ServerSocket serverSocket;
    private final Gson gson;
    private final ExecutorService threadPool;
    private boolean isRunning = false;

    public HttpApiServer(MontoyaApi api, int port) {
        this.api = api;
        this.port = port;
        this.gson = new Gson();
        this.threadPool = Executors.newFixedThreadPool(4);
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        isRunning = true;
        
        // 在新线程中接收请求，避免阻塞主线程
        new Thread(() -> {
            while (isRunning) {
                try {
                    Socket socket = serverSocket.accept();
                    threadPool.execute(() -> handleConnection(socket));
                } catch (IOException e) {
                    if (isRunning) {
                        api.logging().logToError("Socket 接受连接异常: " + e.getMessage());
                    }
                }
            }
        }).start();
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            threadPool.shutdownNow();
        } catch (IOException e) {
            api.logging().logToError("停止 ServerSocket 异常: " + e.getMessage());
        }
    }

    private void handleConnection(Socket socket) {
        try (InputStream is = socket.getInputStream();
             OutputStream os = socket.getOutputStream()) {
             
            // 简单地读取 HTTP 请求的头部
            StringBuilder requestBuilder = new StringBuilder();
            int c;
            int consecutiveNewlines = 0;
            while ((c = is.read()) != -1) {
                requestBuilder.append((char) c);
                if (c == '\r') {
                    // ignore
                } else if (c == '\n') {
                    consecutiveNewlines++;
                    if (consecutiveNewlines == 2) {
                        break; // header end
                    }
                } else {
                    consecutiveNewlines = 0;
                }
            }
            
            String requestHeader = requestBuilder.toString();
            if (requestHeader.isEmpty()) return;

            String[] lines = requestHeader.split("\n");
            if (lines.length == 0) return;
            String[] requestLine = lines[0].split(" ");
            if (requestLine.length < 2) return;
            
            String method = requestLine[0];
            String uri = requestLine[1];
            
            String path = uri;
            String query = null;
            int qIdx = uri.indexOf('?');
            if (qIdx != -1) {
                path = uri.substring(0, qIdx);
                query = uri.substring(qIdx + 1);
            }

            // 获取 Content-Length
            int contentLength = 0;
            for (String line : lines) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            // 读取 Body
            byte[] bodyBytes = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int count = is.read(bodyBytes, read, contentLength - read);
                if (count == -1) break;
                read += count;
            }
            String body = new String(bodyBytes, StandardCharsets.UTF_8);

            // 路由
            try {
                if ("/health".equals(path) && "GET".equals(method)) {
                    handleHealth(os);
                } else if ("/proxy/history".equals(path) && "GET".equals(method)) {
                    handleProxyHistory(os, query);
                } else if ("/proxy/history/detail".equals(path) && "GET".equals(method)) {
                    handleProxyHistoryDetail(os, query);
                } else if ("/proxy/history/range".equals(path) && "GET".equals(method)) {
                    handleProxyHistoryRange(os, query);
                } else if ("/repeater/send".equals(path) && "POST".equals(method)) {
                    handleRepeaterSend(os, body);
                } else if ("/scanner/issues".equals(path) && "GET".equals(method)) {
                    handleScannerIssues(os);
                } else if ("/proxy/intercept".equals(path) && "POST".equals(method)) {
                    handleProxyIntercept(os);
                } else {
                    sendError(os, 404, "Not Found");
                }
            } catch (Exception e) {
                sendError(os, 500, "内部错误: " + e.getMessage());
            }

        } catch (IOException e) {
            api.logging().logToError("处理连接异常: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void sendResponse(OutputStream os, int statusCode, String responseStr) throws IOException {
        byte[] bytes = responseStr.getBytes(StandardCharsets.UTF_8);
        String statusText = statusCode == 200 ? "OK" : "Error";
        String header = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                        "Content-Type: application/json; charset=UTF-8\r\n" +
                        "Content-Length: " + bytes.length + "\r\n" +
                        "Connection: close\r\n\r\n";
        os.write(header.getBytes(StandardCharsets.UTF_8));
        os.write(bytes);
        os.flush();
    }

    private void sendError(OutputStream os, int statusCode, String message) throws IOException {
        JsonObject errorObj = new JsonObject();
        errorObj.addProperty("error", message);
        sendResponse(os, statusCode, gson.toJson(errorObj));
    }

    private String getQueryParam(String query, String paramName) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2 && pair[0].equals(paramName)) {
                return pair[1];
            }
        }
        return null;
    }

    // GET /health
    private void handleHealth(OutputStream os) throws IOException {
        JsonObject resp = new JsonObject();
        resp.addProperty("status", "ok");
        resp.addProperty("version", "1.0.0");
        resp.addProperty("port", port);
        sendResponse(os, 200, gson.toJson(resp));
    }

    // GET /proxy/history?limit=50&filter=keyword
    private void handleProxyHistory(OutputStream os, String query) throws IOException {
        String limitStr = getQueryParam(query, "limit");
        String filter = getQueryParam(query, "filter");
        
        int limit = 50;
        if (limitStr != null) {
            try {
                limit = Integer.parseInt(limitStr);
                if (limit > 500) limit = 500;
            } catch (NumberFormatException ignored) {}
        }

        List<ProxyHttpRequestResponse> history = api.proxy().history();
        JsonArray results = new JsonArray();
        
        int count = 0;
        for (int i = history.size() - 1; i >= 0 && count < limit; i--) {
            ProxyHttpRequestResponse item = history.get(i);
            HttpRequest req = item.request();
            String url = req.url();
            
            if (filter != null && !filter.isEmpty() && !url.toLowerCase().contains(filter.toLowerCase())) {
                continue;
            }

            JsonObject obj = new JsonObject();
            obj.addProperty("index", i);
            obj.addProperty("method", req.method());
            obj.addProperty("url", url);
            
            HttpService service = req.httpService();
            obj.addProperty("host", service.host());
            obj.addProperty("port", service.port());
            obj.addProperty("https", service.secure());
            obj.addProperty("path", req.path());
            
            if (item.response() != null) {
                obj.addProperty("statusCode", item.response().statusCode());
                obj.addProperty("mimeType", item.response().inferredMimeType().name());
                obj.addProperty("responseLength", item.response().toByteArray().length());
            } else {
                obj.addProperty("statusCode", 0);
                obj.addProperty("mimeType", "NONE");
                obj.addProperty("responseLength", 0);
            }
            obj.addProperty("requestLength", req.toByteArray().length());
            obj.addProperty("timestamp", "2025-01-01T12:00:00Z");
            
            results.add(obj);
            count++;
        }
        
        sendResponse(os, 200, gson.toJson(results));
    }

    // GET /proxy/history/detail?index=0
    private void handleProxyHistoryDetail(OutputStream os, String query) throws IOException {
        String indexStr = getQueryParam(query, "index");
        if (indexStr == null) {
            sendError(os, 400, "缺少 index 参数");
            return;
        }
        int index = Integer.parseInt(indexStr);
        List<ProxyHttpRequestResponse> history = api.proxy().history();
        if (index < 0 || index >= history.size()) {
            sendError(os, 404, "未找到对应索引的历史记录");
            return;
        }
        
        ProxyHttpRequestResponse item = history.get(index);
        JsonObject obj = new JsonObject();
        obj.addProperty("index", index);
        obj.addProperty("request", item.request().toByteArray().toString());
        if (item.response() != null) {
            obj.addProperty("response", item.response().toByteArray().toString());
        } else {
            obj.addProperty("response", "");
        }
        
        sendResponse(os, 200, gson.toJson(obj));
    }

    // GET /proxy/history/range?from=0&to=10
    private void handleProxyHistoryRange(OutputStream os, String query) throws IOException {
        String fromStr = getQueryParam(query, "from");
        String toStr = getQueryParam(query, "to");
        if (fromStr == null || toStr == null) {
            sendError(os, 400, "缺少 from 或 to 参数");
            return;
        }
        int from = Integer.parseInt(fromStr);
        int to = Integer.parseInt(toStr);
        
        List<ProxyHttpRequestResponse> history = api.proxy().history();
        JsonArray results = new JsonArray();
        
        for (int i = from; i <= to && i < history.size(); i++) {
            if (i < 0) continue;
            ProxyHttpRequestResponse item = history.get(i);
            JsonObject obj = new JsonObject();
            obj.addProperty("index", i);
            obj.addProperty("request", item.request().toByteArray().toString());
            if (item.response() != null) {
                obj.addProperty("response", item.response().toByteArray().toString());
            } else {
                obj.addProperty("response", "");
            }
            results.add(obj);
        }
        
        sendResponse(os, 200, gson.toJson(results));
    }

    // POST /repeater/send
    private void handleRepeaterSend(OutputStream os, String body) throws IOException {
        if (body == null || body.isEmpty()) {
            sendError(os, 400, "请求体为空");
            return;
        }
        
        JsonObject reqObj = gson.fromJson(body, JsonObject.class);
        
        String host = reqObj.get("host").getAsString();
        int port = reqObj.get("port").getAsInt();
        boolean secure = reqObj.get("https").getAsBoolean();
        String requestText = reqObj.get("request").getAsString();
        String tabName = reqObj.has("tabName") ? reqObj.get("tabName").getAsString() : "MCP-Req";
        
        HttpService service = HttpService.httpService(host, port, secure);
        HttpRequest request = HttpRequest.httpRequest(service, ByteArray.byteArray(requestText));
        
        api.repeater().sendToRepeater(request, tabName);
        
        JsonObject resp = new JsonObject();
        resp.addProperty("status", "success");
        sendResponse(os, 200, gson.toJson(resp));
    }

    // GET /scanner/issues
    private void handleScannerIssues(OutputStream os) throws IOException {
        List<AuditIssue> issues = api.siteMap().issues();
        JsonArray results = new JsonArray();
        
        for (AuditIssue issue : issues) {
            JsonObject obj = new JsonObject();
            obj.addProperty("issueName", issue.name());
            obj.addProperty("severity", issue.severity().name());
            obj.addProperty("confidence", issue.confidence().name());
            obj.addProperty("url", issue.baseUrl());
            obj.addProperty("detail", issue.detail());
            results.add(obj);
        }
        
        sendResponse(os, 200, gson.toJson(results));
    }

    // POST /proxy/intercept
    private void handleProxyIntercept(OutputStream os) throws IOException {
        JsonObject resp = new JsonObject();
        resp.addProperty("status", "warning");
        resp.addProperty("message", "Montoya API 暂不支持直接通过插件修改全局拦截开关");
        sendResponse(os, 200, gson.toJson(resp));
    }
}
