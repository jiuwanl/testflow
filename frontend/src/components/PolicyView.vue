<script setup lang="ts">
import { reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import CollectionToolbar from "./CollectionToolbar.vue";
import { api, notifyError } from "../api";
import { useCollection } from "../useCollection";
import type { Policy } from "../types";
const props = defineProps<{ projectId: string; admin: boolean }>();
const path = "/projects/" + props.projectId + "/target-policies";
const { rows, page, total, loading, load } = useCollection<Policy>(path);
const open = ref(false),
  saving = ref(false);
const form = reactive({
  scheme: "https",
  host: "",
  port: 443,
  addressScope: "PUBLIC",
});
const scopeNames: Record<string, string> = {
  PUBLIC: "公网",
  PRIVATE: "私网",
  LOOPBACK: "本机回环",
};
async function save() {
  saving.value = true;
  try {
    await api(path, "POST", form);
    open.value = false;
    ElMessage.success("目标授权已保存");
    await load();
  } catch (e) {
    notifyError(e);
  } finally {
    saving.value = false;
  }
}
async function toggle(row: Policy) {
  try {
    await api(path + "/" + row.id, "PATCH", {
      version: row.version,
      enabled: !row.enabled,
    });
    await load();
  } catch (e) {
    notifyError(e);
  }
}
</script>
<template>
  <CollectionToolbar
    title="目标访问策略"
    description="明确批准的协议、主机和端口，避免将测试权限扩大为任意网络访问。"
    :writable="admin"
    create-label="授权目标"
    @create="
      Object.assign(form, {
        scheme: 'https',
        host: '',
        port: 443,
        addressScope: 'PUBLIC',
      });
      open = true;
    "
    @refresh="load()"
  />
  <el-alert
    class="policy-note"
    type="info"
    :closable="false"
    title="本机和私网目标也需要管理员明确授权。保存策略不会发送测试请求。"
  />
  <el-table v-loading="loading" :data="rows" empty-text="尚未授权目标">
    <el-table-column label="目标"
      ><template #default="{ row }"
        ><span class="mono"
          >{{ row.scheme }}://{{ row.host }}:{{ row.port }}</span
        ></template
      ></el-table-column
    >
    <el-table-column label="地址范围" width="130"
      ><template #default="{ row }">{{
        scopeNames[row.addressScope]
      }}</template></el-table-column
    >
    <el-table-column label="状态" width="100"
      ><template #default="{ row }"
        ><el-tag :type="row.enabled ? 'success' : 'info'">{{
          row.enabled ? "启用" : "禁用"
        }}</el-tag></template
      ></el-table-column
    >
    <el-table-column v-if="admin" label="操作" width="120"
      ><template #default="{ row }"
        ><el-button
          link
          :type="row.enabled ? 'danger' : 'primary'"
          @click="toggle(row)"
          >{{ row.enabled ? "禁用" : "启用" }}</el-button
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
  <el-dialog v-model="open" title="授权目标" width="540px" destroy-on-close>
    <el-form label-position="top">
      <div class="form-grid">
        <el-form-item label="协议"
          ><el-select v-model="form.scheme"
            ><el-option label="HTTPS" value="https" /><el-option
              label="HTTP"
              value="http" /></el-select></el-form-item
        ><el-form-item label="端口"
          ><el-input-number v-model="form.port" :min="1" :max="65535"
        /></el-form-item>
      </div>
      <el-form-item label="精确主机"
        ><el-input v-model="form.host" placeholder="api.example.com 或精确 IP"
      /></el-form-item>
      <el-form-item label="地址范围"
        ><el-select v-model="form.addressScope"
          ><el-option
            v-for="(label, value) in scopeNames"
            :key="value"
            :label="label"
            :value="value" /></el-select
      ></el-form-item>
      <p class="field-hint">
        不能使用通配主机。系统禁止的元数据和管理目标不会因授权而放开。
      </p>
    </el-form>
    <template #footer
      ><el-button @click="open = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="save"
        >保存授权</el-button
      ></template
    >
  </el-dialog>
</template>
