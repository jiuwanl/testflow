<script setup lang="ts">
import { reactive, ref } from "vue";
import { api, resetCsrf, notifyError } from "../api";
import type { User } from "../types";
const emit = defineEmits<{ authenticated: [user: User] }>();
const form = reactive({ username: "", password: "" });
const loading = ref(false);
async function login() {
  if (!form.username.trim() || !form.password) return;
  loading.value = true;
  try {
    const user = await api<User>("/auth/login", "POST", form);
    resetCsrf();
    emit("authenticated", user);
  } catch (e) {
    notifyError(e);
  } finally {
    form.password = "";
    loading.value = false;
  }
}
</script>
<template>
  <div class="login-page">
    <div class="login-story">
      <div class="wordmark"><span class="brand-icon">T</span> TestFlow</div>
      <div>
        <span class="eyebrow">API QUALITY WORKSPACE</span>
        <h1>让每一次回归，<br />都有迹可循。</h1>
        <p>
          在一个工作空间中整理环境、管理接口用例，<br />为可靠的自动化回归建立清晰的基础。
        </p>
      </div>
      <span class="login-footer">独立设计 · 可追溯 · 有边界的执行</span>
    </div>
    <div class="login-card">
      <span class="eyebrow">欢迎回来</span>
      <h2>登录工作空间</h2>
      <p class="muted">使用管理员分配的账号继续</p>
      <el-form label-position="top" @submit.prevent="login">
        <el-form-item label="账号"
          ><el-input
            v-model="form.username"
            aria-label="账号"
            autocomplete="username"
            maxlength="32"
            size="large"
            placeholder="请输入账号"
        /></el-form-item>
        <el-form-item label="密码"
          ><el-input
            v-model="form.password"
            aria-label="密码"
            type="password"
            autocomplete="current-password"
            maxlength="72"
            size="large"
            placeholder="请输入密码"
        /></el-form-item>
        <el-button
          class="login-submit"
          type="primary"
          size="large"
          native-type="submit"
          :loading="loading"
          :disabled="!form.username.trim() || !form.password"
          >登录</el-button
        >
      </el-form>
      <div class="login-note">账号由平台管理员创建，不提供公开注册。</div>
    </div>
  </div>
</template>
