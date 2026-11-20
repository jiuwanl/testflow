<script setup lang="ts">
defineProps<{
  title: string;
  description: string;
  writable?: boolean;
  createLabel?: string;
}>();
defineEmits<{ create: []; refresh: [] }>();
</script>
<template>
  <div class="section-heading">
    <div>
      <h2>{{ title }}</h2>
      <p>{{ description }}</p>
    </div>
    <div class="actions">
      <el-button @click="$emit('refresh')">刷新</el-button>
      <el-button v-if="writable" type="primary" @click="$emit('create')">{{
        createLabel ?? "新建"
      }}</el-button>
    </div>
  </div>
</template>
