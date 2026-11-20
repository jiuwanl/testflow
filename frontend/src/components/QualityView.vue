<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from "vue";
import { ElMessage } from "element-plus";
import { api, notifyError } from "../api";
import { percentage, time } from "../runPresentation";
import type { Page, Summary } from "../types";
const props = defineProps<{ projectId: string; writable: boolean }>();
const emit = defineEmits<{ run: [id: string] }>();
interface Metrics {
  runs: number;
  total: number;
  passed: number;
  failed: number;
  errors: number;
  skipped: number;
  cancelled: number;
  unknown: number;
  coverage: number | null;
  passRate: number | null;
  failureRate: number | null;
  errorRate: number | null;
  averageDurationMs: number | null;
}
interface Quality {
  status: {
    state: string;
    enabled: boolean;
    asOf: string;
    updatedAt: string | null;
    terminalRuns: number;
    projectedRuns: number;
    missingRuns: number;
    pendingEvents: number;
    incompleteRuns: number;
    oldestMissingAt: string | null;
  };
  metrics: Metrics;
  days: { date: string; metrics: Metrics }[];
  recent: {
    runId: string;
    sourceName: string;
    state: string;
    outcome: string;
    finishedAt: string;
  }[];
}
interface Failure {
  id: string;
  errorCode: string;
  status: string;
  replayCount: number;
  lastSeenAt: string;
  eventId?: string;
}
const base = `/projects/${props.projectId}`;
const data = ref<Quality>(),
  failures = ref<Failure[]>([]),
  sources = ref<Summary[]>([]),
  environments = ref<Summary[]>([]);
const sourceType = ref("PLAN"),
  sourceId = ref(""),
  environmentId = ref(""),
  includeIncomplete = ref(false),
  stale = ref(false),
  loading = ref(false),
  repairing = ref(false);
const now = new Date(),
  first = new Date(now.getTime() - 29 * 86400000);
const range = ref<string[]>([
  first.toISOString().slice(0, 10),
  now.toISOString().slice(0, 10),
]);
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false,
  version = 0;
async function sourceOptions() {
  sourceId.value = "";
  try {
    sources.value = (
      await api<Page<Summary>>(
        `${base}/${sourceType.value === "PLAN" ? "plans" : "cases"}?size=100`,
      )
    ).items;
  } catch (error) {
    notifyError(error);
  }
}
function query() {
  const params = new URLSearchParams({
    sourceType: sourceType.value,
    includeIncomplete: String(includeIncomplete.value),
  });
  if (sourceId.value) params.set("sourceId", sourceId.value);
  if (environmentId.value) params.set("environmentId", environmentId.value);
  if (range.value?.length === 2) {
    params.set("finishedFrom", range.value[0] + "T00:00:00Z");
    params.set(
      "finishedTo",
      new Date(
        new Date(range.value[1] + "T00:00:00Z").getTime() + 86400000,
      ).toISOString(),
    );
  }
  return params.toString();
}
async function load(quiet = false) {
  if (disposed) return;
  if (timer) clearTimeout(timer);
  const ticket = ++version;
  if (!quiet) loading.value = true;
  try {
    const [quality, errors] = await Promise.all([
      api<Quality>(`${base}/quality?${query()}`),
      api<Page<Failure>>(`${base}/quality/failures?size=20`),
    ]);
    if (!disposed && ticket === version) {
      stale.value = false;
      data.value = quality;
      failures.value = errors.items;
    }
  } catch (error) {
    if (!disposed && ticket === version) stale.value = true;
    if (!quiet) notifyError(error);
  } finally {
    if (!disposed && ticket === version) {
      loading.value = false;
      timer = setTimeout(() => void load(true), 5000);
    }
  }
}
async function repair() {
  repairing.value = true;
  try {
    const result = await api<{ createdEvents: number; requeuedEvents: number }>(
      `${base}/quality/reconcile`,
      "POST",
      { limit: 50 },
    );
    ElMessage.success(
      `已补建 ${result.createdEvents} 条事件，重新排队 ${result.requeuedEvents} 条。消息恢复后自动补齐。`,
    );
    await load(true);
  } catch (error) {
    notifyError(error);
  } finally {
    repairing.value = false;
  }
}
async function replay(id: string) {
  repairing.value = true;
  try {
    await api(`${base}/quality/failures/${id}/replay`, "POST", {});
    ElMessage.success("已请求重放可信来源事件，稍后刷新结果");
    await load(true);
  } catch (error) {
    notifyError(error);
  } finally {
    repairing.value = false;
  }
}
onMounted(async () => {
  await sourceOptions();
  try {
    environments.value = (
      await api<Page<Summary>>(`${base}/environments?size=100`)
    ).items;
  } catch (error) {
    notifyError(error);
  }
  await load();
});
onBeforeUnmount(() => {
  disposed = true;
  version++;
  if (timer) clearTimeout(timer);
});
</script>
<template>
  <section class="quality-workspace" v-loading="loading">
    <div class="quality-heading">
      <div>
        <span class="eyebrow">QUALITY · OVER TIME</span>
        <h2>让回归结果，沉淀为质量线索。</h2>
        <p class="muted">按运行唯一记录，用已知事实观察变化。</p>
      </div>
      <el-button @click="load()">刷新统计</el-button>
    </div>
    <div class="quality-filters">
      <el-select
        v-model="sourceType"
        aria-label="质量来源类型"
        @change="sourceOptions"
        ><el-option label="测试计划" value="PLAN" /><el-option
          label="单用例"
          value="CASE"
      /></el-select>
      <el-select
        v-model="sourceId"
        clearable
        filterable
        placeholder="全部来源"
        aria-label="质量来源"
        ><el-option
          v-for="source in sources"
          :key="source.id"
          :value="source.id"
          :label="source.name"
      /></el-select>
      <el-select
        v-model="environmentId"
        clearable
        placeholder="全部环境"
        aria-label="质量环境"
        ><el-option
          v-for="env in environments"
          :key="env.id"
          :value="env.id"
          :label="env.name"
      /></el-select>
      <el-date-picker
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        aria-label="质量日期范围"
      />
      <el-checkbox v-model="includeIncomplete">纳入取消、超时和中断</el-checkbox
      ><el-button type="primary" @click="load()">查询趋势</el-button>
    </div>
    <p class="field-hint">
      按完成日期（UTC）统计，最多 90
      天。默认只汇总正常结束的运行，失败停止仍计入；比率按步骤总数加权。
    </p>
    <template v-if="data">
      <div
        class="quality-freshness"
        :class="{ 'quality-lagging': stale || data.status.missingRuns > 0 }"
      >
        <div>
          <strong>{{
            stale
              ? "暂时无法刷新统计"
              : data.status.state === "DISABLED"
                ? "消息统计未启用"
                : data.status.missingRuns > 0
                  ? "统计尚未补齐"
                  : data.status.terminalRuns === 0
                    ? "所选范围暂无终态运行"
                    : "所选终态已投影"
          }}</strong>
          <p v-if="stale">
            当前显示上次成功查询的结果，请参考查询时点，恢复连接后自动刷新。
          </p>
          <p>
            {{ data.status.projectedRuns }} /
            {{ data.status.terminalRuns }} 次运行已统计，尚缺
            {{ data.status.missingRuns }} 次；其中
            {{ data.status.incompleteRuns }} 次为不完整运行。
          </p>
          <p v-if="data.status.missingRuns > 0">
            当前指标仅覆盖已投影记录。未补齐不代表零失败，原始报告仍可查询。
          </p>
          <small
            >最近投影：{{ time(data.status.updatedAt) }} · 查询时点：{{
              time(data.status.asOf)
            }}
            · 每 5 秒刷新</small
          >
        </div>
        <el-button v-if="writable" :loading="repairing" @click="repair"
          >补齐本项目统计</el-button
        >
      </div>
      <div class="quality-metrics">
        <div>
          <span>已知步骤通过率</span
          ><strong>{{ percentage(data.metrics.passRate) }}</strong
          ><small
            >{{ data.metrics.passed }} 个通过 /
            {{
              data.metrics.passed + data.metrics.failed + data.metrics.errors
            }}
            个已知结果</small
          >
        </div>
        <div>
          <span>已知结果覆盖率</span
          ><strong>{{ percentage(data.metrics.coverage) }}</strong
          ><small
            >{{ data.metrics.runs }} 次运行 ·
            {{ data.metrics.total }} 个步骤</small
          >
        </div>
        <div>
          <span>断言失败 / 执行错误</span
          ><strong
            >{{ data.metrics.failed }}
            <em>/ {{ data.metrics.errors }}</em></strong
          ><small
            >{{ percentage(data.metrics.failureRate) }} /
            {{ percentage(data.metrics.errorRate) }}</small
          >
        </div>
        <div>
          <span>跳过 / 取消 / 未知</span
          ><strong
            >{{ data.metrics.skipped }}
            <em
              >/ {{ data.metrics.cancelled }} / {{ data.metrics.unknown }}</em
            ></strong
          ><small>均不计为通过</small>
        </div>
      </div>
      <div class="quality-panel">
        <div class="quality-heading">
          <h3>每日已知结果通过率</h3>
          <span class="muted">UTC · 非性能基准</span>
        </div>
        <el-empty
          v-if="!data.days.length"
          :description="
            data.status.missingRuns > 0
              ? '等待消息补齐，暂无可展示的质量事实'
              : '所选范围暂无质量事实'
          "
        />
        <div v-for="day in data.days" :key="day.date" class="quality-day">
          <span>{{ day.date }}</span>
          <div class="quality-track">
            <div :style="{ width: (day.metrics.passRate ?? 0) + '%' }"></div>
          </div>
          <strong>{{ percentage(day.metrics.passRate) }}</strong
          ><small
            >{{ day.metrics.runs }} 次 · 覆盖
            {{ percentage(day.metrics.coverage) }}</small
          >
        </div>
      </div>
      <div class="quality-panel">
        <h3>追溯原始报告</h3>
        <p class="field-hint">
          所选范围最近 20 条质量事实；缺失记录可在“执行与报告”查询。
        </p>
        <el-table :data="data.recent" empty-text="暂无已投影报告"
          ><el-table-column
            prop="sourceName"
            label="执行来源"
          /><el-table-column label="结果" width="130"
            ><template #default="{ row }">{{
              row.outcome === "PASSED"
                ? "通过"
                : row.outcome === "FAILED"
                  ? "未通过"
                  : "不完整"
            }}</template></el-table-column
          ><el-table-column label="完成时间" min-width="170"
            ><template #default="{ row }">{{
              time(row.finishedAt)
            }}</template></el-table-column
          ><el-table-column width="110"
            ><template #default="{ row }"
              ><el-button link type="primary" @click="emit('run', row.runId)"
                >查看报告 ↗</el-button
              ></template
            ></el-table-column
          ></el-table
        >
      </div>
      <div class="quality-panel">
        <h3>消费失败与重放</h3>
        <p class="field-hint">
          显示本项目最近 20 条失败诊断。重放只重新投递可信来源摘要，不会重新执行
          HTTP。
        </p>
        <el-table :data="failures" empty-text="暂无消费失败记录"
          ><el-table-column
            prop="errorCode"
            label="安全错误码"
            min-width="200"
          /><el-table-column label="状态" width="150"
            ><template #default="{ row }">{{
              row.status === "RESOLVED"
                ? "已恢复"
                : row.status === "REPLAY_REQUESTED"
                  ? "等待重放"
                  : "需要处理"
            }}</template></el-table-column
          ><el-table-column
            prop="replayCount"
            label="重放次数"
            width="110"
          /><el-table-column v-if="writable" width="130"
            ><template #default="{ row }"
              ><el-button
                link
                type="primary"
                :disabled="repairing || row.status === 'RESOLVED'"
                @click="replay(row.id)"
                >重放事件</el-button
              ></template
            ></el-table-column
          ></el-table
        >
      </div>
    </template>
  </section>
</template>
<style scoped>
.quality-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-width: 0;
  gap: 22px;
}
.quality-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}
.quality-heading h2 {
  font-weight: 500;
  font-size: 26px;
  margin: 8px 0;
}
.quality-heading h3,
.quality-panel h3 {
  font-size: 17px;
  font-weight: 500;
  margin: 0;
}
.quality-filters {
  display: grid;
  grid-template-columns: 150px 1fr 1fr;
  gap: 14px;
}
.quality-filters :deep(.el-date-editor) {
  width: 100%;
  grid-column: span 2;
}
.quality-freshness {
  background: #eff5ff;
  border: 1px solid #dce6ff;
  border-radius: 14px;
  padding: 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
}
.quality-freshness p {
  margin: 6px 0;
  font-size: 13px;
}
.quality-freshness small {
  color: #6e819e;
}
.quality-lagging {
  background: #fff8ed;
  border-color: #f6dfbc;
}
.quality-metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.quality-metrics > div {
  padding: 20px;
  border: 1px solid #e3eaf7;
  border-radius: 13px;
  background: #fafcff;
}
.quality-metrics span,
.quality-metrics small {
  display: block;
  color: #7e8da8;
  font-size: 12px;
}
.quality-metrics strong {
  display: block;
  font-size: 27px;
  font-weight: 500;
  margin: 8px 0;
}
.quality-metrics em {
  font-size: 17px;
  font-style: normal;
  color: #7e8da8;
}
.quality-panel {
  border: 1px solid #e3eaf7;
  border-radius: 14px;
  padding: 22px;
}
.quality-day {
  display: grid;
  grid-template-columns: 100px 1fr 70px 160px;
  align-items: center;
  gap: 18px;
  margin-top: 18px;
  font-size: 13px;
}
.quality-day strong {
  font-weight: 500;
}
.quality-day small {
  color: #8492aa;
}
.quality-track {
  height: 9px;
  border-radius: 8px;
  background: #e9eef9;
  overflow: hidden;
}
.quality-track > div {
  height: 100%;
  background: linear-gradient(90deg, #7797fb, #4c73e8);
  border-radius: 8px;
}
@media (max-width: 1100px) {
  .quality-metrics {
    grid-template-columns: repeat(2, 1fr);
  }
  .quality-filters {
    grid-template-columns: 1fr 1fr;
  }
  .quality-filters :deep(.el-date-editor) {
    grid-column: span 2;
  }
  .quality-day {
    grid-template-columns: 90px 1fr 70px;
  }
  .quality-day small {
    grid-column: 2/4;
  }
  .quality-freshness {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
