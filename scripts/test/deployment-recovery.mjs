import {
  client,
  fixture,
  evidence,
  assert,
  sleep,
  root,
} from "../lib/deployment-client.mjs";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { appendFile } from "node:fs/promises";
const checks = [],
  details = {};
const pass = (name) => {
  checks.push(name);
  console.log("PASS: " + name);
};
async function docker(parameters, input = "") {
  return new Promise((resolve, reject) => {
    const child = spawn(
      "docker",
      [
        "compose",
        "-f",
        "compose.deploy.yml",
        "--profile",
        "demo",
        ...parameters,
      ],
      {
        cwd: fileURLToPath(root),
        windowsHide: true,
        stdio: ["pipe", "pipe", "pipe"],
      },
    );
    let output = "",
      errors = "";
    child.stdout.on("data", (x) => (output += x));
    child.stderr.on("data", (x) => (errors += x));
    child.on("error", reject);
    child.on("close", async (code) => {
      if (code === 0) resolve(output.trim());
      else {
        await appendFile(
          new URL(".local/verification/deployment/recovery-commands.log", root),
          `${new Date().toISOString()} ${parameters[0]} code=${code}\n${errors}\n`,
        );
        reject(
          new Error(
            `Compose ${parameters[0]} failed; code=${code}; see private recovery-commands.log`,
          ),
        );
      }
    });
    child.stdin.end(input);
  });
}
async function sql(query) {
  return docker(
    [
      "exec",
      "-T",
      "mysql",
      "sh",
      "-c",
      'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysql -uroot --batch --skip-column-names testflow',
    ],
    query + ";\n",
  );
}
async function until(check, seconds = 120) {
  const deadline = Date.now() + seconds * 1000;
  while (Date.now() < deadline) {
    try {
      if (await check()) return;
    } catch {}
    await sleep(1000);
  }
  throw new Error("Recovery condition did not become true");
}
async function targetStats() {
  const raw = await docker([
    "exec",
    "-T",
    "demo-target",
    "bash",
    "-c",
    "exec 3<>/dev/tcp/127.0.0.1/8088; printf 'GET /stats HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3; cat <&3",
  ]);
  return JSON.parse(raw.slice(raw.indexOf("\r\n\r\n") + 4));
}
const c = await client(),
  f = await fixture(c, "故障恢复 · 合成数据");
details.fixture = { ...f, addCase: undefined };
let kafkaStopped = false;
try {
  await docker(["stop", "--timeout", "20", "kafka"]);
  kafkaStopped = true;
  const ids = [];
  for (let i = 0; i < 12; i++)
    ids.push((await c.submit(f.project, f.caseId, f.environment)).id);
  for (const id of ids)
    assert(
      (await c.wait(f.project, id)).outcome === "PASSED",
      "Execution failed while Kafka unavailable",
    );
  const pending = Number(
    await sql(
      `SELECT COUNT(*) FROM tf_outbox_event WHERE project_id=${f.project} AND status<>'PUBLISHED'`,
    ),
  );
  assert(pending === 12, "Outbox durable backlog");
  await c.api(f.path + "/runs/" + ids[0] + "/report");
  details.kafka = { ids, pending };
  pass("Kafka unavailable: 12 real runs, report reads and durable backlog");
  await docker(["restart", "--timeout", "35", "api"]);
  await until(async () => {
    await docker(["exec", "-T", "api", "bash", "/app/health.sh"]);
    const me = await c.api("/auth/me");
    const history = await c.api(f.path + "/runs/" + ids[0]);
    return !!me.id && history.outcome === "PASSED";
  });
  assert(
    (await c.api(f.path + "/runs/" + ids[0])).outcome === "PASSED",
    "API offline-broker restart",
  );
  pass("API restarts with Kafka offline; Redis session and results remain");
  await docker(["start", "kafka"]);
  kafkaStopped = false;
  const recoveredAt = Date.now();
  await until(
    async () =>
      Number(
        await sql(
          `SELECT COUNT(*) FROM tf_quality_run_fact WHERE project_id=${f.project}`,
        ),
      ) === 12,
    180,
  );
  details.kafka.recoveryAfterStartMs = Date.now() - recoveredAt;
  const event = await sql(
    `SELECT CONCAT(run_id,'|',CAST(payload AS CHAR)) FROM tf_outbox_event WHERE run_id=${ids[0]}`,
  );
  await docker(
    [
      "exec",
      "-T",
      "kafka",
      "/opt/kafka/bin/kafka-console-producer.sh",
      "--bootstrap-server",
      "kafka:9092",
      "--topic",
      "testflow.run-finalized.v1",
      "--property",
      "parse.key=true",
      "--property",
      "key.separator=|",
    ],
    event + "\n" + event + "\n",
  );
  await sleep(4000);
  assert(
    Number(
      await sql(
        `SELECT COUNT(*) FROM tf_quality_run_fact WHERE project_id=${f.project}`,
      ),
    ) === 12,
    "Duplicate messages over-counted facts",
  );
  pass(
    "Kafka backlog recovered and duplicate delivery did not duplicate facts",
  );
  const mutation = await f.addCase("在途副作用故障探针", "/mutate", {
    timeoutMs: 60000,
    request: {
      method: "POST",
      path: "/mutate",
      query: [{ name: "ms", value: "30000" }],
      headers: [],
      body: { type: "NONE" },
    },
  });
  const before = (await targetStats()).mutations;
  const lost = await c.submit(f.project, mutation, f.environment);
  await until(async () => (await targetStats()).mutations === before + 1, 30);
  await docker(["kill", "-s", "SIGKILL", "worker"]);
  const interrupted = await c.wait(f.project, lost.id, 120);
  assert(
    interrupted.state === "INTERRUPTED" && interrupted.metrics.unknown === 1,
    "Lost in-flight request must be uncertain",
  );
  await docker(["start", "worker"]);
  await sleep(8000);
  assert(
    (await targetStats()).mutations === before + 1,
    "Worker replayed unknown external side effect",
  );
  details.worker = {
    runId: lost.id,
    state: interrupted.state,
    unknown: interrupted.metrics.unknown,
    mutationBefore: before,
    mutationAfter: before + 1,
  };
  pass("Worker SIGKILL: expired lease interrupted/unknown, no HTTP replay");
  const cancelled = await c.submit(f.project, mutation, f.environment);
  await until(async () => (await targetStats()).mutations === before + 2, 30);
  await c.api(f.path + "/runs/" + cancelled.id + "/cancel", "POST", {}, 202);
  const cancelledRun = await c.wait(f.project, cancelled.id);
  assert(
    cancelledRun.state === "CANCELLED" && cancelledRun.metrics.unknown === 1,
    "In-flight cancel uncertainty",
  );
  pass("Container execution cancellation retains unknown in-flight outcome");
  const snapshotBefore = await sql(
    `SELECT content_hmac,SHA2(encrypted_payload,256) FROM tf_run_snapshot WHERE run_id=${ids[0]}`,
  );
  const countsBefore = await sql(
    `SELECT (SELECT COUNT(*) FROM tf_test_run WHERE project_id=${f.project}),(SELECT COUNT(*) FROM tf_outbox_event WHERE project_id=${f.project}),(SELECT COUNT(*) FROM tf_run_item_result WHERE project_id=${f.project})`,
  );
  await until(
    async () =>
      Number(
        await sql(
          `SELECT COUNT(*) FROM tf_test_run r LEFT JOIN tf_quality_run_fact q ON q.run_id=r.id WHERE r.project_id=${f.project} AND q.run_id IS NULL`,
        ),
      ) === 0,
    120,
  );
  await docker(["stop", "--timeout", "35", "worker"]);
  const queued = await c.submit(f.project, f.caseId, f.environment);
  assert(
    (await c.api(f.path + "/runs/" + queued.id)).state === "QUEUED",
    "Worker stop did not leave a durable queued task",
  );
  await docker([
    "stop",
    "--timeout",
    "35",
    "nginx",
    "worker",
    "api",
    "kafka",
    "redis",
    "mysql",
  ]);
  await docker([
    "up",
    "-d",
    "--wait",
    "--wait-timeout",
    "240",
    "api",
    "worker",
    "nginx",
    "kafka",
    "demo-target",
  ]);
  assert(
    (await c.api("/auth/me")).username === "admin",
    "Redis session did not survive restart",
  );
  assert(
    (await c.api(f.path + "/runs/" + ids[0])).outcome === "PASSED",
    "History did not survive restart",
  );
  const resumed = await c.wait(f.project, queued.id);
  assert(
    resumed.outcome === "PASSED",
    "Queued task did not survive container restart",
  );
  assert(
    (await sql(
      `SELECT content_hmac,SHA2(encrypted_payload,256) FROM tf_run_snapshot WHERE run_id=${ids[0]}`,
    )) === snapshotBefore,
    "Snapshot changed during restart",
  );
  assert(
    (await sql(
      `SELECT (SELECT COUNT(*) FROM tf_test_run WHERE project_id=${f.project}),(SELECT COUNT(*) FROM tf_outbox_event WHERE project_id=${f.project}),(SELECT COUNT(*) FROM tf_run_item_result WHERE project_id=${f.project})`,
    )) ===
      countsBefore
        .split("\t")
        .map((value) => Number(value) + 1)
        .join("\t"),
    "Persistent row counts changed",
  );
  details.persistence = {
    countsBefore,
    queuedRunId: queued.id,
    queuedOutcome: resumed.outcome,
    snapshotUnchanged: true,
    sessionPreserved: true,
  };
  pass(
    "All containers restart: MySQL snapshots/results, Redis session and Kafka facts preserved",
  );
  const final = await c.wait(
    f.project,
    (await c.submit(f.project, f.caseId, f.environment)).id,
  );
  assert(final.outcome === "PASSED", "Execution unavailable after restart");
  await until(
    async () =>
      Number(
        await sql(
          `SELECT COUNT(*) FROM tf_quality_run_fact WHERE run_id=${final.id}`,
        ),
      ) === 1,
    90,
  );
  pass("Post-restart real execution and quality projection remain available");
  await evidence("recovery", { at: new Date().toISOString(), checks, details });
} catch (error) {
  await evidence("recovery-failed", {
    at: new Date().toISOString(),
    checks,
    details,
    error: error.message,
  });
  throw error;
} finally {
  try {
    if (kafkaStopped) await docker(["start", "kafka"]);
    await docker(["start", "worker"]);
  } catch {
    console.error(
      "Recovery cleanup incomplete; inspect private recovery-commands.log and service health.",
    );
  }
}
