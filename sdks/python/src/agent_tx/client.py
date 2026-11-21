"""Stdlib JSON-RPC client for an ATP proxy."""

import json
import urllib.error
import urllib.request


class TransactionError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


class TransactionClient:
    def __init__(self, base_url):
        self._base = base_url[:-1] if base_url.endswith("/") else base_url

    def execute(self, tx_id, steps, reasoning=""):
        return self._rpc("tx/execute", {"txId": tx_id, "steps": steps, "reasoning": reasoning})

    def resume(self, tx_id, decision):
        return self._rpc("tx/resume", {"txId": tx_id, "decision": decision})

    def abort(self, tx_id):
        return self._rpc("tx/abort", {"txId": tx_id})

    def status(self, tx_id):
        return self._rpc("tx/status", {"txId": tx_id})

    def replay(self, tx_id, up_to_step=0):
        return self._rpc("tx/replay", {"txId": tx_id, "sandbox": True, "upToStep": up_to_step})

    def tools(self):
        return self._rpc("tools/list", {}).get("tools", [])

    def _rpc(self, method, params):
        body = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": method,
            "params": params,
            "_meta": {
                "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                "io.modelcontextprotocol/clientCapabilities": {},
            },
        }
        request = urllib.request.Request(
            self._base + "/mcp",
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        try:
            with urllib.request.urlopen(request) as response:
                payload = json.loads(response.read().decode())
        except urllib.error.URLError as ex:
            raise TransactionError(-31801, str(ex)) from ex
        if "error" in payload:
            error = payload["error"]
            raise TransactionError(error.get("code", -31801), error.get("message", ""))
        return payload.get("result", {})
