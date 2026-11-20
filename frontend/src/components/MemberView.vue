<script setup lang="ts">
import { reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import CollectionToolbar from "./CollectionToolbar.vue";
import { api, notifyError } from "../api";
import { useCollection } from "../useCollection";
import type { Member } from "../types";
const props = defineProps<{
  projectId: string;
  writable: boolean;
  currentUserId: string;
}>();
const emit = defineEmits<{ changed: [] }>();
const path = "/projects/" + props.projectId + "/members";
const { rows, page, total, loading, load } = useCollection<Member>(path);
const open = ref(false),
  saving = ref(false),
  editing = ref(false);
const form = reactive({
  userId: "",
  role: "VIEWER",
  version: undefined as number | undefined,
});
function edit(row?: Member) {
  editing.value = !!row;
  Object.assign(form, {
    userId: row?.userId ?? "",
    role: row?.role ?? "VIEWER",
    version: row?.version,
  });
  open.value = true;
}
async function save() {
  saving.value = true;
  try {
    if (!/^[1-9][0-9]*$/.test(form.userId))
      throw new Error("请输入有效用户 ID");
    if (form.userId === props.currentUserId && form.role !== "OWNER") {
      await ElMessageBox.confirm(form.role === "VIEWER" ? "你将失去成员管理与写入权限，仅能查看配置和报告。是否继续？" : "你将失去成员管理权限，仍可维护配置和执行测试。是否继续？", "确认降低自身权限", {type: "warning"});
    }
    await api(path + "/" + form.userId, "PUT", {
      role: form.role,
      ...(form.version !== undefined ? { version: form.version } : {}),
    });
    open.value = false;
    ElMessage.success("成员权限已保存");
    if (form.userId !== props.currentUserId || form.role === "OWNER")
      await load();
    emit("changed");
  } catch (e) {
    if (e !== "cancel" && e !== "close") notifyError(e);
  } finally {
    saving.value = false;
  }
}
async function remove(row: Member) {
  try {
    await ElMessageBox.confirm(
      row.userId === props.currentUserId ? "你将失去本项目的访问权限，需要其他 OWNER 或系统管理员重新授权。是否继续？" : "移除后，该用户将无法再访问本项目。",
      "移除 " + row.username,
      { type: "warning" },
    );
    await api(path + "/" + row.userId, "DELETE");
    ElMessage.success("成员已移除");
    if (row.userId !== props.currentUserId) await load();
    emit("changed");
  } catch (e) {
    if (e !== "cancel" && e !== "close") notifyError(e);
  }
}
</script>
<template>
  <CollectionToolbar
    title="项目成员"
    description="用固定角色明确协作边界，保留至少一名有效的项目所有者。"
    :writable="writable"
    create-label="添加成员"
    @create="edit()"
    @refresh="load()"
  />
  <el-table v-loading="loading" :data="rows" empty-text="暂无成员">
    <el-table-column prop="username" label="账号" /><el-table-column
      prop="userId"
      label="用户 ID"
      width="100"
    />
    <el-table-column label="角色" width="180"
      ><template #default="{ row }"
        ><el-tag>{{ row.role }}</el-tag></template
      ></el-table-column
    >
    <el-table-column label="账号状态" width="120"
      ><template #default="{ row }">{{
        row.status === "ACTIVE" ? "启用" : "禁用"
      }}</template></el-table-column
    >
    <el-table-column v-if="writable" label="操作" width="170"
      ><template #default="{ row }"
        ><el-button link type="primary" @click="edit(row)">修改角色</el-button
        ><el-button link type="danger" @click="remove(row)"
          >移除</el-button
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
    :title="editing ? '修改成员角色' : '添加成员'"
    width="490px"
    destroy-on-close
  >
    <el-form label-position="top">
      <el-form-item label="用户 ID"
        ><el-input
          v-model="form.userId"
          :disabled="editing"
          placeholder="由管理员提供的用户 ID"
      /></el-form-item>
      <el-form-item label="项目角色"
        ><el-select v-model="form.role"
          ><el-option
            label="VIEWER · 查看配置与报告"
            value="VIEWER" /><el-option
            label="MAINTAINER · 维护配置"
            value="MAINTAINER" /><el-option
            label="OWNER · 管理项目与成员"
            value="OWNER" /></el-select
      ></el-form-item>
      <p class="field-hint">
        平台账号与项目成员相互独立。先创建账号，再将其加入需要协作的项目。
      </p>
    </el-form>
    <template #footer
      ><el-button @click="open = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="save"
        >保存成员</el-button
      ></template
    >
  </el-dialog>
</template>
