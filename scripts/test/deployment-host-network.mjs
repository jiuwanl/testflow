import { client, fixture, evidence, assert } from "../lib/deployment-client.mjs";
const c = await client(),
  f = await fixture(c, "宿主机网络 · 合成验证");
const environment = (
  await c.api(
    f.path + "/environments",
    "POST",
    {
      name: "Windows 宿主机合成目标",
      baseUrl: "http://host.docker.internal:18089",
      variables: {},
    },
    201,
  )
).id;
await c.api(
  f.path + "/target-policies",
  "POST",
  {
    scheme: "http",
    host: "host.docker.internal",
    port: 18089,
    addressScope: "PRIVATE",
  },
  201,
);
const run = await c.wait(
  f.project,
  (await c.submit(f.project, f.caseId, environment)).id,
);
assert(
  run.outcome === "PASSED",
  "Container Worker could not reach explicitly authorized Windows fixture",
);
const controlEnvironment = (
  await c.api(
    f.path + "/environments",
    "POST",
    {
      name: "禁止访问平台入口",
      baseUrl: "http://host.docker.internal:18081",
      variables: {},
    },
    201,
  )
).id;
await c.api(
  f.path + "/target-policies",
  "POST",
  {
    scheme: "http",
    host: "host.docker.internal",
    port: 18081,
    addressScope: "PRIVATE",
  },
  201,
);
const blocked = await c.wait(
  f.project,
  (await c.submit(f.project, f.caseId, controlEnvironment)).id,
);
assert(blocked.metrics.errors === 1, "Published control port was not blocked");
await evidence("host-network", {
  at: new Date().toISOString(),
  project: f.project,
  hostRun: run.id,
  hostOutcome: run.outcome,
  controlRun: blocked.id,
  controlErrors: blocked.metrics.errors,
});
console.log(
  "PASS: container Worker to Windows host.docker.internal:18089; platform port 18081 blocked",
);
