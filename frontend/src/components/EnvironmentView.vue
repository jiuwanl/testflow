<script setup lang="ts">
import { reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import CollectionToolbar from "./CollectionToolbar.vue";
import { api, notifyError, parseJson } from "../api";
import { useCollection } from "../useCollection";
import type { Summary } from "../types";
const props = defineProps<{ projectId: string; writable: boolean }>();
const path = "/projects/" + props.projectId + "/environments";
const { rows, page, total, loading, name, status, load } =
  useCollection<Summary>(path);
interface Detail extends Summary {
  baseUrl: string;
  variables: Record<string, string>;
  secretNames: string[];
}
const open = ref(false),
  saving = ref(false),
  editing = ref<string>(),
  secretNames = ref<string[]>([]);
const form = ref({
  name: "",
  baseUrl: "",
  variables: "{}",
  version: 0,
  status: "ACTIVE",
});
const changes = ref<{ name: string; operation: string; value: string }[]>([]);
async function edit(row?: Summary) {
  try {
    editing.value = row?.id;
    changes.value = [];
    secretNames.value = [];
    Object.assign(form.value, {
      name: "",
      baseUrl: "http://127.0.0.1:8088",
      variables: "{}",
      version: 0,
      status: "ACTIVE",
    });
    if (row) {
      const data = await api<Detail>(path + "/" + row.id);
      Object.assign(form.value, data, {
        variables: JSON.stringify(data.variables, null, 2),
      });
      secretNames.value = data.secretNames;
    }
    open.value = true;
  } catch (e) {
    notifyError(e);
  }
}
async function save() {
  saving.value = true;
  try {
    const payload = {
      name: form.value.name,
      baseUrl: form.value.baseUrl,
      variables: parseJson<Record<string, string>>(
        form.value.variables,
        "普通变量",
      ),
      secretUpdates: changes.value,
      ...(editing.value
        ? { version: form.value.version, status: form.value.status }
        : {}),
    };
    await api(
      path + (editing.value ? "/" + editing.value : ""),
      editing.value ? "PATCH" : "POST",
      payload,
    );
    changes.value = [];
    open.value = false;
    ElMessage.success("环境已保存");
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
    title="环境管理"
    description="为不同环境维护目标地址和变量，敏感值单独加密保存。"
    :writable="writable"
    create-label="新建环境"
    @create="edit()"
    @refresh="load()"
  />
  <div class="filters">
    <el-input
      v-model="name"
      aria-label="环境名称筛选"
      placeholder="搜索环境名称"
      clearable
      @keyup.enter="load(true)"
    />
    <el-select
      v-model="status"
      aria-label="环境状态筛选"
      placeholder="全部状态"
      clearable
      @change="load(true)"
      ><el-option label="活动" value="ACTIVE" /><el-option
        label="已归档"
        value="ARCHIVED"
    /></el-select>
    <el-button @click="load(true)">筛选</el-button>
  </div>
  <el-table
    v-loading="loading"
    :data="rows"
    empty-text="尚无环境，创建第一个环境以配置受测地址"
  >
    <el-table-column prop="name" label="环境名称" /><el-table-column
      label="状态"
      width="120"
      ><template #default="{ row }"
        ><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{
          row.status === "ACTIVE" ? "活动" : "已归档"
        }}</el-tag></template
      ></el-table-column
    >
    <el-table-column prop="version" label="版本" width="90" />
    <el-table-column label="操作" width="120"
      ><template #default="{ row }"
        ><el-button link type="primary" @click="edit(row)">{{
          writable ? "编辑" : "查看"
        }}</el-button></template
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
    :title="editing ? (writable ? '编辑环境' : '环境详情') : '新建环境'"
    width="720px"
    destroy-on-close
    @closed="changes = []"
  >
    <el-form label-position="top" :disabled="!writable">
      <div class="form-grid">
        <el-form-item label="名称"
          ><el-input v-model="form.name" maxlength="100"
        /></el-form-item>
        <el-form-item label="目标地址"
          ><el-input
            v-model="form.baseUrl"
            maxlength="2048"
            placeholder="https://api.example.com"
        /></el-form-item>
      </div>
      <el-form-item label="普通变量 · JSON 对象"
        ><el-input
          v-model="form.variables"
          type="textarea"
          :rows="5"
          spellcheck="false"
      /></el-form-item>
      <p class="field-hint">
        敏感变量请使用下方配置，不要放入普通变量或目标地址。
      </p>
      <div class="subheading">已设置的敏感变量</div>
      <div class="secret-tags">
        <el-tag v-for="key in secretNames" :key="key" type="info"
          >{{ key }} · 已设置</el-tag
        ><span v-if="!secretNames.length" class="muted">暂无</span>
      </div>
      <div v-for="(change, index) in changes" :key="index" class="secret-row">
        <el-input
          v-model="change.name"
          placeholder="变量名"
          aria-label="敏感变量名"
        />
        <el-select v-model="change.operation" aria-label="敏感变量操作"
          ><el-option label="设置" value="SET" /><el-option
            label="移除"
            value="REMOVE"
        /></el-select>
        <el-input
          v-if="change.operation === 'SET'"
          v-model="change.value"
          type="password"
          autocomplete="new-password"
          placeholder="输入新值"
          aria-label="敏感变量值"
        />
        <el-button @click="changes.splice(index, 1)">移除该操作</el-button>
      </div>
      <el-button
        v-if="writable"
        plain
        @click="changes.push({ name: '', operation: 'SET', value: '' })"
        >添加敏感变量操作</el-button
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
        >保存环境</el-button
      ></template
    >
  </el-dialog>
</template>
