import httpx
import sys

def test():
    """
    验证与 Burp 插件 HTTP 接口的连接
    """
    print("正在测试与 Burp 插件的连接 (http://127.0.0.1:8181/health) ...")
    try:
        response = httpx.get("http://127.0.0.1:8181/health", timeout=2.0)
        print("✅ 连接成功！")
        print("响应内容:", response.json())
    except httpx.ConnectError:
        print("❌ 连接失败！请确认 Burp Suite 已启动，并且 Burp MCP 插件已成功加载（默认监听 8181 端口）。")
        sys.exit(1)
    except Exception as e:
        print("❌ 出现异常:", str(e))
        sys.exit(1)

if __name__ == "__main__":
    test()
