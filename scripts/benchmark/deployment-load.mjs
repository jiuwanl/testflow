import {
  client,
  fixture,
  evidence,
  metrics,
  assert,
  sleep,
} from "../lib/deployment-client.mjs";
import { readFile } from "node:fs/promises";
import { performance } from "node:perf_hooks";
const mode = process.argv[2] || "performance";
assert(["performance", "soak"].includes(mode), "Expected performance or soak");
const c = await client(),
  f = await fixture(
    c,
    mode === "soak" ? "短时稳定性 · 合成数据" : "性能测试 · 合成数据",
  );
const result = {
  at: new Date().toISOString(),
  mode,
  fixture: { ...f, addCase: undefined },
  samples: [],
  batches: [],
};
if (mode === "performance") {
  const run = await c.wait(
    f.project,
    (await c.submit(f.project, f.caseId, f.environment)).id,
  );
  const paths = [
    `${f.path}/runs?size=20`,
    `${f.path}/runs/${run.id}/report`,
    `${f.path}/quality`,
  ];
  // Warm-up is deliberately excluded from measured samples.
  for (const path of paths) for (let i = 0; i < 10; i++) await c.api(path);
  for (const path of paths)
    for (const concurrency of [1, 4, 8, 16]) {
      const samples = [],
        begin = performance.now();
      let next = 0;
      await Promise.all(
        Array.from({ length: concurrency }, async () => {
          while (next++ < 200) {
            const start = performance.now();
            let ok = false;
            try {
              await c.api(path);
              ok = true;
            } catch {}
            samples.push({ ms: performance.now() - start, ok });
          }
        }),
      );
      const batch = {
        path,
        concurrency,
        ...metrics(samples),
        elapsedMs: performance.now() - begin,
      };
      result.batches.push(batch);
      result.samples.push({ path, concurrency, samples });
      console.log(JSON.stringify(batch));
    }
  const samples = [],
    begin = performance.now();
  let next = 0;
  await Promise.all(
    Array.from({ length: 4 }, async () => {
      while (next++ < 40) {
        const start = performance.now();
        let ok = false;
        try {
          const run = await c.wait(
            f.project,
            (await c.submit(f.project, f.caseId, f.environment)).id,
          );
          ok = run.state === "COMPLETED" && run.outcome === "PASSED";
        } catch {}
        samples.push({ ms: performance.now() - start, ok });
      }
    }),
  );
  result.endToEnd = {
    concurrency: 4,
    ...metrics(samples),
    elapsedMs: performance.now() - begin,
    samples,
  };
  console.log("Real Worker end-to-end " + JSON.stringify(result.endToEnd));
} else {
  const seconds = Number(process.env.TF_SOAK_SECONDS || 1800);
  assert(
    Number.isInteger(seconds) && seconds >= 60 && seconds <= 7200,
    "Soak duration 60..7200 seconds",
  );
  result.requestedSeconds = seconds;
  const started = Date.now(),
    samples = [],
    executions = [];
  while (Date.now() - started < seconds * 1000) {
    const start = performance.now();
    let ok = false;
    try {
      await c.api(f.path + "/runs?size=20");
      await c.api(f.path + "/quality");
      const run = await c.wait(
        f.project,
        (await c.submit(f.project, f.caseId, f.environment)).id,
      );
      ok = run.state === "COMPLETED" && run.outcome === "PASSED";
      executions.push({ id: run.id, state: run.state, outcome: run.outcome });
    } catch (error) {
      executions.push({ error: error.message });
    }
    samples.push({ ms: performance.now() - start, ok });
    if (samples.length % 10 === 0)
      await evidence("soak-progress", {
        ...result,
        elapsedSeconds: (Date.now() - started) / 1000,
        summary: metrics(samples),
        executions,
      });
    await sleep(4000);
  }
  result.elapsedSeconds = (Date.now() - started) / 1000;
  result.summary = metrics(samples);
  result.samples = samples;
  result.executions = executions;
}
result.finishedAt = new Date().toISOString();
await evidence(mode, result, "benchmarks");
const successes =
  result.batches.every((x) => x.requests === x.success) &&
  (!result.endToEnd || result.endToEnd.requests === result.endToEnd.success) &&
  (!result.summary || result.summary.requests === result.summary.success);
assert(
  successes,
  "Some measured requests/executions failed; evidence preserved",
);
