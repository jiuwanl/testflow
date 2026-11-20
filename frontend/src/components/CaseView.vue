<script setup lang="ts">
import { reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import CollectionToolbar from "./CollectionToolbar.vue";
import { api, notifyError, parseJson } from "../api";
import { useCollection } from "../useCollection";
import type { Summary } from "../types";
const props = defineProps<{ projectId: string; writable: boolean }>();
const emit = defineEmits<{ run: [caseId: string] }>();
const path = "/projects/" + props.projectId + "/cases";
const { rows, page, total, loading, name, status, load } =
  useCollection<Summary>(path);
const open = ref(false),
  saving = ref(false),
  editing = ref<string>();
const form = reactive({
  name: "",
  priority: "P2",
  tags: [] as string[],
  method: "GET",
  path: "/",
  query: "[]",
  headers: "[]",
  bodyType: "NONE",
  body: "{}",
  assertions:
    '[\n  { "id": "status", "type": "STATUS_CODE", "expected": 200 }\n]',
  extractors: "[]",
  timeoutMs: 15000,
  version: 0,
  status: "ACTIVE",
});
interface Config {
  request: {
    method: string;
    path: string;
    query: unknown[];
    headers: unknown[];
    body: { type: string; json?: unknown };
  };
  assertions: unknown[];
  extractors: unknown[];
  timeoutMs?: number;
}
async function edit(row?: Summary) {
  try {
    editing.value = row?.id;
    Object.assign(form, {
      name: "",
      priority: "P2",
      tags: [],
      method: "GET",
      path: "/",
      query: "[]",
      headers: "[]",
      bodyType: "NONE",
      body: "{}",
      assertions:
        '[\n  { "id": "status", "type": "STATUS_CODE", "expected": 200 }\n]',
      extractors: "[]",
      timeoutMs: 15000,
      version: 0,
      status: "ACTIVE",
    });
    if (row) {
      const value = await api<Summary & { tags: string[]; config: Config }>(
          path + "/" + row.id,
        ),
        c = value.config;
      Object.assign(form, {
        name: value.name,
        priority: value.priority,
        tags: value.tags,
        version: value.version,
        status: value.status,
        method: c.request.method,
        path: c.request.path,
        query: JSON.stringify(c.request.query, null, 2),
        headers: JSON.stringify(c.request.headers, null, 2),
        bodyType: c.request.body.type,
        body: JSON.stringify(
          c.request.body.json === undefined ? {} : c.request.body.json,
          null,
          2,
        ),
        assertions: JSON.stringify(c.assertions, null, 2),
        extractors: JSON.stringify(c.extractors, null, 2),
        timeoutMs: c.timeoutMs ?? 15000,
      });
    }
    open.value = true;
  } catch (e) {
    notifyError(e);
  }
}
async function save() {
  saving.value = true;
  try {
    const config: Config = {
      request: {
        method: form.method,
        path: form.path,
        query: parseJson(form.query, "Query"),
        headers: parseJson(form.headers, "Headers"),
        body: {
          type: form.bodyType,
          ...(form.bodyType === "JSON"
            ? { json: parseJson(form.body, "JSON 正文") }
            : {}),
        },
      },
      assertions: parseJson(form.assertions, "断言"),
      extractors: parseJson(form.extractors, "提取器"),
      timeoutMs: form.timeoutMs,
    };
    await api(
      path + (editing.value ? "/" + editing.value : ""),
      editing.value ? "PATCH" : "POST",
      {
        name: form.name,
        priority: form.priority,
        tags: form.tags,
        configSchemaVersion: 1,
        config,
        ...(editing.value
          ? { version: form.version, status: form.status }
          : {}),
      },
    );
    open.value = false;
    ElMessage.success("用例已保存");
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
    title="接口用例"
    description="保存请求与声明式校验规则，复用到有序测试计划中。"
    :writable="writable"
    create-label="新建用例"
    @create="edit()"
    @refresh="load()"
  />
  <div class="filters">
    <el-input
      v-model="name"
      aria-label="用例名称筛选"
      placeholder="搜索用例名称"
      clearable
      @keyup.enter="load(true)"
    /><el-select
      v-model="status"
      aria-label="用例状态筛选"
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
    empty-text="暂无接口用例，从一个清晰的请求开始"
  >
    <el-table-column prop="name" label="用例名称" /><el-table-column
      prop="priority"
      label="优先级"
      width="100"
    />
    <el-table-column label="状态" width="120"
      ><template #default="{ row }"
        ><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{
          row.status === "ACTIVE" ? "活动" : "已归档"
        }}</el-tag></template
      ></el-table-column
    >
    <el-table-column prop="version" label="版本" width="90" /><el-table-column
      label="操作"
      width="190"
      ><template #default="{ row }"
        ><el-button type="primary" link @click="edit(row)">{{
          writable ? "编辑" : "查看"
        }}</el-button
        ><el-button
          v-if="writable && row.status === 'ACTIVE'"
          link
          type="primary"
          @click="emit('run', row.id)"
          >执行 ↗</el-button
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
    :title="editing ? '用例配置' : '新建用例'"
    width="850px"
    destroy-on-close
  >
    <el-form label-position="top" :disabled="!writable">
      <div class="form-grid">
        <el-form-item label="用例名称"
          ><el-input v-model="form.name" maxlength="100" /></el-form-item
        ><el-form-item label="优先级"
          ><el-select v-model="form.priority"
            ><el-option
              v-for="p in ['P0', 'P1', 'P2', 'P3']"
              :key="p"
              :value="p" /></el-select
        ></el-form-item>
      </div>
      <el-form-item label="标签">
        <el-select
          v-model="form.tags"
          multiple
          filterable
          allow-create
          default-first-option
          placeholder="输入标签后按回车"
          style="width: 100%"
        >
          <el-option
            v-for="tag in form.tags"
            :key="tag"
            :label="tag"
            :value="tag"
          />
        </el-select>
      </el-form-item>
      <div class="request-line">
        <el-select v-model="form.method" aria-label="请求方法"
          ><el-option
            v-for="m in [
              'GET',
              'POST',
              'PUT',
              'PATCH',
              'DELETE',
              'HEAD',
              'OPTIONS',
            ]"
            :key="m"
            :value="m" /></el-select
        ><el-input
          v-model="form.path"
          aria-label="请求路径"
          placeholder="/api/items"
        />
      </div>
      <p class="field-hint">
        目标地址由环境提供。环境变量使用 ${env.name}，前序提取变量使用
        ${run.name}。
      </p>
      <el-tabs>
        <el-tab-pane label="参数与正文">
          <div class="form-grid">
            <el-form-item label="Query · name/value 数组"
              ><el-input
                v-model="form.query"
                type="textarea"
                :rows="4" /></el-form-item
            ><el-form-item label="Headers · name/value 数组"
              ><el-input v-model="form.headers" type="textarea" :rows="4"
            /></el-form-item>
          </div>
          <el-form-item label="正文类型"
            ><el-select v-model="form.bodyType"
              ><el-option label="无正文" value="NONE" /><el-option
                label="JSON"
                value="JSON" /></el-select
          ></el-form-item>
          <el-form-item v-if="form.bodyType === 'JSON'" label="JSON 正文"
            ><el-input
              v-model="form.body"
              type="textarea"
              :rows="6"
              spellcheck="false"
          /></el-form-item>
        </el-tab-pane>
        <el-tab-pane label="断言与提取">
          <el-form-item label="断言 · JSON 数组"
            ><el-input
              v-model="form.assertions"
              type="textarea"
              :rows="7"
              spellcheck="false"
          /></el-form-item>
          <p class="field-hint">
            支持
            STATUS_CODE、HEADER、JSON_EXISTS、JSON_EQUALS、DURATION_LT。字段路径示例：$.data.id。
          </p>
          <el-form-item label="提取器 · JSON 数组"
            ><el-input
              v-model="form.extractors"
              type="textarea"
              :rows="4"
              spellcheck="false"
          /></el-form-item>
          <p class="field-hint">
            每项包含 name、path、secret，例如 { "name": "id", "path":
            "$.data.id", "secret": false }。
          </p>
        </el-tab-pane>
      </el-tabs>
      <div class="form-grid">
        <el-form-item label="请求超时（毫秒）"
          ><el-input-number v-model="form.timeoutMs" :min="1" :max="60000"
        /></el-form-item>
        <el-form-item v-if="editing" label="状态"
          ><el-select v-model="form.status"
            ><el-option label="活动" value="ACTIVE" /><el-option
              label="已归档"
              value="ARCHIVED" /></el-select
        ></el-form-item>
      </div>
    </el-form>
    <template #footer
      ><el-button @click="open = false">关闭</el-button
      ><el-button v-if="writable" type="primary" :loading="saving" @click="save"
        >保存用例</el-button
      ></template
    >
  </el-dialog>
</template>
