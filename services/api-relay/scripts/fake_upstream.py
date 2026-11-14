"""Loopback-only, charge-free OpenAI HTTP fixture. No credentials or dependencies."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json


class Fake(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Never print headers or request content.

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length > 32768:
            self.send_error(413)
            return
        try:
            request = json.loads(self.rfile.read(length))
            body = json.dumps({
                "id": "chatcmpl-mock", "object": "chat.completion", "created": 0,
                "model": request["model"],
                "choices": [{"index": 0, "message": {"role": "assistant", "content": "Hello from the local fake upstream."}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except Exception:
            self.send_error(400)


if __name__ == "__main__":
    print("Charge-free fake upstream: http://127.0.0.1:19092/v1", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 19092), Fake).serve_forever()
