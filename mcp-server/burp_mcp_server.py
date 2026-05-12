import os
import json
import httpx
from mcp.server.fastmcp import FastMCP

# 从环境变量中读取 Burp 插件的 API 地址，默认值为本地 8181 端口
BURP_API_BASE_URL = os.getenv("BURP_API_BASE_URL", "http://127.0.0.1:8181")

# 初始化 FastMCP
mcp = FastMCP("Burp MCP Server")

def _request(method: str, endpoint: str, **kwargs) -> dict | str:
    """
    内部统一的 HTTP 请求辅助函数，用于调用 Burp 插件的 REST API
    """
    url = f"{BURP_API_BASE_URL}{endpoint}"
    try:
        # 设置超时时间为 5 秒
        response = httpx.request(method, url, timeout=5.0, **kwargs)
        if response.headers.get("content-type", "").startswith("application/json"):
            return response.json()
        return response.text
    except httpx.ConnectError:
        return {"error": "无法连接到 Burp 插件，请确保 Burp Suite 已启动且插件加载成功（默认端口 8181）。"}
    except httpx.TimeoutException:
        return {"error": "连接 Burp 插件超时（5秒），请检查网络或 Burp 状态。"}
    except Exception as e:
        return {"error": f"请求异常: {str(e)}"}

@mcp.tool()
def get_proxy_history(limit: int = 50, filter: str = "") -> str:
    """
    获取 Burp 代理历史摘要。
    
    Args:
        limit: 返回的记录数量限制（默认 50，最大 500）。
        filter: 可选的关键词过滤（对 URL 进行匹配）。
    """
    params = {"limit": limit}
    if filter:
        params["filter"] = filter
    res = _request("GET", "/proxy/history", params=params)
    return json.dumps(res, ensure_ascii=False)

@mcp.tool()
def get_request_detail(index: int) -> str:
    """
    获取某条请求的完整原文。
    
    Args:
        index: 历史记录中的索引。
    """
    res = _request("GET", "/proxy/history/detail", params={"index": index})
    return json.dumps(res, ensure_ascii=False)

@mcp.tool()
def get_request_range(from_index: int, to_index: int) -> str:
    """
    批量获取多条请求的完整原文，用于批量分析。
    
    Args:
        from_index: 起始索引。
        to_index: 结束索引。
    """
    res = _request("GET", "/proxy/history/range", params={"from": from_index, "to": to_index})
    return json.dumps(res, ensure_ascii=False)

@mcp.tool()
def search_history(keyword: str, limit: int = 20) -> str:
    """
    在历史记录中搜索包含关键词的请求。
    
    Args:
        keyword: 搜索关键词。
        limit: 返回数量限制。
    """
    return get_proxy_history(limit=limit, filter=keyword)

@mcp.tool()
def get_scanner_issues() -> str:
    """
    获取 Burp Scanner 发现的所有漏洞（仅限 Professional 版本）。
    """
    res = _request("GET", "/scanner/issues")
    return json.dumps(res, ensure_ascii=False)

@mcp.tool()
def send_to_repeater(host: str, port: int, https: bool, request: str, tab_name: str = "MCP-Req") -> str:
    """
    将指定请求发送到 Burp Repeater 标签页。
    
    Args:
        host: 目标主机名（例如 example.com）。
        port: 目标端口（例如 443）。
        https: 是否为 HTTPS 请求。
        request: 完整的 HTTP 请求报文。
        tab_name: 在 Repeater 中显示的标签页名称。
    """
    payload = {
        "host": host,
        "port": port,
        "https": https,
        "request": request,
        "tabName": tab_name
    }
    res = _request("POST", "/repeater/send", json=payload)
    return json.dumps(res, ensure_ascii=False)

@mcp.tool()
def check_burp_status() -> str:
    """
    检查 Burp 插件是否在线。
    """
    res = _request("GET", "/health")
    return json.dumps(res, ensure_ascii=False)

if __name__ == "__main__":
    # 以 stdio transport 启动 MCP 服务
    mcp.run(transport="stdio")
