<script setup lang="ts">
import { reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import CollectionToolbar from "./CollectionToolbar.vue";
import { api, notifyError } from "../api";
import { useCollection } from "../useCollection";
import type { Summary, Page } from "../types";
const props = defineProps<{ projectId: string; writable: boolean }>();
const emit = defineEmits<{ run: [id: string] }>();
const path = "/projects/" + props.projectId + "/plans";
const { rows, page, total, loading, name, status, load } =
  useCollection<Summary>(path);
const open = ref(false),
  saving = ref(false),
  editing = ref<string>(),
  cases = ref<Summary[]>([]);
const form = reactive({
  name: "",
  executionMode: "ORDERED",
  maxConcurrency: 1,
  failFast: true,
  steps: [] as { caseId: string; enabled: boolean }[],
  version: 0,
  status: "ACTIVE",
});
async function searchCases(query = "") {
  try {
    cases.value = (
      await api<Page<Summary>>(
        "/projects/" +
          props.projectId +
          "/cases?size=100&status=ACTIVE&name=" +
          encodeURIComponent(query),
      )
    ).items;
  } catch (e) {
    notifyError(e);
  }
}
async function edit(row?: Summary) {
  try {
    editing.value = row?.id;
    Object.assign(form, {
      name: "",
      executionMode: "ORDERED",
      maxConcurrency: 1,
      failFast: true,
      steps: [],
      version: 0,
      status: "ACTIVE",
    });
    await searchCases();
    if (row) Object.assign(form, await api(path + "/" + row.id));
    open.value = true;
  } catch (e) {
    notifyError(e);
  }
}
function move(index: number, delta: number) {
  const target = index + delta;
  if (target < 0 || target >= form.steps.length) return;
  const steps = [...form.steps];
  [steps[index], steps[target]] = [steps[target]!, steps[index]!];
  form.steps = steps;
}
async function save() {
  saving.value = true;
  try {
    await api(
      path + (editing.value ? "/" + editing.value : ""),
      editing.value ? "PATCH" : "POST",
      {
        name: form.name,
        executionMode: form.executionMode,
        maxConcurrency: form.maxConcurrency,
        failFast: form.failFast,
        steps: form.steps,
        ...(editing.value
          ? { version: form.version, status: form.status }
          : {}),
      },
    );
    open.value = false;
    ElMessage.success("计划已保存");
    await load();
  } catch (e) {
    notifyError(e);
  } finally {
    saving.value = false;
  }
}
</script>
<template>
  <CollectionToolbar
    title="测试计划"
    description="串联业务流程，或以受控并发巡检彼此独立的接口。"
    :writable="writable"
    create-label="新建计划"
    @create="edit()"
    @refresh="load()"
  />
  <div class="filters">
    <el-input
      v-model="name"
      aria-label="计划名称筛选"
      placeholder="搜索计划名称"
      clearable
      @keyup.enter="load(true)"
    /><el-select
      v-model="status"
      aria-label="计划状态筛选"
      placeholder="全部状态"
      clearable
      @change="load(true)"
      ><el-option label="活动" value="ACTIVE" /><el-option
        label="已归档"
        value="ARCHIVED" /></el-select
    ><el-button @click="load(true)">筛选</el-button>
  </div>
  <el-table
    v-loading="loading"
    :data="rows"
    empty-text="尚无测试计划，先创建用例，再组合业务流程"
  >
    <el-table-column prop="name" label="计划名称" /><el-table-column
      label="模式"
      width="190"
      ><template #default="{ row }"
        ><el-tag
          :type="row.executionMode === 'INDEPENDENT' ? 'success' : 'primary'"
          >{{
            row.executionMode === "INDEPENDENT"
              ? `独立并行 · 上限 ${row.maxConcurrency}`
              : "有序执行 · 串行"
          }}</el-tag
        ></template
      ></el-table-column
    >
    <el-table-column label="状态" width="120"
      ><template #default="{ row }"
        ><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{
          row.status === "ACTIVE" ? "活动" : "已归档"
        }}</el-tag></template
      ></el-table-column
    >
    <el-table-column prop="version" label="版本" width="90" /><el-table-column
      label="操作"
      width="180"
      ><template #default="{ row }"
        ><el-button link type="primary" @click="edit(row)">{{
          writable ? "编辑" : "查看"
        }}</el-button
        ><el-button
          v-if="writable && row.status === 'ACTIVE'"
          link
          type="primary"
          @click="emit('run', row.id)"
          >执行计划 ↗</el-button
        ></template
      ></el-table-column
    >
  </el-table>
  <el-pagination
    class="pagination"
    v-model:current-page="page"
    :total="total"
    :page-size="20"
    layout="total, prev, pager, next"
    @current-change="load()"
  />
  <el-dialog
    v-model="open"
    :title="editing ? '计划配置' : '新建计划'"
    width="760px"
    destroy-on-close
  >
    <el-form label-position="top" :disabled="!writable">
      <el-form-item label="计划名称"
        ><el-input v-model="form.name" maxlength="100"
      /></el-form-item>
      <el-form-item label="执行模式">
        <el-radio-group
          v-model="form.executionMode"
          @change="
            form.maxConcurrency = form.executionMode === 'INDEPENDENT' ? 2 : 1
          "
          aria-label="计划执行模式"
        >
          <el-radio-button value="ORDERED">有序流程</el-radio-button
          ><el-radio-button value="INDEPENDENT">独立并行</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item
        v-if="form.executionMode === 'INDEPENDENT'"
        label="单次运行并发上限"
      >
        <el-input-number
          v-model="form.maxConcurrency"
          :min="1"
          :max="4"
          :precision="0"
          aria-label="计划并发上限"
        />
      </el-form-item>
      <p v-if="form.executionMode === 'INDEPENDENT'" class="field-hint">
        仅适用于互不依赖的步骤，不能引用运行变量（run）。完成顺序不作保证；实际并发还受
        Worker 资源预算限制。
      </p>
      <el-form-item label="失败策略"
        ><el-switch
          v-model="form.failFast"
          active-text="失败后停止派发后续步骤"
      /></el-form-item>
      <p
        v-if="form.executionMode === 'INDEPENDENT' && form.failFast"
        class="field-hint"
      >
        失败时跳过尚未派发的步骤，已在执行的请求继续收集结果。
      </p>
      <div class="subheading">
        {{ form.executionMode === "INDEPENDENT" ? "独立步骤" : "步骤顺序" }}
      </div>
      <p class="field-hint">
        同一用例可以重复引用。停用的步骤保留在计划中，但不参加执行。
      </p>
      <div v-for="(step, index) in form.steps" :key="index" class="plan-step">
        <span class="step-number">{{ index + 1 }}</span
        ><el-select
          v-model="step.caseId"
          filterable
          remote
          :remote-method="searchCases"
          placeholder="搜索并选择用例"
          aria-label="步骤用例"
        >
          <el-option
            v-for="c in cases"
            :key="c.id"
            :label="c.name"
            :value="c.id"
          />
          <el-option
            v-if="step.caseId && !cases.some((c) => c.id === step.caseId)"
            :label="'用例 #' + step.caseId"
            :value="step.caseId"
          /> </el-select
        ><el-checkbox v-model="step.enabled">启用</el-checkbox>
        <el-button :disabled="index === 0" @click="move(index, -1)"
          >上移</el-button
        ><el-button
          :disabled="index === form.steps.length - 1"
          @click="move(index, 1)"
          >下移</el-button
        >
        <el-button type="danger" link @click="form.steps.splice(index, 1)"
          >移除</el-button
        >
      </div>
      <el-button
        v-if="writable"
        class="top-gap"
        :disabled="form.steps.length >= 100"
        @click="form.steps.push({ caseId: '', enabled: true })"
        >添加步骤</el-button
      >
      <el-form-item v-if="editing" label="状态" class="top-gap"
        ><el-select v-model="form.status"
          ><el-option label="活动" value="ACTIVE" /><el-option
            label="已归档"
            value="ARCHIVED" /></el-select
      ></el-form-item>
    </el-form>
    <template #footer
      ><el-button @click="open = false">关闭</el-button
      ><el-button v-if="writable" type="primary" :loading="saving" @click="save"
        >保存计划</el-button
      ></template
    >
  </el-dialog>
</template>
