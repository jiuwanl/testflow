<script setup lang="ts">
import { ref, reactive } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, ApiError, notifyError } from '../api';
import { useCollection } from '../useCollection';
import CollectionToolbar from './CollectionToolbar.vue';
import ActiveUserSelect from './ActiveUserSelect.vue';
import type { OwnershipDetail, GovernanceProject } from '../types';
const emit = defineEmits<{ changed: [] }>();
const { rows, page, total, loading, load } = useCollection<GovernanceProject>('/admin/projects');
const detail = ref<OwnershipDetail>(), open = ref(false), saving = ref(false);
const form = reactive({ successorUserId: '', fromUserId: '', reason: '' });
async function edit(p: GovernanceProject) {
  try {
    detail.value = await api<OwnershipDetail>('/admin/projects/' + p.id + '/ownership');
    Object.assign(form, { successorUserId: '', fromUserId: '', reason: '' }); open.value = true;
  } catch(e) { notifyError(e); }
}
async function save() {
  if (!detail.value || !form.successorUserId || !form.reason.trim()) { ElMessage.warning('请选择继任者并填写治理原因'); return; }
  if (form.fromUserId === form.successorUserId) { ElMessage.warning("继任者不能是原 OWNER 本人"); return; }
  saving.value = true;
  try {
    await ElMessageBox.confirm('将调整此项目所有权。指定原 OWNER 时，其角色降为 MAINTAINER；此操作不会授予你项目业务访问权限。', '确认所有权治理', { type: 'warning' });
    const id = detail.value.project.id;
    await api('/admin/projects/' + id + '/ownership', 'PUT', {
      version: detail.value.project.version, successorUserId: form.successorUserId,
      ...(form.fromUserId ? { fromUserId: form.fromUserId } : {}), reason: form.reason.trim(),
    });
    open.value = false; ElMessage.success('项目所有权已更新'); await load(); emit('changed');
  } catch(e) {
    if (e !== 'cancel' && e !== 'close') {
      notifyError(e);
      if (e instanceof ApiError && e.status === 409 && detail.value) {
        try { detail.value = await api<OwnershipDetail>('/admin/projects/' + detail.value.project.id + '/ownership'); }
        catch(refreshError) { notifyError(refreshError); }
      }
    }
  } finally { saving.value = false; }
}
</script>
<template>
  <CollectionToolbar title="项目管理" description="查看全平台项目摘要和管理所有权。此治理权限不授予项目业务访问权限。" :writable="false" @refresh="load()" />
  <el-table :data="rows" v-loading="loading" empty-text="暂无项目">
    <el-table-column prop="name" label="项目" min-width="180" />
    <el-table-column prop="id" label="ID" width="90" />
    <el-table-column label="状态" width="110"><template #default="{ row }">{{ row.status === 'ACTIVE' ? '活动' : '已归档' }}</template></el-table-column>
    <el-table-column prop="activeOwners" label="有效 OWNER" width="130" />
    <el-table-column label="治理" width="130"><template #default="{ row }"><el-button link type="primary" @click="edit(row)">交接所有权</el-button></template></el-table-column>
  </el-table>
  <el-pagination class="pagination" v-model:current-page="page" :total="total" :page-size="20" layout="total, prev, pager, next" @current-change="load()" />
  <el-dialog v-model="open" title="项目所有权治理" width="600px" destroy-on-close>
    <template v-if="detail">
      <p>{{ detail.project.name }} · ID {{ detail.project.id }} · 有效 OWNER {{ detail.project.activeOwners }} 人</p>
      <el-table :data="detail.owners" empty-text="暂无 OWNER，请指定有效继任者">
        <el-table-column prop="username" label="当前 OWNER" /><el-table-column label="账号状态"><template #default="{row}">{{ row.status === 'ACTIVE' ? '启用' : '禁用' }}</template></el-table-column>
      </el-table>
      <el-form label-position="top" style="margin-top:20px">
        <el-form-item label="交出的 OWNER · 留空仅增加所有者"><el-select v-model="form.fromUserId" clearable aria-label="交出的 OWNER"><el-option v-for="u in detail.owners" :key="u.userId" :value="u.userId" :label="u.username" /></el-select></el-form-item>
        <el-form-item label="有效继任者"><ActiveUserSelect v-model="form.successorUserId" :exclude-id="form.fromUserId" /></el-form-item>
        <el-form-item label="治理原因"><el-input v-model="form.reason" type="textarea" maxlength="500" show-word-limit aria-label="治理原因" /></el-form-item>
      </el-form>
      <p class="field-hint">此处单独保存所有权交接，不会禁用账号。继任者若不是成员，将加入为 OWNER；无需原 OWNER 配合，已归档项目不会自动激活。</p>
    </template>
    <template #footer><el-button @click="open = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">确认交接</el-button></template>
  </el-dialog>
</template>
