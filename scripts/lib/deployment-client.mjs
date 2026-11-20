import { readFile, writeFile, mkdir } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { performance } from "node:perf_hooks";
import { root } from "./project-paths.mjs";
export { root };
export const baseUrl = process.env.TF_DEPLOY_URL || "http://127.0.0.1:18081";
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(baseUrl))
  throw new Error("Local deployment URL required");
export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
export function assert(value, message) {
  if (!value) throw new Error(message);
}
export async function evidence(name, value, category = "verification/deployment") {
  await mkdir(new URL(`.local/${category}/`, root), { recursive: true });
  await writeFile(
    new URL(`.local/${category}/${name}.json`, root),
    JSON.stringify(value, null, 2),
  );
}
export async function client() {
  const secrets = JSON.parse(
    await readFile(new URL(".local/deploy-secrets.json", root)),
  );
  let cookies = new Map(),
    csrf;
  async function request(
    path,
    method = "GET",
    body,
    expected = 200,
    headers = {},
  ) {
    const begin = performance.now();
    const response = await fetch(baseUrl + "/api/v1" + path, {
      method,
      headers: {
        "Content-Type": "application/json",
        Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join("; "),
        ...(method !== "GET" && csrf ? { [csrf.headerName]: csrf.token } : {}),
        ...headers,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(20000),
    });
    for (const cookie of response.headers.getSetCookie()) {
      const pair = cookie.split(";")[0],
        at = pair.indexOf("=");
      cookies.set(pair.slice(0, at), pair.slice(at + 1));
    }
    const payload = await response.json().catch(() => ({}));
    assert(
      response.status === expected,
      `${method} ${path}: HTTP ${response.status}, expected ${expected}, code=${payload.error?.code}`,
    );
    return {
      data: payload.data,
      status: response.status,
      ms: performance.now() - begin,
      headers: response.headers,
    };
  }
  csrf = (await request("/auth/csrf")).data;
  await request("/auth/login", "POST", {
    username: "admin",
    password: secrets.AdminPassword,
  });
  csrf = (await request("/auth/csrf")).data;
  return {
    request,
    api: async (...parameters) => (await request(...parameters)).data,
    submit: async (
      project,
      sourceId,
      environmentId,
      sourceType = "CASE",
      key = randomUUID(),
      expected = 202,
    ) =>
      (
        await request(
          `/projects/${project}/runs`,
          "POST",
          { sourceType, sourceId, environmentId },
          expected,
          { "Idempotency-Key": key },
        )
      ).data,
    rerun: async (project, id) =>
      (
        await request(
          `/projects/${project}/runs/${id}/reruns`,
          "POST",
          {},
          202,
          { "Idempotency-Key": randomUUID() },
        )
      ).data,
    async wait(project, id, seconds = 90) {
      const begin = Date.now();
      while (Date.now() - begin < seconds * 1000) {
        const run = (await request(`/projects/${project}/runs/${id}`)).data;
        if (
          ["COMPLETED", "CANCELLED", "TIMED_OUT", "INTERRUPTED"].includes(
            run.state,
          )
        )
          return run;
        await sleep(200);
      }
      throw new Error(`Run ${id} did not reach terminal state`);
    },
  };
}
export async function fixture(c, name = "TestFlow · 合成部署验证") {
  const owner = (await c.api("/auth/me")).id;
  const project = (
    await c.api(
      "/projects",
      "POST",
      { name: name + " " + Date.now(), ownerId: owner },
      201,
    )
  ).id;
  const path = `/projects/${project}`;
  const environment = (
    await c.api(
      path + "/environments",
      "POST",
      {
        name: "容器合成目标",
        baseUrl: "http://demo-target:8088",
        variables: { marker: "testflow synthetic" },
      },
      201,
    )
  ).id;
  await c.api(
    path + "/target-policies",
    "POST",
    {
      scheme: "http",
      host: "demo-target",
      port: 8088,
      addressScope: "PRIVATE",
    },
    201,
  );
  async function addCase(name, target = "/ok", overrides = {}) {
    return (
      await c.api(
        path + "/cases",
        "POST",
        {
          name,
          configSchemaVersion: 1,
          config: {
            request: {
              method: "GET",
              path: target,
              query: [],
              headers: [],
              body: { type: "NONE" },
            },
            assertions: [{ id: "status", type: "STATUS_CODE", expected: 200 }],
            extractors: [],
            timeoutMs: 15000,
            ...overrides,
          },
        },
        201,
      )
    ).id;
  }
  const caseId = await addCase("真实请求 · 合成响应");
  return { project, environment, caseId, path, addCase };
}
export function metrics(samples) {
  const sorted = samples.map((x) => x.ms).sort((a, b) => a - b),
    n = sorted.length;
  const percentile = (p) => (n ? sorted[Math.ceil(p * n) - 1] : null);
  return {
    requests: n,
    success: samples.filter((x) => x.ok).length,
    successRate: n ? samples.filter((x) => x.ok).length / n : null,
    averageMs: n ? sorted.reduce((a, b) => a + b, 0) / n : null,
    p95Ms: percentile(0.95),
    p99Ms: percentile(0.99),
    maxMs: n ? sorted[n - 1] : null,
  };
}
