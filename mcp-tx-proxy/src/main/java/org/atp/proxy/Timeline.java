package org.atp.proxy;

/** Operations console served by the proxy. No build step. */
public final class Timeline {
    private Timeline() {
    }

    public static String html() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Reverta — transactional rollback</title>
                  <style>
                    :root {
                      --ink: #e7ecdf;
                      --muted: #a8b5a4;
                      --line: rgba(231, 236, 223, 0.14);
                      --bg: #101612;
                      --panel: #18211b;
                      --panel-2: #202b23;
                      --brass: #d7b56a;
                      --pine: #7dcea0;
                      --oxide: #e07a62;
                      --amber: #e2b15a;
                      --shadow: 0 24px 60px rgba(0, 0, 0, 0.35);
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      min-height: 100vh;
                      color: var(--ink);
                      background:
                        radial-gradient(1200px 500px at 10% -10%, rgba(215, 181, 106, 0.16), transparent 55%),
                        radial-gradient(900px 420px at 100% 0%, rgba(125, 206, 160, 0.12), transparent 50%),
                        var(--bg);
                      font-family: "Segoe UI", "Avenir Next", sans-serif;
                    }
                    header, main { width: min(1120px, calc(100% - 2rem)); margin: 0 auto; }
                    header { display: flex; justify-content: space-between; gap: 1rem; align-items: end; padding: 1.6rem 0 1rem; }
                    .mark { display: flex; gap: 0.8rem; align-items: center; }
                    .seal {
                      width: 2.6rem; height: 2.6rem; border-radius: 50%;
                      border: 1px solid var(--brass); display: grid; place-items: center;
                      color: var(--brass); font-family: Georgia, serif; font-size: 1.15rem;
                    }
                    h1 { font-family: Georgia, "Palatino Linotype", serif; font-weight: 500; font-size: 1.7rem; margin: 0; letter-spacing: -0.03em; }
                    .eyebrow { margin: 0; color: var(--brass); letter-spacing: 0.16em; text-transform: uppercase; font-size: 0.72rem; }
                    a { color: var(--brass); }
                    main { display: grid; grid-template-columns: 320px 1fr; gap: 1rem; padding-bottom: 2.5rem; }
                    .panel { background: linear-gradient(180deg, var(--panel), #141c17); border: 1px solid var(--line); border-radius: 18px; box-shadow: var(--shadow); }
                    .story { padding: 1.15rem 1.15rem 1.3rem; }
                    .story p, .hint { color: var(--muted); line-height: 1.5; }
                    ol { margin: 0; padding: 0; list-style: none; display: grid; gap: 0.7rem; }
                    li { display: grid; grid-template-columns: auto 1fr; gap: 0.7rem; align-items: start; }
                    .n { width: 1.6rem; height: 1.6rem; border-radius: 50%; display: grid; place-items: center; background: #2a382d; color: var(--brass); font-size: 0.8rem; }
                    strong { font-weight: 600; }
                    .actions { display: grid; gap: 0.55rem; margin-top: 1rem; }
                    button {
                      font: inherit; color: var(--ink); background: var(--panel-2);
                      border: 1px solid var(--line); border-radius: 999px; padding: 0.7rem 0.95rem; text-align: left; cursor: pointer;
                    }
                    button:hover { border-color: var(--brass); }
                    button:disabled { opacity: 0.55; cursor: progress; }
                    button.primary { background: #3d4f34; border-color: transparent; }
                    button.good { background: #1d3b2e; }
                    button.stop { background: #3c2420; }
                    section { padding: 1.15rem; }
                    .status { display: flex; justify-content: space-between; gap: 1rem; align-items: center; margin-bottom: 0.8rem; }
                    .pill { border-radius: 999px; padding: 0.28rem 0.7rem; font-size: 0.78rem; letter-spacing: 0.08em; text-transform: uppercase; background: #2a332c; }
                    .pill.ok { background: #1d3b2e; color: var(--pine); }
                    .pill.bad { background: #3c2420; color: var(--oxide); }
                    .pill.wait { background: #3a311c; color: var(--amber); }
                    h2 { font-family: Georgia, serif; font-weight: 500; font-size: 1.25rem; margin: 0 0 0.8rem; }
                    pre { margin: 0; white-space: pre-wrap; background: #0d120f; border-radius: 12px; padding: 0.9rem; color: #d5dece; min-height: 4.5rem; }
                    #chain { display: grid; gap: 0.45rem; margin-top: 0.9rem; max-height: 34rem; overflow: auto; }
                    .entry { display: grid; grid-template-columns: 3.2rem 1fr auto; gap: 0.7rem; align-items: center; padding: 0.55rem 0.7rem; border-radius: 12px; background: rgba(255,255,255,0.03); }
                    .seq { color: var(--brass); font-variant-numeric: tabular-nums; }
                    .meta { color: var(--muted); font-size: 0.82rem; }
                    .hash { font-family: ui-monospace, Consolas, monospace; font-size: 0.75rem; color: #8ea18d; }
                    #ledger { display: none; }
                    @media (max-width: 860px) { main { grid-template-columns: 1fr; } header { align-items: start; flex-direction: column; } }
                  </style>
                </head>
                <body>
                  <header>
                    <div class="mark">
                      <div class="seal" aria-hidden="true">R</div>
                      <div>
                        <p class="eyebrow">Reverta</p>
                        <h1>Transactional rollback</h1>
                      </div>
                    </div>
                    <p class="hint">Shadow, gate, commit, or compensate. The hash chain is the record.</p>
                  </header>
                  <main>
                    <aside class="panel story">
                      <p>A three-step agent plan writes a ledger branch and a lakehouse branch, then the carrier returns HTTP 500. The coordinator compensates in reverse and restores both branches.</p>
                      <ol>
                        <li><span class="n">1</span><span><strong>adjust_ledger</strong><br>Move 100 from account A to B on an isolated branch.</span></li>
                        <li><span class="n">2</span><span><strong>move_stock</strong><br>Add two units of SKU-1 on a catalog branch.</span></li>
                        <li><span class="n">3</span><span><strong>dispatch_carrier</strong><br>The carrier answers HTTP 500. Nothing is left behind.</span></li>
                      </ol>
                      <div class="actions">
                        <button id="rollback" class="primary" type="button">Run rollback demo</button>
                        <button id="wire" type="button">Park an irreversible wire</button>
                        <button id="approve" class="good" type="button">Approve parked wire</button>
                        <button id="reject" class="stop" type="button">Reject parked wire</button>
                      </div>
                    </aside>
                    <section class="panel">
                      <div class="status">
                        <h2>Outcome</h2>
                        <span id="pill" class="pill">Idle</span>
                      </div>
                      <pre id="outcome">No transaction yet.</pre>
                      <h2 style="margin-top:1.2rem">Hash chain</h2>
                      <div id="chain"><p class="hint">Empty ledger. Run a demo to seal the first entry.</p></div>
                      <pre id="ledger" class="hash">Empty ledger.</pre>
                    </section>
                  </main>
                  <script>
                    const outcome = document.getElementById('outcome');
                    const ledger = document.getElementById('ledger');
                    const chain = document.getElementById('chain');
                    const pill = document.getElementById('pill');
                    const buttons = [...document.querySelectorAll('button')];
                    let parked = null;
                    let seq = 0;
                    function paint(text, state) {
                      outcome.textContent = text;
                      pill.textContent = state || 'Note';
                      pill.className = 'pill';
                      if (state === 'COMMITTED' || state === 'ROLLED_BACK') pill.classList.add('ok');
                      else if (state === 'ESCALATED_HITL') pill.classList.add('wait');
                      else if (state) pill.classList.add('bad');
                    }
                    async function post(url, body) {
                      buttons.forEach(button => { button.disabled = true; });
                      try {
                        const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body || '{}' });
                        return response.json();
                      } finally {
                        buttons.forEach(button => { button.disabled = false; });
                      }
                    }
                    function render(entries) {
                      chain.replaceChildren();
                      if (!entries.length) {
                        const empty = document.createElement('p');
                        empty.className = 'hint';
                        empty.textContent = 'Empty ledger. Run a demo to seal the first entry.';
                        chain.appendChild(empty);
                        return;
                      }
                      entries.forEach(entry => {
                        const row = document.createElement('article');
                        row.className = 'entry';
                        const seqNode = document.createElement('div');
                        seqNode.className = 'seq';
                        seqNode.textContent = String(entry.seq).padStart(2, '0');
                        const body = document.createElement('div');
                        const title = document.createElement('div');
                        title.textContent = entry.txId + ' · ' + entry.stepId;
                        const meta = document.createElement('div');
                        meta.className = 'meta';
                        meta.textContent = entry.state + (entry.toolName ? ' · ' + entry.toolName : '');
                        body.append(title, meta);
                        const hash = document.createElement('div');
                        hash.className = 'hash';
                        hash.textContent = String(entry.entryHash || '').slice(0, 12);
                        row.append(seqNode, body, hash);
                        chain.appendChild(row);
                      });
                    }
                    async function refresh() {
                      const response = await fetch('/api/ledger');
                      const body = await response.json();
                      const entries = (body.result && body.result.entries) || [];
                      ledger.textContent = entries.length ? entries.map(entry =>
                        entry.seq + ' ' + entry.txId + ' ' + entry.stepId + ' ' + entry.state + ' ' + entry.entryHash).join('\\n') : 'Empty ledger.';
                      render(entries);
                    }
                    document.getElementById('rollback').onclick = async () => {
                      const body = await post('/api/demo/rollback');
                      paint(JSON.stringify(body, null, 2), body.state);
                      await refresh();
                    };
                    document.getElementById('wire').onclick = async () => {
                      seq += 1;
                      const body = await post('/api/demo/approve-setup', JSON.stringify({ txId: 'ui-wire-' + seq }));
                      parked = body.txId;
                      paint(JSON.stringify(body, null, 2), body.state);
                      await refresh();
                    };
                    async function decide(decision) {
                      if (!parked) {
                        paint('Park a wire first.', 'Waiting');
                        return;
                      }
                      const body = await post('/api/demo/decision', JSON.stringify({ txId: parked, decision: decision }));
                      paint(JSON.stringify(body, null, 2), body.state);
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
