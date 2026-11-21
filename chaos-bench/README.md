# Chaos bench

The fault injector lives in `org.atp.chaos.ChaosHarness`. It runs one plan under HTTP 500, HTTP 429, a timeout before the handler, a timeout after the handler, and a missing-argument hallucination. For each fault it records recovery, whether a side effect was left behind, and the time to a terminal state. The same plan is also scored by a simulated executor that does not compensate, so the CSV can compare the two.

Generate the file:

```powershell
java -jar mcp-tx-proxy\target\tx-engine.jar chaos --out chaos-bench\out\results.csv
```

`chaos-bench/out/` is gitignored. The columns are `mode,fault,step,recovered,orphaned,mttr_nanos,final_state`.
