"""Advertises a Python function as a compensatable MCP tool."""


def compensatable_tool(name, description, input_schema, compensation_tool, argument_mapping, **extra):
    transaction = {
        "reversibility": extra.get("reversibility", "COMPENSATABLE"),
        "blastRadiusTier": extra.get("blast_radius_tier", "MEDIUM"),
        "dryRunSupported": extra.get("dry_run_supported", True),
        "compensation": {
            "tool": compensation_tool,
            "argumentMapping": argument_mapping,
            "maxRetries": extra.get("max_retries", 5),
            "onCompensationFailure": extra.get("on_compensation_failure", "ESCALATE_HITL"),
        },
    }
    if "idempotency_key_path" in extra:
        transaction["idempotencyKeyPath"] = extra["idempotency_key_path"]

    def wrap(function):
        tool = {
            "name": name,
            "description": description,
            "inputSchema": input_schema,
            "_meta": {"org.agenttxprotocol/transaction": transaction},
            "x-atp-transaction": transaction,
        }
        function.mcp_tool = tool
        return function

    return wrap
