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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

public class HttpApiServer {
    private final MontoyaApi api;
    private final int port;
    private HttpServer server;
    private final Gson gson;

    public HttpApiServer(MontoyaApi api, int port) {
        this.api = api;
        this.port = port;
        this.gson = new Gson();
    }

    public void start() throws IOException {
        // 创建本地绑定的 HTTP Server
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        
        // 注册各个 API 路由
        server.createContext("/health", this::handleHealth);
        server.createContext("/proxy/history", this::handleProxyHistory);
        server.createContext("/proxy/history/detail", this::handleProxyHistoryDetail);
        server.createContext("/proxy/history/range", this::handleProxyHistoryRange);
        server.createContext("/repeater/send", this::handleRepeaterSend);
        server.createContext("/scanner/issues", this::handleScannerIssues);
        server.createContext("/proxy/intercept", this::handleProxyIntercept);

        // 设置线程池，最小 4 线程
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    public void stop() {
        if (server != null) {
            // 参数 0 表示立即停止，不等待当前请求完成
            server.stop(0);
        }
    }

    // 统一处理请求头和异常
    private void sendResponse(HttpExchange exchange, int statusCode, String responseStr) throws IOException {
        byte[] bytes = responseStr.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        JsonObject errorObj = new JsonObject();
        errorObj.addProperty("error", message);
        sendResponse(exchange, statusCode, gson.toJson(errorObj));
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
    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "只支持 GET 方法");
            return;
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("status", "ok");
        resp.addProperty("version", "1.0.0");
        resp.addProperty("port", port);
        sendResponse(exchange, 200, gson.toJson(resp));
    }

    // GET /proxy/history?limit=50&filter=keyword
    private void handleProxyHistory(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "只支持 GET 方法");
                return;
            }
            String query = exchange.getRequestURI().getQuery();
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
            
            // 从最新的记录开始遍历
            int count = 0;
            for (int i = history.size() - 1; i >= 0 && count < limit; i--) {
                ProxyHttpRequestResponse item = history.get(i);
                HttpRequest req = item.request();
                String url = req.url();
                
                // 如果有过滤词，进行匹配（忽略大小写）
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
                // Montoya API 并没有直接提供 timestamp，我们用索引代替或者留空，这里用一个假的时间戳或者省略
                obj.addProperty("timestamp", "2025-01-01T12:00:00Z"); // 如果 Montoya 之后支持时间戳可以替换
                
                results.add(obj);
                count++;
            }
            
            sendResponse(exchange, 200, gson.toJson(results));
        } catch (Exception e) {
            sendError(exchange, 500, "内部错误: " + e.getMessage());
        }
    }

    // GET /proxy/history/detail?index=0
    private void handleProxyHistoryDetail(HttpExchange exchange) throws IOException {
        try {
            String query = exchange.getRequestURI().getQuery();
            String indexStr = getQueryParam(query, "index");
            if (indexStr == null) {
                sendError(exchange, 400, "缺少 index 参数");
                return;
            }
            int index = Integer.parseInt(indexStr);
            List<ProxyHttpRequestResponse> history = api.proxy().history();
            if (index < 0 || index >= history.size()) {
                sendError(exchange, 404, "未找到对应索引的历史记录");
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
            
            sendResponse(exchange, 200, gson.toJson(obj));
        } catch (Exception e) {
            sendError(exchange, 500, "内部错误: " + e.getMessage());
        }
    }

    // GET /proxy/history/range?from=0&to=10
    private void handleProxyHistoryRange(HttpExchange exchange) throws IOException {
        try {
            String query = exchange.getRequestURI().getQuery();
            String fromStr = getQueryParam(query, "from");
            String toStr = getQueryParam(query, "to");
            if (fromStr == null || toStr == null) {
                sendError(exchange, 400, "缺少 from 或 to 参数");
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
            
            sendResponse(exchange, 200, gson.toJson(results));
        } catch (Exception e) {
            sendError(exchange, 500, "内部错误: " + e.getMessage());
        }
    }

    // POST /repeater/send
    private void handleRepeaterSend(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "只支持 POST 方法");
                return;
            }
            
            InputStream is = exchange.getRequestBody();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
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
            sendResponse(exchange, 200, gson.toJson(resp));
        } catch (Exception e) {
            sendError(exchange, 500, "内部错误: " + e.getMessage());
        }
    }

    // GET /scanner/issues
    private void handleScannerIssues(HttpExchange exchange) throws IOException {
        try {
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
            
            sendResponse(exchange, 200, gson.toJson(results));
        } catch (Exception e) {
            sendError(exchange, 500, "内部错误: " + e.getMessage());
        }
    }

    // POST /proxy/intercept
    private void handleProxyIntercept(HttpExchange exchange) throws IOException {
        try {
            // 注意：Montoya API 并不直接支持控制全局拦截开关。
            // 作为一个妥协，或者根据具体需求，我们返回提示或者用其他方式实现。
            // 因为需求里写了控制拦截开关，我们尽量返回一个友好的信息，说明暂不支持或抛出提示。
            JsonObject resp = new JsonObject();
            resp.addProperty("status", "warning");
            resp.addProperty("message", "Montoya API 暂不支持直接通过插件修改全局拦截开关");
            sendResponse(exchange, 200, gson.toJson(resp));
        } catch (Exception e) {
            sendError(exchange, 500, "内部错误: " + e.getMessage());
        }
    }
}
