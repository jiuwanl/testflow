<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, ApiError, notifyError, parseJson } from "../api";
import { isTerminal, stateNames, tone, time } from "../runPresentation";
import type { Summary, Page, Run, RunReport, RunDetail } from "../types";
import HistoryFilters from "./HistoryFilters.vue";
import RunDetailPanel from "./RunDetailPanel.vue";
const props = defineProps<{
  projectId: string;
  writable: boolean;
  caseId?: string;
  planId?: string;
  runId?: string;
}>();
const path = `/projects/${props.projectId}`;
const cases = ref<Summary[]>([]),
  environments = ref<Summary[]>([]),
  rows = ref<Run[]>([]);
const sourceType = ref(props.planId ? "PLAN" : "CASE");
const page = ref(1),
  total = ref(0),
  historyQuery = ref("");
const selectedItem = ref("");
let listVersion = 0,
  sourceVersion = 0;
const selectedCase = ref(props.planId ?? props.caseId ?? ""),
  selectedEnvironment = ref("");
const timeout = ref<number | undefined>(),
  variables = ref("{}");
const report = ref<RunReport>(),
  detail = ref<RunDetail>();
const modeLabel = computed(() => {
  if (!props.writable && !report.value) return "HISTORY · 执行档案";
  const frozen = !props.writable ? report.value?.run : undefined;
  const source = frozen?.sourceType ?? sourceType.value;
  const mode =
    frozen?.executionMode ??
    cases.value.find((c) => c.id === selectedCase.value)?.executionMode;
  return source === "CASE"
    ? "CASE · 单接口验证"
    : mode === "INDEPENDENT"
      ? "INDEPENDENT · 独立巡检"
      : "ORDERED · 有序回归";
});
const busy = ref(false),
  loading = ref(false),
  cancelling = ref(false),
  listLoading = ref(false);
const selectedRun = ref("");
const pending = ref<{ key: string; body: unknown; url?: string }>();
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false,
  requestVersion = 0;
async function searchCases(name = "") {
  const version = ++sourceVersion;
  try {
    const value = (
      await api<Page<Summary>>(
        `${path}/${sourceType.value === "PLAN" ? "plans" : "cases"}?status=ACTIVE&size=100&name=${encodeURIComponent(name)}`,
      )
    ).items;
    if (!disposed && version === sourceVersion) cases.value = value;
  } catch (error) {
    notifyError(error);
  }
}
async function recent() {
  listLoading.value = true;
  const version = ++listVersion;
  try {
    const value = await api<Page<Run>>(
      `${path}/runs?page=${page.value}&size=20${historyQuery.value}`,
    );
    if (disposed || version !== listVersion) return;
    rows.value = value.items;
    total.value = value.total;
  } catch (error) {
    notifyError(error);
  } finally {
    if (version === listVersion) listLoading.value = false;
  }
}
async function inspect(id: string) {
  if (!id || disposed) return;
  if (timer) clearTimeout(timer);
  selectedRun.value = id;
  const version = ++requestVersion;
  loading.value = !report.value || report.value.run.id !== id;
  if (loading.value) {
    report.value = undefined;
    detail.value = undefined;
    selectedItem.value = "";
  }
  try {
    const value = await api<RunReport>(`${path}/runs/${id}/report`);
    const item =
      value.items.find((item) => item.id === selectedItem.value) ??
      value.items.find((item) => item.state === "RUNNING") ??
      value.items[0];
    const data = item
      ? await api<RunDetail>(`${path}/runs/${id}/items/${item.id}`)
      : undefined;
    if (disposed || version !== requestVersion) return;
    const stateChanged = report.value?.run.state !== value.run.state;
    selectedItem.value = item?.id ?? "";
    report.value = value;
    detail.value = data;
    if (stateChanged) await recent();
    if (!disposed && version === requestVersion && !isTerminal(value.run.state))
      timer = setTimeout(() => {
        void inspect(id);
      }, 1500);
  } catch (error) {
    if (!disposed && version === requestVersion) notifyError(error);
  } finally {
    if (version === requestVersion) loading.value = false;
  }
}
async function submit() {
  if (busy.value) return;
  try {
    if (!pending.value) {
      if (!selectedCase.value || !selectedEnvironment.value) {
        ElMessage.warning("请先选择执行来源与环境");
        return;
      }
      pending.value = {
        key: crypto.randomUUID(),
        body: {
          sourceType: sourceType.value,
          sourceId: selectedCase.value,
          environmentId: selectedEnvironment.value,
          overrides: {
            variables: parseJson<Record<string, string>>(
              variables.value,
              "普通变量覆盖",
            ),
            ...(timeout.value === undefined
              ? {}
              : { requestTimeoutMs: timeout.value }),
          },
        },
      };
    }
    busy.value = true;
    const value = await api<{ id: string }>(
      pending.value.url ?? `${path}/runs`,
      "POST",
      pending.value.body,
      { "Idempotency-Key": pending.value.key },
    );
    pending.value = undefined;
    ElMessage.success("执行已接受");
    await recent();
    await inspect(value.id);
  } catch (error) {
    if (
      error instanceof ApiError &&
      error.status >= 400 &&
      error.status < 500 &&
      error.status !== 429
    )
      pending.value = undefined;
    notifyError(error);
  } finally {
    busy.value = false;
  }
}
async function cancel() {
  try {
    await ElMessageBox.confirm(
      "在途请求可能已经被目标处理。取消不会撤销目标端的操作。",
      "取消本次执行",
      {
        type: "warning",
        confirmButtonText: "确认取消",
        cancelButtonText: "继续执行",
      },
    );
    cancelling.value = true;
    await api(`${path}/runs/${selectedRun.value}/cancel`, "POST", {});
    await inspect(selectedRun.value);
  } catch (error) {
    if (error !== "cancel" && error !== "close") notifyError(error);
  } finally {
    cancelling.value = false;
  }
}
async function selectKind() {
  selectedCase.value = "";
  await searchCases();
  selectedCase.value = cases.value[0]?.id ?? "";
}
async function filterHistory(query: string) {
  historyQuery.value = query;
  page.value = 1;
  await recent();
  if (rows.value[0]) await inspect(rows.value[0].id);
  else {
    requestVersion++;
    if (timer) clearTimeout(timer);
    selectedRun.value = "";
    selectedItem.value = "";
    report.value = undefined;
    detail.value = undefined;
  }
}
async function chooseItem(id: string) {
  selectedItem.value = id;
  await inspect(selectedRun.value);
}
async function replay(original: boolean) {
  if (!report.value || busy.value || pending.value) return;
  try {
    await ElMessageBox.confirm(
      original
        ? "将保留原步骤、变量、环境和失败策略，动态提取值会重新获取。当前权限与目标授权仍会检查。"
        : "将读取当前来源与环境，生成新的快照；不会沿用历史覆盖项。",
      original ? "按原快照重跑" : "按当前配置执行",
      { confirmButtonText: "确认执行", cancelButtonText: "取消" },
    );
    const run = report.value.run;
    pending.value = {
      key: crypto.randomUUID(),
      url: original ? `${path}/runs/${run.id}/reruns` : `${path}/runs`,
      body: original
        ? {}
        : {
            sourceType: run.sourceType,
            sourceId: run.sourceId,
            environmentId: run.environmentId,
          },
    };
    await submit();
  } catch (error) {
    if (error !== "cancel" && error !== "close") notifyError(error);
  }
}

onMounted(async () => {
  await Promise.all([searchCases(), recent()]);
  try {
    environments.value = (
      await api<Page<Summary>>(`${path}/environments?status=ACTIVE&size=100`)
    ).items;
    selectedEnvironment.value = environments.value[0]?.id ?? "";
    const launchId = props.planId ?? props.caseId;
    if (launchId && !cases.value.some((value) => value.id === launchId))
      cases.value.unshift(
        await api<Summary>(
          `${path}/${props.planId ? "plans" : "cases"}/${launchId}`,
        ),
      );
    if (!selectedCase.value) selectedCase.value = cases.value[0]?.id ?? "";
    if (props.runId) await inspect(props.runId);
    else if (rows.value[0]) await inspect(rows.value[0].id);
  } catch (error) {
    notifyError(error);
  }
});
onBeforeUnmount(() => {
  disposed = true;
  requestVersion++;
  listVersion++;
  if (timer) clearTimeout(timer);
});
</script>
<template>
  <div class="execution-workspace">
    <div class="execution-title">
      <div>
        <span class="eyebrow">RUN · OBSERVE · UNDERSTAND</span>
        <h2>每一次执行，都有清晰的来处。</h2>
        <p>从一个接口到完整业务流程，追踪每一步输入、结果与来处。</p>
      </div>
      <span class="execution-mode-label">{{ modeLabel }}</span>
    </div>
    <section v-if="writable" class="launch-card">
      <el-radio-group
        v-model="sourceType"
        :disabled="busy || !!pending"
        @change="selectKind"
        class="source-kind"
        aria-label="执行来源类型"
        ><el-radio-button value="CASE">单用例</el-radio-button
        ><el-radio-button value="PLAN"
          >测试计划</el-radio-button
        ></el-radio-group
      >
      <div class="launch-main">
        <div class="launch-field">
          <label>{{ sourceType === "PLAN" ? "测试计划" : "接口用例" }}</label
          ><el-select
            v-model="selectedCase"
            :aria-label="sourceType === 'PLAN' ? '执行计划' : '执行用例'"
            filterable
            remote
            :remote-method="searchCases"
            :disabled="busy || !!pending"
            placeholder="搜索并选择执行来源"
            ><el-option
              v-for="item in cases"
              :key="item.id"
              :label="item.name"
              :value="item.id"
          /></el-select>
        </div>
        <div class="launch-field">
          <label>运行环境</label
          ><el-select
            v-model="selectedEnvironment"
            aria-label="执行环境"
            :disabled="busy || !!pending"
            placeholder="选择活动环境"
            ><el-option
              v-for="item in environments"
              :key="item.id"
              :label="item.name"
              :value="item.id"
          /></el-select>
        </div>
        <el-button
          type="primary"
          size="large"
          :aria-label="pending && !busy ? '重试原提交' : '开始执行'"
          :loading="busy"
          @click="submit"
          >{{ pending && !busy ? "重试原提交" : "开始执行"
          }}<span aria-hidden="true" class="button-arrow">↗</span></el-button
        >
      </div>
      <el-collapse class="launch-options"
        ><el-collapse-item title="本次执行选项"
          ><el-form label-position="top" :disabled="busy || !!pending"
            ><div class="launch-options-grid">
              <el-form-item label="请求总时限（毫秒）"
                ><el-input-number
                  v-model="timeout"
                  placeholder="沿用用例配置"
                  :min="1"
                  :max="60000"
                />
                <p class="field-hint">
                  留空沿用用例配置，最高 60000 毫秒。
                </p></el-form-item
              ><el-form-item label="普通变量覆盖 · JSON 对象"
                ><el-input
                  v-model="variables"
                  type="textarea"
                  :rows="3"
                  spellcheck="false"
                />
                <p class="field-hint">
                  只作用于本次执行，不修改环境；不能覆盖敏感变量。
                </p></el-form-item
              >
            </div></el-form
          ></el-collapse-item
        ></el-collapse
      >
      <div v-if="pending && !busy" class="submission-note">
        上次提交结果尚未确认。重试会复用原提交，避免创建重复执行。<el-button
          link
          @click="pending = undefined"
          >开始新的提交</el-button
        >
      </div>
      <p v-if="!environments.length || !cases.length" class="field-hint">
        先准备活动用例与环境，并由管理员授权精确目标地址。
      </p>
    </section>
    <HistoryFilters :project-id="projectId" @search="filterHistory" />
    <div class="execution-grid">
      <section class="recent-runs">
        <header>
          <div>
            <h3>执行历史</h3>
            <span>共 {{ total }} 次执行 · 每页 20 条</span>
          </div>
          <el-button link @click="recent">刷新</el-button>
        </header>
        <div v-loading="listLoading" class="recent-runs-list">
          <button
            v-for="run in rows"
            :key="run.id"
            class="recent-run"
            :class="{ selected: selectedRun === run.id }"
            @click="inspect(run.id)"
          >
            <span
              class="run-state-orb"
              :class="
                run.outcome === 'PASSED'
                  ? 'passed'
                  : run.outcome === 'FAILED'
                    ? 'failed'
                    : run.state.toLowerCase()
              "
            ></span
            ><span class="recent-run-copy"
              ><strong>{{ run.sourceName }}</strong
              ><small
                >{{ run.sourceType === "PLAN" ? "计划" : "用例" }} #{{
                  run.id
                }}
                · {{ run.environmentName }} · {{ time(run.createdAt) }}</small
              ></span
            ><el-tag
              :type="
                tone(
                  run.outcome === 'PASSED'
                    ? 'PASSED'
                    : run.outcome === 'FAILED'
                      ? 'FAILED'
                      : run.state,
                )
              "
              size="small"
              >{{
                run.outcome === "PASSED"
                  ? "通过"
                  : run.outcome === "FAILED"
                    ? "未通过"
                    : stateNames[run.state]
              }}</el-tag
            ></button
          ><el-empty
            v-if="!rows.length"
            description="还没有执行记录"
            :image-size="70"
          />
        </div>
        <el-pagination
          v-model:current-page="page"
          :total="total"
          :page-size="20"
          layout="prev, pager, next"
          @current-change="recent"
          size="small"
        />
      </section>
      <RunDetailPanel
        :report="report"
        :detail="detail"
        :loading="loading"
        :writable="writable"
        :cancelling="cancelling"
        @cancel="cancel"
        @item="chooseItem"
        @rerun="replay(true)"
        @current="replay(false)"
        @parent="inspect"
        :submitting="busy || !!pending"
        @refresh="inspect(selectedRun)"
      />
    </div>
  </div>
</template>
