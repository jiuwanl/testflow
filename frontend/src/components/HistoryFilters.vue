<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { api, notifyError } from "../api";
import type { Page, Summary } from "../types";
const props = defineProps<{ projectId: string }>();
const emit = defineEmits<{ search: [query: string] }>();
const form = reactive({
  sourceType: "",
  sourceId: "",
  environmentId: "",
  state: "",
  outcome: "",
  createdFrom: "",
  createdTo: "",
});
const sources = ref<Summary[]>([]),
  environments = ref<Summary[]>([]);
let sourceVersion = 0;
async function searchSources(name = "") {
  const version = ++sourceVersion;
  if (!form.sourceType) {
    sources.value = [];
    return;
  }
  try {
    const value = await api<Page<Summary>>(
      `/projects/${props.projectId}/${form.sourceType === "PLAN" ? "plans" : "cases"}?size=100&name=${encodeURIComponent(name)}`,
    );
    if (version === sourceVersion) sources.value = value.items;
  } catch (error) {
    notifyError(error);
  }
}
async function searchEnvironments(name = "") {
  try {
    environments.value = (
      await api<Page<Summary>>(
        `/projects/${props.projectId}/environments?size=100&name=${encodeURIComponent(name)}`,
      )
    ).items;
  } catch (error) {
    notifyError(error);
  }
}
watch(
  () => form.sourceType,
  () => {
    form.sourceId = "";
    void searchSources();
  },
);
void searchEnvironments();
function search() {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(form))
    if (value) params.set(key, value);
  emit("search", params.size ? "&" + params.toString() : "");
}
function reset() {
  Object.assign(form, {
    sourceType: "",
    sourceId: "",
    environmentId: "",
    state: "",
    outcome: "",
    createdFrom: "",
    createdTo: "",
  });
  search();
}
</script>
<template>
  <section class="history-filter-card">
    <div class="history-filter-heading">
      <h3>找到一次回归，读懂每一步。</h3>
      <span class="muted">按提交时间筛选 · 包含归档来源</span>
    </div>
    <div class="history-filter-grid">
      <el-select
        v-model="form.sourceType"
        placeholder="全部来源类型"
        clearable
        aria-label="历史来源类型"
        ><el-option value="PLAN" label="测试计划" /><el-option
          value="CASE"
          label="单用例"
      /></el-select>
      <el-select
        v-model="form.sourceId"
        :disabled="!form.sourceType"
        filterable
        remote
        :remote-method="searchSources"
        clearable
        placeholder="全部用例 / 计划"
        aria-label="历史来源"
        ><el-option
          v-for="source in sources"
          :key="source.id"
          :value="source.id"
          :label="source.name"
      /></el-select>
      <el-select
        v-model="form.environmentId"
        filterable
        remote
        :remote-method="searchEnvironments"
        clearable
        placeholder="全部环境"
        aria-label="历史环境"
        ><el-option
          v-for="env in environments"
          :key="env.id"
          :value="env.id"
          :label="env.name"
      /></el-select>
      <el-select
        v-model="form.state"
        clearable
        placeholder="全部状态"
        aria-label="历史状态"
        ><el-option
          v-for="[value, label] in [
            ['QUEUED', '排队'],
            ['RUNNING', '运行中'],
            ['CANCELLING', '取消中'],
            ['COMPLETED', '已完成'],
            ['CANCELLED', '已取消'],
            ['TIMED_OUT', '运行超时'],
            ['INTERRUPTED', '已中断'],
          ]"
          :key="value"
          :value="value!"
          :label="label!"
      /></el-select>
      <el-select
        v-model="form.outcome"
        clearable
        placeholder="全部结论"
        aria-label="历史结论"
        ><el-option value="PASSED" label="通过" /><el-option
          value="FAILED"
          label="失败" /><el-option value="INCOMPLETE" label="不完整"
      /></el-select>
      <el-date-picker
        v-model="form.createdFrom"
        type="datetime"
        value-format="YYYY-MM-DDTHH:mm:ssZ"
        placeholder="提交起始时间（含）"
        aria-label="提交起始时间"
      />
      <el-date-picker
        v-model="form.createdTo"
        type="datetime"
        value-format="YYYY-MM-DDTHH:mm:ssZ"
        placeholder="提交结束时间（不含）"
        aria-label="提交结束时间"
      />
      <div class="filter-buttons">
        <el-button type="primary" plain @click="search">筛选历史</el-button
        ><el-button @click="reset">重置</el-button>
      </div>
    </div>
  </section>
</template>
