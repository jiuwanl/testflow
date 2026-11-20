import {
  client,
  fixture,
  evidence,
  assert,
  baseUrl,
} from "../lib/deployment-client.mjs";
import { randomUUID } from "node:crypto";
const c = await client(),
  f = await fixture(c),
  checks = [];
const pass = (name) => {
  checks.push(name);
  console.log("PASS: " + name);
};
const headers = (await c.request("/auth/me")).headers;
assert(
  headers.get("x-content-type-options") === "nosniff",
  "Proxy security header",
);
const me = await c.api("/auth/me");
assert(me.username === "admin", "Session through Nginx");
pass("Nginx login/Redis session");
for (const path of ["/projects/deep/link", "/actuator/health"]) {
  const response = await fetch(baseUrl + path);
  assert(
    response.status === (path.startsWith("/actuator") ? 404 : 200),
    "SPA/health boundary",
  );
}
pass("SPA fallback and blocked actuator");
const tooLarge = await fetch(baseUrl + "/api/v1/projects", {
  method: "POST",
  body: "x".repeat(1048577),
});
assert(tooLarge.status === 413, "Nginx request body bound");
pass("Nginx 1 MiB request limit");
const key = randomUUID(),
  first = await c.submit(f.project, f.caseId, f.environment, "CASE", key);
const same = await c.submit(
  f.project,
  f.caseId,
  f.environment,
  "CASE",
  key,
  200,
);
assert(first.id === same.id, "Idempotent submit");
const run = await c.wait(f.project, first.id);
assert(
  run.state === "COMPLETED" && run.outcome === "PASSED",
  "Worker real HTTP",
);
pass("Database claim, safe HTTP, result and idempotence");
const rerun = await c.rerun(f.project, first.id);
assert(
  (await c.wait(f.project, rerun.id)).outcome === "PASSED",
  "Snapshot rerun",
);
pass("Original snapshot rerun");
const failing = await f.addCase("断言失败", "/failed");
const plan = (
  await c.api(
    f.path + "/plans",
    "POST",
    {
      name: "有序失败停止",
      executionMode: "ORDERED",
      failFast: true,
      steps: [failing, f.caseId].map((caseId) => ({ caseId, enabled: true })),
    },
    201,
  )
).id;
const ordered = await c.wait(
  f.project,
  (await c.submit(f.project, plan, f.environment, "PLAN")).id,
);
assert(
  ordered.metrics.failed === 1 && ordered.metrics.skipped === 1,
  "ORDERED fail fast",
);
pass("ORDERED failure stop");
const parallel = (
  await c.api(
    f.path + "/plans",
    "POST",
    {
      name: "独立并发四",
      executionMode: "INDEPENDENT",
      maxConcurrency: 4,
      failFast: false,
      steps: [f.caseId, f.caseId, f.caseId, f.caseId].map((caseId) => ({
        caseId,
        enabled: true,
      })),
    },
    201,
  )
).id;
const concurrent = await c.wait(
  f.project,
  (await c.submit(f.project, parallel, f.environment, "PLAN")).id,
);
assert(
  concurrent.metrics.passed === 4 && concurrent.outcome === "PASSED",
  "INDEPENDENT four results",
);
pass("INDEPENDENT concurrency four");
const forwarded = (
  await c.request("/auth/me", "GET", undefined, 200, {
    "X-Forwarded-For": "203.0.113.77",
    Forwarded: "for=203.0.113.77;proto=https",
    "X-Forwarded-Proto": "https",
  })
).data;
assert(forwarded.id === me.id, "Proxy headers preserve authenticated session");
pass("Forged forwarding headers do not change session");
const history = await c.api(f.path + "/runs?state=COMPLETED&size=100");
assert(history.total >= 4, "History filter");
pass("Filtered history and report queries");
const unknownAccount = "proxy-" + randomUUID().slice(0, 12);
for (let n = 0; n < 9; n++) {
  await c.request(
    "/auth/login",
    "POST",
    { username: unknownAccount, password: "synthetic-wrong-password" },
    n < 8 ? 401 : 429,
    { "X-Forwarded-For": `203.0.113.${n + 1}`, "X-Forwarded-Proto": "https" },
  );
}
pass("Forged proxy addresses cannot bypass account login throttling");
await evidence("smoke", {
  at: new Date().toISOString(),
  fixture: { ...f, addCase: undefined, parallel },
  checks,
  runs: [first.id, rerun.id, ordered.id, concurrent.id],
});
console.log(`${checks.length} deployment functional checks passed`);
