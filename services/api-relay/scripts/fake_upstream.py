"""Loopback-only, charge-free OpenAI HTTP fixture. No credentials or dependencies."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import time
import argparse


class Fake(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Never print headers or request content.

    def do_POST(self):
        if self.path not in ("/v1/chat/completions", "/v1/responses"):
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length > 32768:
            self.send_error(413)
            return
        try:
            request = json.loads(self.rfile.read(length))
            responses = self.path == "/v1/responses"
            response = {
                "id": "resp_mock", "object": "response", "created_at": 0, "status": "completed",
                "model": request["model"],
                "output": [{"id": "msg_mock", "type": "message", "role": "assistant", "status": "completed", "content": [{"type": "output_text", "text": "Hello from the local fake upstream.", "annotations": []}]}],
                "usage": {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15, "input_tokens_details": {"cached_tokens": 0}}
            }
            chat = {
                "id": "chatcmpl-mock", "object": "chat.completion", "created": 0,
                "model": request["model"],
                "choices": [{"index": 0, "message": {"role": "assistant", "content": "Hello from the local fake upstream."}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            }
            if request.get("stream"):
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Connection", "close")
                self.end_headers()
                if responses:
                    events = [
                        'event: response.output_text.delta\ndata: ' + json.dumps({"type": "response.output_text.delta", "delta": "Hello from the local fake upstream."}) + '\n\n',
                        'event: response.completed\ndata: ' + json.dumps({"type": "response.completed", "response": response}) + '\n\n'
                    ]
                else:
                    events = [
                        'data: ' + json.dumps({"id": "chatcmpl-mock", "object": "chat.completion.chunk", "model": request["model"], "choices": [{"index": 0, "delta": {"role": "assistant", "content": "Hello from the local fake upstream."}, "finish_reason": None}]}) + '\n\n',
                        'data: ' + json.dumps({"id": "chatcmpl-mock", "object": "chat.completion.chunk", "model": request["model"], "choices": [], "usage": chat["usage"]}) + '\n\n',
                        'data: [DONE]\n\n'
                    ]
                for event in events:
                    self.wfile.write(event.encode())
                    self.wfile.flush()
                    time.sleep(0.1)
                self.close_connection = True
                return
            body = json.dumps(response if responses else chat).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self.send_error(400)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=19092)
    port = parser.parse_args().port
    print(f"Charge-free fake upstream: http://127.0.0.1:{port}/v1", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Fake).serve_forever()
