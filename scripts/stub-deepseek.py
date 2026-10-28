# DeepSeek /chat/completions 的本地桩，仅用于验证 Agent 循环，不接真模型。
# 用法：python scripts/stub-deepseek.py 9099
# 行为：第一次调用返回一个 tool_call（fn_overdue_days），收到 tool 结果后返回最终回答。

import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    server_version = "stub-deepseek/1.0"

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        body = json.loads(raw.decode("utf-8") or "{}")
        messages = body.get("messages", [])
        last = messages[-1] if messages else {}

        if last.get("role") == "tool":
            content = str(last.get("content", ""))[:300]
            message = {
                "role": "assistant",
                "content": "桩服务回答：根据工具返回 " + content,
            }
            finish = "stop"
        else:
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [{
                    "id": "call_stub_1",
                    "type": "function",
                    "function": {
                        "name": "fn_overdue_days",
                        "arguments": json.dumps({"equipmentId": "E-1001"}),
                    },
                }],
            }
            finish = "tool_calls"

        response = {
            "id": "chatcmpl-stub",
            "object": "chat.completion",
            "model": body.get("model", "stub"),
            "choices": [{"index": 0, "message": message, "finish_reason": finish}],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
        }
        data = json.dumps(response, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        print("stub:", fmt % args, flush=True)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9099
    print(f"stub deepseek listening on 127.0.0.1:{port}", flush=True)
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
