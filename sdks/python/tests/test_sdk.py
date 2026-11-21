import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading

import pytest

from agent_tx import TransactionClient, TransactionError, compensatable_tool


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode()
        request = json.loads(raw)
        if self.path == "/mcp-error":
            body = {"jsonrpc": "2.0", "id": 1, "error": {"message": "denied"}}
        elif self.path == "/mcp-empty":
            body = {"jsonrpc": "2.0", "id": 1}
        else:
            body = {"jsonrpc": "2.0", "id": 1, "result": {"state": "COMMITTED", "tools": [{"name": request["method"]}]}}
        encoded = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, fmt, *args):
        return


def serve(path_client):
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


def test_client_and_decorator():
    @compensatable_tool(
        "transfer_inventory",
        "Moves stock",
        {"type": "object"},
        "transfer_inventory",
        {"sku": "input.sku"},
        reversibility="COMPENSATABLE",
        idempotency_key_path="$.input.transfer_id",
        blast_radius_tier="HIGH",
        dry_run_supported=True,
        max_retries=5,
        on_compensation_failure="ESCALATE_HITL",
    )
    def transfer(sku):
        return sku

    assert transfer.mcp_tool["x-atp-transaction"]["idempotencyKeyPath"] == "$.input.transfer_id"
    assert transfer("sku-1") == "sku-1"

    @compensatable_tool("plain", "Plain", {"type": "object"}, "plain", {"id": "input.id"})
    def plain(value):
        return value

    assert plain.mcp_tool["_meta"]["org.agenttxprotocol/transaction"]["reversibility"] == "COMPENSATABLE"

    server = serve(None)
    try:
        base = "http://127.0.0.1:%s/" % server.server_address[1]
        client = TransactionClient(base)
        assert client.execute("t1", [{"tool": "reserve_credit", "arguments": {}}], "why")["state"] == "COMMITTED"
        assert client.resume("t1", "APPROVE")["state"] == "COMMITTED"
        assert client.abort("t1")["state"] == "COMMITTED"
        assert client.status("t1")["state"] == "COMMITTED"
        assert client.replay("t1", 1)["state"] == "COMMITTED"
        assert client.tools()[0]["name"] == "tools/list"
        bare = TransactionClient(base[:-1])
        assert bare.status("t1")["tools"][0]["name"] == "tx/status"
    finally:
        server.shutdown()

    error_server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)

    class ErrorHandler(Handler):
        def do_POST(self):
            self.path = "/mcp-error"
            super().do_POST()

    error_server.RequestHandlerClass = ErrorHandler
    thread = threading.Thread(target=error_server.serve_forever, daemon=True)
    thread.start()
    try:
        broken = TransactionClient("http://127.0.0.1:%s" % error_server.server_address[1])
        with pytest.raises(TransactionError) as caught:
            broken.status("t")
        assert caught.value.code == -31801
    finally:
        error_server.shutdown()

    class EmptyHandler(Handler):
        def do_POST(self):
            self.path = "/mcp-empty"
            super().do_POST()

    empty_server = ThreadingHTTPServer(("127.0.0.1", 0), EmptyHandler)
    threading.Thread(target=empty_server.serve_forever, daemon=True).start()
    try:
        empty = TransactionClient("http://127.0.0.1:%s" % empty_server.server_address[1])
        assert empty.status("t") == {}
    finally:
        empty_server.shutdown()

    offline = TransactionClient("http://127.0.0.1:1")
    with pytest.raises(TransactionError):
        offline.status("t")
