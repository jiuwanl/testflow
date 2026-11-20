<script setup lang="ts">
import { ref } from "vue";
import { api, notifyError } from "../api";
import type { Page, User } from "../types";
const props = defineProps<{ modelValue: string; excludeId?: string }>();
const emit = defineEmits<{ 'update:modelValue': [value: string] }>();
const users = ref<User[]>([]), loading = ref(false), more = ref(true), page = ref(0);
async function load() {
  loading.value = true;
  try {
    const result = await api<Page<User>>('/admin/users?page=' + (page.value + 1) + '&size=100');
    page.value++;
    users.value.push(...result.items);
    more.value = page.value * 100 < result.total;
  } catch(e) { notifyError(e); } finally { loading.value = false; }
}
load();
</script>
<template>
  <el-select :model-value="modelValue" filterable :loading="loading" placeholder="选择启用中的继任者" aria-label="继任者" @update:model-value="emit('update:modelValue', $event)">
    <el-option v-for="u in users.filter(u => u.status === 'ACTIVE' && u.id !== props.excludeId)" :key="u.id" :label="u.username + ' · ' + u.id" :value="u.id" />
    <template v-if="more" #footer><el-button :loading="loading" @click="load">加载更多账号</el-button></template>
  </el-select>
</template>
