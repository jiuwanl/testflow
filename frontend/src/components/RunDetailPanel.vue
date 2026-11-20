<script setup lang="ts">
import { ref, watch } from "vue";
import type { RunReport, RunDetail } from "../types";
import {
  stateNames,
  reasons,
  tone,
  percentage,
  time,
  isTerminal,
} from "../runPresentation";
const props = defineProps<{
  report?: RunReport;
  detail?: RunDetail;
  loading: boolean;
  writable: boolean;
  cancelling: boolean;
  submitting: boolean;
}>();
const emit = defineEmits<{
  cancel: [];
  refresh: [];
  item: [id: string];
  rerun: [];
  current: [];
  parent: [id: string];
}>();
const tab = ref("overview");
watch(
  () => props.report?.run.id,
  () => {
    tab.value = "overview";
  },
);
const pretty = (value: unknown) => JSON.stringify(value, null, 2);
const assertionNames: Record<string, string> = {
  STATUS_CODE: "状态码",
  HEADER: "响应头",
  JSON_EXISTS: "字段存在",
  JSON_EQUALS: "字段等值",
  DURATION_LT: "耗时上限",
};
</script>
<template>
  <section class="execution-detail" v-loading="loading">
    <template v-if="report">
      <div class="run-detail-heading">
        <div>
          <span class="eyebrow">EXECUTION DETAIL · #{{ report.run.id }}</span>
          <h2>{{ report.run.sourceName }}</h2>
          <p class="muted">
            {{ report.run.environmentName }}<span class="inline-dot">·</span
            >{{ report.run.requestedByName }}
          </p>
        </div>
        <el-tag
          :type="
            tone(
              report.run.outcome === 'PASSED'
                ? 'PASSED'
                : report.run.outcome === 'FAILED'
                  ? 'FAILED'
                  : report.run.state,
            )
          "
          effect="light"
          round
        >
          {{
            report.run.outcome === "PASSED"
              ? "全部通过"
              : report.run.outcome === "FAILED"
                ? "未通过"
                : stateNames[report.run.state]
          }}
        </el-tag>
      </div>
      <div class="run-actions">
        <span class="muted"
          >{{
            report.run.sourceType === "PLAN"
              ? report.run.executionMode === "INDEPENDENT"
                ? "独立并行"
                : "有序计划"
              : "单用例"
          }}
          · #{{ report.run.id }}</span
        >
        <el-button
          v-if="report.run.parentRunId"
          link
          @click="emit('parent', report.run.parentRunId)"
          >查看原运行 #{{ report.run.parentRunId }}</el-button
        >
        <template v-if="writable && isTerminal(report.run.state)"
          ><el-button :disabled="submitting" @click="emit('rerun')"
            >按原快照重跑</el-button
          ><el-button link :disabled="submitting" @click="emit('current')"
            >按当前配置执行</el-button
          ></template
        >
      </div>
      <p class="field-hint" v-if="report.run.executionMode === 'INDEPENDENT'">
        冻结并发上限 {{ report.run.maxConcurrency }} · 当前
        {{ report.items.filter((item) => item.state === "RUNNING").length }}
        步执行中。步骤按定义顺序展示，完成顺序不作保证。
      </p>
      <div class="step-selector" v-if="report.items.length > 1">
        <label>查看步骤明细</label
        ><el-select
          :model-value="detail?.item.id"
          @change="emit('item', $event)"
          aria-label="报告步骤"
          ><el-option
            v-for="item in report.items"
            :key="item.id"
            :value="item.id"
            :label="`${item.ordinal}. ${item.caseName} · ${stateNames[item.state]}`"
        /></el-select>
      </div>
      <div class="run-progress-line">
        <el-progress
          :percentage="report.run.metrics.progress ?? 0"
          :show-text="false"
          :stroke-width="5"
        /><span>{{ percentage(report.run.metrics.progress) }} 已收敛</span>
      </div>
      <div class="run-metrics">
        <div>
          <span>通过率</span
          ><strong>{{ percentage(report.run.metrics.passRate) }}</strong
          ><small>仅计算已知结果</small>
        </div>
        <div>
          <span>已知结果覆盖率</span
          ><strong>{{ percentage(report.run.metrics.coverage) }}</strong
          ><small
            >{{
              report.run.metrics.passed +
              report.run.metrics.failed +
              report.run.metrics.errors
            }}
            / {{ report.run.metrics.total }} 个步骤</small
          >
        </div>
        <div>
          <span>所选步骤耗时</span
          ><strong
            >{{
              detail?.item.durationMs === null ||
              detail?.item.durationMs === undefined
                ? "—"
                : detail.item.durationMs
            }}<em
              v-if="
                detail?.item.durationMs !== null &&
                detail?.item.durationMs !== undefined
              "
              >ms</em
            ></strong
          ><small>{{
            detail?.result?.response
              ? `HTTP ${detail.result.response.statusCode}`
              : "尚无可信响应"
          }}</small>
        </div>
      </div>
      <div
        v-if="report.run.terminalReason || detail?.item.errorCode"
        class="execution-notice"
        role="status"
      >
        {{
          reasons[report.run.terminalReason ?? detail?.item.errorCode ?? ""] ??
          reasons[detail?.item.errorCode ?? ""] ??
          "本次执行已结束，详细结果请查看下方记录。"
        }}
      </div>
      <el-tabs v-model="tab" class="report-tabs">
        <el-tab-pane label="概览" name="overview">
          <div class="execution-timeline">
            <div>
              <i class="timeline-dot complete"></i>
              <div>
                <strong>已接受执行</strong
                ><span>{{ time(report.run.createdAt) }}</span>
              </div>
            </div>
            <div>
              <i
                class="timeline-dot"
                :class="{ complete: report.run.startedAt }"
              ></i>
              <div>
                <strong>{{
                  report.run.startedAt ? "已被执行节点领取" : "等待执行节点领取"
                }}</strong
                ><span>{{ time(report.run.startedAt) }}</span>
              </div>
            </div>
            <div>
              <i
                class="timeline-dot"
                :class="{ complete: report.run.finishedAt }"
              ></i>
              <div>
                <strong>{{
                  isTerminal(report.run.state)
                    ? stateNames[report.run.state]
                    : "等待执行结果"
                }}</strong
                ><span>{{ time(report.run.finishedAt) }}</span>
              </div>
            </div>
          </div>
          <div
            v-for="item in report.items"
            :key="item.id"
            class="step-result-row"
            role="button"
            tabindex="0"
            @click="emit('item', item.id)"
            @keydown.enter="emit('item', item.id)"
            :aria-label="`查看步骤 ${item.ordinal} ${item.caseName}`"
          >
            <span class="step-number">{{ item.ordinal }}</span>
            <div>
              <strong>{{ item.caseName }}</strong
              ><span class="muted">{{
                item.errorCode
                  ? (reasons[item.errorCode] ?? item.errorCode)
                  : "独立记录本次配置与结果"
              }}</span>
            </div>
            <el-tag :type="tone(item.state)" size="small">{{
              stateNames[item.state]
            }}</el-tag>
          </div>
          <p
            v-if="detail?.result?.configurationVersions"
            class="report-footnote"
          >
            本次冻结版本：用例 v{{
              detail.result.configurationVersions.caseVersion
            }}
            · 环境 v{{ detail.result.configurationVersions.environmentVersion }}
          </p>
          <p class="report-footnote">
            整体期限：{{
              time(report.run.deadlineAt)
            }}。终态进度包含取消、跳过和不确定结果，不能代替通过率。
          </p>
        </el-tab-pane>
        <el-tab-pane label="请求" name="request">
          <p class="field-hint">
            步骤 {{ detail?.item.ordinal }} · {{ detail?.item.caseName }}
          </p>
          <pre v-if="detail?.result?.request" class="json-surface">{{
            pretty(detail.result.request)
          }}</pre>
          <el-empty v-else description="尚无可展示的请求记录" :image-size="76"
        /></el-tab-pane>
        <el-tab-pane label="响应" name="response">
          <template v-if="detail?.result?.response"
            ><div class="response-meta">
              <el-tag>HTTP {{ detail.result.response.statusCode }}</el-tag
              ><span>{{ detail.result.response.bodyBytes }} bytes</span
              ><span>{{ detail.result.response.durationMs }} ms</span>
            </div>
            <pre v-if="detail.bodyAvailable" class="json-surface">{{
              pretty(detail.result.response.body)
            }}</pre>
            <div v-else class="body-withheld">
              {{
                detail.result.response.bodyUnavailableReason ===
                "SENSITIVE_REQUEST"
                  ? "本次请求包含敏感配置，响应正文已隐藏。断言仍使用完整响应进行判断。"
                  : "该正文无法安全展示，已保留必要响应元数据。"
              }}
            </div>
            <el-collapse class="top-gap"
              ><el-collapse-item title="响应头（已脱敏）">
                <pre class="json-surface">{{
                  pretty(detail.result.response.headers)
                }}</pre>
              </el-collapse-item></el-collapse
            ></template
          >
          <el-empty
            v-else
            :description="
              detail?.bodyTruncated
                ? '响应超过上限，未保存正文或伪造通过结果'
                : '暂无可信的 HTTP 响应'
            "
            :image-size="76"
          />
        </el-tab-pane>
        <el-tab-pane label="断言与提取" name="assertions">
          <template v-if="detail?.result?.assertions"
            ><div
              v-for="assertion in detail.result.assertions"
              :key="assertion.id"
              class="assertion-row"
            >
              <span :class="assertion.passed ? 'check-pass' : 'check-fail'">{{
                assertion.passed ? "✓" : "×"
              }}</span>
              <div>
                <strong>{{ assertion.id }}</strong
                ><small>{{
                  assertionNames[assertion.type] ?? assertion.type
                }}</small>
              </div>
              <el-tag
                :type="assertion.passed ? 'success' : 'danger'"
                size="small"
                >{{ assertion.passed ? "通过" : "未通过" }}</el-tag
              >
            </div>
            <p v-if="!detail.result.assertions.length" class="muted">
              本次未配置断言。
            </p>
            <div v-if="detail.result.extractors?.length" class="top-gap">
              <div class="subheading">提取结果摘要</div>
              <div
                v-for="extractor in detail.result.extractors"
                :key="extractor.name"
                class="assertion-row"
              >
                <span
                  >{{ extractor.name
                  }}{{ extractor.secret ? " · 敏感变量" : "" }}</span
                ><el-tag
                  :type="extractor.success ? 'success' : 'danger'"
                  size="small"
                  >{{ extractor.success ? "提取成功" : "未提取" }}</el-tag
                >
              </div>
            </div></template
          >
          <el-empty
            v-else
            description="执行完成后显示断言结果"
            :image-size="76"
          />
        </el-tab-pane>
      </el-tabs>
      <footer class="run-detail-footer">
        <span>{{
          isTerminal(report.run.state)
            ? "结果已固定，自动刷新已停止"
            : "正在自动刷新执行进度"
        }}</span>
        <div>
          <el-button @click="$emit('refresh')">刷新</el-button
          ><el-button
            v-if="writable && !isTerminal(report.run.state)"
            :loading="cancelling"
            type="danger"
            plain
            @click="$emit('cancel')"
            >取消执行</el-button
          >
        </div>
      </footer>
    </template>
    <el-empty
      v-else
      description="选择一次执行，查看它的完整过程"
      :image-size="92"
    />
  </section>
</template>
