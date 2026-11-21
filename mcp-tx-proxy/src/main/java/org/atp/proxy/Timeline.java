package org.atp.proxy;

/** Single-page timeline served by the proxy. No build step. */
public final class Timeline {
    private Timeline() {
    }

    public static String html() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <title>Agent Transaction Protocol</title>
                  <style>
                    body { font-family: Georgia, serif; margin: 2rem auto; max-width: 52rem; color: #1c1917; background: #faf7f2; }
                    button { font: inherit; margin-right: .5rem; padding: .4rem .8rem; }
                    pre { background: #fff; border: 1px solid #d6d3d1; padding: 1rem; overflow: auto; }
                    .hash { font-family: ui-monospace, monospace; font-size: .85rem; }
                  </style>
                </head>
                <body>
                  <h1>Transactional rollback</h1>
                  <p>A three-step agent plan writes a ledger branch and a lakehouse branch, then the carrier returns HTTP 500. The coordinator compensates in reverse and restores both branches.</p>
                  <button id="rollback" type="button">Run rollback demo</button>
                  <button id="wire" type="button">Park an irreversible wire</button>
                  <button id="approve" type="button">Approve parked wire</button>
                  <button id="reject" type="button">Reject parked wire</button>
                  <h2>Outcome</h2>
                  <pre id="outcome">No transaction yet.</pre>
                  <h2>Hash chain</h2>
                  <pre id="ledger" class="hash">Empty ledger.</pre>
                  <script>
                    const outcome = document.getElementById('outcome');
                    const ledger = document.getElementById('ledger');
                    let parked = null;
                    let seq = 0;
                    async function post(url, body) {
                      const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body || '{}' });
                      return response.json();
                    }
                    async function refresh() {
                      const response = await fetch('/api/ledger');
                      const body = await response.json();
                      const entries = (body.result && body.result.entries) || [];
                      ledger.textContent = entries.length ? entries.map(entry =>
                        entry.seq + ' ' + entry.txId + ' ' + entry.stepId + ' ' + entry.state + ' ' + entry.entryHash).join('\\n') : 'Empty ledger.';
                    }
                    document.getElementById('rollback').onclick = async () => {
                      outcome.textContent = JSON.stringify(await post('/api/demo/rollback'), null, 2);
                      await refresh();
                    };
                    document.getElementById('wire').onclick = async () => {
                      seq += 1;
                      const body = await post('/api/demo/approve-setup', JSON.stringify({ txId: 'ui-wire-' + seq }));
                      parked = body.txId;
                      outcome.textContent = JSON.stringify(body, null, 2);
                      await refresh();
                    };
                    async function decide(decision) {
                      if (!parked) {
                        outcome.textContent = 'Park a wire first.';
                        return;
                      }
                      const body = await post('/api/demo/decision', JSON.stringify({ txId: parked, decision: decision }));
                      outcome.textContent = JSON.stringify(body, null, 2);
                      parked = null;
                      await refresh();
                    }
                    document.getElementById('approve').onclick = () => decide('APPROVE');
                    document.getElementById('reject').onclick = () => decide('REJECT');
                    refresh();
                  </script>
                </body>
                </html>
                """;
    }
}
