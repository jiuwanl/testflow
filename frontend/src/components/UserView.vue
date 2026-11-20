<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import CollectionToolbar from "./CollectionToolbar.vue";
import { api, ApiError, notifyError } from "../api";
import { useCollection } from "../useCollection";
import ActiveUserSelect from "./ActiveUserSelect.vue";
import type { User, DisableImpact } from "../types";

type ImpactProject = DisableImpact["projects"][number];
const emit = defineEmits<{ changed: [] }>();
const { rows, page, total, loading, load } = useCollection<User>("/admin/users");
const open = ref(false), saving = ref(false), editing = ref<string>();
const impact = ref<DisableImpact>();
const impactLoading = ref(false), impactError = ref("");
const reviewMessage = ref("");
const reportedProjects = ref<ImpactProject[]>([]);
const successors = reactive<Record<string, string>>({});
const originalStatus = ref("ACTIVE");
const form = reactive({ username: "", password: "", globalAdmin: false, status: "ACTIVE", version: 0 });
const disabling = computed(() => open.value && !!editing.value && originalStatus.value === "ACTIVE" && form.status === "DISABLED");
const missingSuccessors = computed(() => impact.value?.projects.filter(p => p.requiresTransfer && !successors[p.id]) ?? []);
const cannotSave = computed(() => saving.value || (disabling.value &&
  (impactLoading.value || !!impactError.value || !impact.value || missingSuccessors.value.length > 0)));
let previewGeneration = 0;

function isImpactProject(value: unknown): value is ImpactProject {
  if (!value || typeof value !== "object") return false;
  const p = value as Record<string, unknown>;
  return typeof p.id === "string" && /^[1-9][0-9]*$/.test(p.id) && typeof p.name === "string" &&
    typeof p.version === "number" && Number.isSafeInteger(p.version) && p.version >= 0 &&
    typeof p.remainingActiveOwners === "number" && typeof p.requiresTransfer === "boolean";
}

async function refreshImpact() {
  if (!disabling.value || !editing.value) return;
  const target = editing.value, generation = ++previewGeneration;
  impact.value = undefined;
  impactLoading.value = true;
  impactError.value = "";
  try {
    const result = await api<DisableImpact>("/admin/users/" + target + "/disable-impact");
    if (generation !== previewGeneration || !disabling.value || editing.value !== target) return;
    if (result.userId !== target || !Array.isArray(result.projects) || !result.projects.every(isImpactProject))
      throw new Error("禁用影响数据格式不正确，请重新加载");
    impact.value = result;
    form.version = result.version;
    // Keep explicit selections only where a handover is still required. Never choose a replacement automatically.
    for (const id of Object.keys(successors)) {
      if (!result.projects.some(p => p.id === id && p.requiresTransfer)) delete successors[id];
    }
  } catch (e) {
    if (generation === previewGeneration) impactError.value = e instanceof Error ? e.message : "无法加载禁用影响";
  } finally {
    if (generation === previewGeneration) impactLoading.value = false;
  }
}

watch(disabling, (active) => {
  ++previewGeneration;
  impact.value = undefined;
  impactLoading.value = false;
  impactError.value = "";
  reviewMessage.value = "";
  reportedProjects.value = [];
  if (active) void refreshImpact();
}, { flush: "sync" });
onBeforeUnmount(() => { ++previewGeneration; });

function edit(row?: User) {
  editing.value = row?.id;
  originalStatus.value = row?.status ?? "ACTIVE";
  for (const key of Object.keys(successors)) delete successors[key];
  Object.assign(form, { username: "", password: "", globalAdmin: false, status: "ACTIVE", version: 0 }, row ?? {});
  open.value = true;
}

async function save() {
  if (cannotSave.value) return;
  saving.value = true;
  try {
    const isDisabling = disabling.value;
    const preview = impact.value;
    if (isDisabling) {
      await ElMessageBox.confirm(
        "将一次提交所有权交接与账号禁用，并使其旧会话失效。" +
        (preview!.projects.length ? preview!.projects.map(p => p.name + "（ID " + p.id + "）" +
          (successors[p.id] ? " → 继任者 ID " + successors[p.id] : " · 保留其他有效 OWNER")).join("；") : "该账号没有活动项目所有权需要交接。"),
        "确认交接与禁用", { type: "warning" });
      // A refresh or status change while the confirmation was open invalidates this submission.
      if (!disabling.value || preview !== impact.value || impactLoading.value || impactError.value) return;
    }
    await api("/admin/users" + (editing.value ? "/" + editing.value : ""), editing.value ? "PATCH" : "POST",
      editing.value ? {
        version: form.version, status: form.status,
        ...(isDisabling ? { ownershipTransfers: preview!.projects.filter(p => p.requiresTransfer && successors[p.id])
          .map(p => ({ projectId: p.id, version: p.version, successorUserId: successors[p.id] })) } : {}),
        ...(form.password ? { password: form.password } : {}),
      } : { username: form.username, password: form.password, globalAdmin: form.globalAdmin });
    form.password = "";
    open.value = false;
    ElMessage.success("账号已保存");
    await load();
    emit("changed");
  } catch (e) {
    if (e === "cancel" || e === "close") return;
    if (disabling.value && e instanceof ApiError && e.status === 409) {
      reportedProjects.value = e.code === "OWNER_TRANSFER_REQUIRED" ? e.fieldErrors.filter(isImpactProject) : [];
      reviewMessage.value = reportedProjects.value.length
        ? "禁用未完成：以下项目需要交接。已保留已有选择，请核对刷新后的影响并重新确认。"
        : "禁用未完成：" + e.message + "。请核对刷新后的影响并重新确认。";
      await refreshImpact();
    } else {
      notifyError(e);
      if (disabling.value) await refreshImpact();
    }
  } finally { saving.value = false; }
}
</script>
<template>
  <CollectionToolbar
    title="账号管理"
    description="分配账号与平台管理权限，项目角色在各项目内单独维护。"
    writable
    create-label="创建账号"
    @create="edit()"
    @refresh="load()"
  />
  <el-table v-loading="loading" :data="rows" empty-text="暂无账号">
    <el-table-column prop="username" label="账号" /><el-table-column
      prop="id"
      label="用户 ID"
      width="100"
    />
    <el-table-column label="平台身份" width="150"
      ><template #default="{ row }"
        ><el-tag :type="row.globalAdmin ? 'warning' : 'info'">{{
          row.globalAdmin ? "系统管理员" : "普通用户"
        }}</el-tag></template
      ></el-table-column
    >
    <el-table-column label="状态" width="100"
      ><template #default="{ row }">{{
        row.status === "ACTIVE" ? "启用" : "禁用"
      }}</template></el-table-column
    >
    <el-table-column label="操作" width="120"
      ><template #default="{ row }"
        ><el-button link type="primary" @click="edit(row)"
          >管理</el-button
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
    :title="editing ? '管理账号' : '创建账号'"
    width="650px"
    destroy-on-close
    @closed="form.password = ''"
  >
    <el-form label-position="top">
      <el-form-item label="账号"
        ><el-input
          v-model="form.username"
          :disabled="!!editing"
          autocomplete="off"
          maxlength="32"
      /></el-form-item>
      <el-form-item :label="editing ? '重置密码 · 留空保持不变' : '初始密码'"
        ><el-input
          v-model="form.password"
          type="password"
          autocomplete="new-password"
          maxlength="72"
      /></el-form-item>
      <p class="field-hint">
        密码至少 12 字符，UTF-8 编码不超过 72
        字节。修改密码或禁用账号会使其现有登录失效。
      </p>
      <el-checkbox v-if="!editing" v-model="form.globalAdmin"
        >授予系统管理员身份</el-checkbox
      >
      <el-form-item v-if="editing" label="状态"
        ><el-select v-model="form.status"
          ><el-option label="启用" value="ACTIVE" /><el-option
            label="禁用"
            value="DISABLED" /></el-select
      ></el-form-item>
      <section v-if="disabling" class="disable-impact" aria-label="禁用影响">
        <div class="disable-impact-heading">
          <h3>禁用影响与所有权交接</h3>
          <el-button :loading="impactLoading" :disabled="saving" @click="refreshImpact">重新加载影响</el-button>
        </div>
        <p class="field-hint">按项目列出禁用后的有效 OWNER 数量。平台管理员可直接指定继任者，无需原 OWNER 配合。</p>
        <el-alert v-if="reviewMessage" :title="reviewMessage" type="warning" :closable="false" show-icon />
        <ul v-if="reportedProjects.length" class="impact-conflict-projects">
          <li v-for="p in reportedProjects" :key="p.id">{{ p.name }}（ID {{ p.id }}）</li>
        </ul>
        <p v-if="impactLoading" role="status">正在加载禁用影响，请稍候…</p>
        <el-alert v-else-if="impactError" title="无法加载禁用影响，已禁止提交" type="error" :closable="false" show-icon>
          {{ impactError }}。请点击“重新加载影响”后再确认。
        </el-alert>
        <template v-if="impact">
          <article v-for="p in impact.projects" :key="p.id" class="impact-project" :aria-label="'项目 ' + p.id">
            <div class="impact-project-title"><strong>{{ p.name }}</strong><el-tag :type="p.requiresTransfer ? 'warning' : 'success'">{{ p.requiresTransfer ? '必须交接' : '可直接禁用' }}</el-tag></div>
            <p class="muted">项目 ID {{ p.id }} · 禁用后有效 OWNER {{ p.remainingActiveOwners }} 人</p>
            <template v-if="p.requiresTransfer">
              <p class="field-hint">禁用后将没有有效所有者，请选择启用中的继任者。</p>
              <ActiveUserSelect :model-value="successors[p.id] ?? ''" @update:model-value="successors[p.id] = $event" :exclude-id="editing" />
            </template>
          </article>
          <p v-if="!impact.projects.length">没有需要交接的活动项目。</p>
          <p v-if="missingSuccessors.length" class="field-hint">仍有 {{ missingSuccessors.length }} 个项目需要选择继任者，完成后才能禁用。</p>
          <p class="field-hint">在此选择的交接与禁用一次提交、同事务完成。继任者成为 OWNER，交出的原 OWNER 降为 MAINTAINER。未交接项目保留原角色，重新启用后恢复权限。归档项目不阻止禁用。</p>
        </template>
      </section>
    </el-form>
    <template #footer
      ><el-button @click="open = false">取消</el-button
      ><el-button type="primary" :loading="saving" :disabled="cannotSave" @click="save"
        >{{ disabling ? "确认禁用账号" : "保存账号" }}</el-button
      ></template
    >
  </el-dialog>
</template>
