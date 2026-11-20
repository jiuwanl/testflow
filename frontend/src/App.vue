<script setup lang="ts">
import {
  computed,
  onMounted,
  onBeforeUnmount,
  reactive,
  ref,
  watch,
} from "vue";
import { ElMessage } from "element-plus";
import LoginView from "./components/LoginView.vue";
import EnvironmentView from "./components/EnvironmentView.vue";
import CaseView from "./components/CaseView.vue";
import PlanView from "./components/PlanView.vue";
import MemberView from "./components/MemberView.vue";
import PolicyView from "./components/PolicyView.vue";
import OwnershipView from "./components/OwnershipView.vue";
import UserView from "./components/UserView.vue";
import QualityView from "./components/QualityView.vue";
import RunView from "./components/RunView.vue";
import { api, ApiError, notifyError, resetCsrf } from "./api";
import type { Page, Project, User } from "./types";
const user = ref<User>(),
  projects = ref<Project[]>([]),
  projectId = ref(""),
  active = ref("cases"),
  booting = ref(true);
const project = computed(() =>
  projects.value.find((p) => p.id === projectId.value),
);
const writable = computed(
  () =>
    project.value?.status === "ACTIVE" &&
    ["OWNER", "MAINTAINER"].includes(project.value?.role ?? ""),
);
const owner = computed(() => project.value?.role === "OWNER");
const roleNames: Record<string, string> = {
  OWNER: "项目所有者",
  MAINTAINER: "项目维护者",
  VIEWER: "只读成员",
};
const titles: Record<string, string> = {
  runs: "执行与报告",
  quality: "质量趋势",
  environments: "环境管理",
  cases: "接口用例",
  plans: "测试计划",
  members: "项目成员",
  policies: "目标访问策略",
  users: "账号管理",
  ownership: "项目管理",
};
const launchRunId = ref<string>();
function inspectQualityRun(id: string) {
  launchRunId.value = id;
  launchPlanId.value = undefined;
  launchCaseId.value = undefined;
  active.value = "runs";
}
const launchCaseId = ref<string>(),
  launchPlanId = ref<string>();
function startPlan(planId: string) {
  launchRunId.value = undefined;
  launchCaseId.value = undefined;
  launchPlanId.value = planId;
  active.value = "runs";
}
function startRun(caseId: string) {
  launchRunId.value = undefined;
  launchPlanId.value = undefined;
  launchCaseId.value = caseId;
  active.value = "runs";
}
watch(projectId, () => {
  launchRunId.value = undefined;
  launchCaseId.value = undefined;
  launchPlanId.value = undefined;
});
const projectDialog = ref(false),
  savingProject = ref(false),
  editingProject = ref(false);
const projectForm = reactive({
  name: "",
  ownerId: "",
  status: "ACTIVE",
  version: 0,
});
async function loadProjects(preferred?: string) {
  const result = await api<Page<Project>>("/projects?size=100");
  projects.value = result.items;
  const selected = preferred ?? projectId.value;
  projectId.value = projects.value.some((p) => p.id === selected)
    ? selected
    : (projects.value[0]?.id ?? "");
  if (active.value === "members" && !owner.value) active.value = "cases";
}
async function authenticated(value: User) {
  user.value = value;
  try {
    await loadProjects();
  } catch (e) {
    notifyError(e);
  }
}
async function refreshProjects() {
  try {
    await loadProjects();
  } catch (e) {
    notifyError(e);
  }
}
function newProject() {
  editingProject.value = false;
  Object.assign(projectForm, {
    name: "",
    ownerId: user.value?.id ?? "",
    status: "ACTIVE",
    version: 0,
  });
  projectDialog.value = true;
}
function editProject() {
  if (!project.value) return;
  editingProject.value = true;
  Object.assign(projectForm, project.value);
  projectDialog.value = true;
}
async function saveProject() {
  savingProject.value = true;
  try {
    const value = await api<Project>(
      "/projects" + (editingProject.value ? "/" + projectId.value : ""),
      editingProject.value ? "PATCH" : "POST",
      editingProject.value
        ? {
            name: projectForm.name,
            status: projectForm.status,
            version: projectForm.version,
          }
        : { name: projectForm.name, ownerId: projectForm.ownerId },
    );
    projectDialog.value = false;
    await loadProjects(value.id);
    ElMessage.success("项目已保存");
  } catch (e) {
    notifyError(e);
  } finally {
    savingProject.value = false;
  }
}
async function logout() {
  try {
    await api("/auth/logout", "POST", {});
    resetCsrf();
    clearAccount();
  } catch (e) {
    notifyError(e);
  }
}
function clearAccount() {
  user.value = undefined;
  projects.value = [];
  projectId.value = "";
  active.value = "cases";
  resetCsrf();
}
onMounted(async () => {
  window.addEventListener("testflow:unauthenticated", clearAccount);
  try {
    await authenticated(await api<User>("/auth/me"));
  } catch (e) {
    if (!(e instanceof ApiError && e.status === 401)) notifyError(e);
  } finally {
    booting.value = false;
  }
});
onBeforeUnmount(() =>
  window.removeEventListener("testflow:unauthenticated", clearAccount),
);
</script>
<template>
  <div v-if="booting" class="boot-screen">正在打开 TestFlow 工作空间…</div>
  <LoginView v-else-if="!user" @authenticated="authenticated" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="wordmark"><span class="brand-icon">T</span> TestFlow</div>
      <span class="sidebar-caption">API 质量工作空间</span>
      <div v-if="user.globalAdmin" class="nav-label platform-label">平台管理</div>
      <button
        v-if="user.globalAdmin"
        class="nav-item"
        :class="{ selected: active === 'users' }"
        @click="active = 'users'"
      >
        <span>▦</span>账号管理
      </button>
      <button v-if="user.globalAdmin" class="nav-item" :class="{selected: active === 'ownership'}" @click="active = 'ownership'"><span>♧</span>项目管理</button>
      <div class="nav-label">项目配置</div>
      <button
        v-for="item in [
          { key: 'environments', mark: '◈', label: '环境管理' },
          { key: 'cases', mark: '⌘', label: '接口用例' },
          { key: 'runs', mark: '↗', label: '执行与报告' },
          { key: 'plans', mark: '≡', label: '测试计划' },
          { key: 'quality', mark: '◷', label: '质量趋势' },
        ]"
        :key="item.key"
        class="nav-item"
        :class="{ selected: active === item.key }"
        @click="active = item.key"
      >
        <span>{{ item.mark }}</span
        >{{ item.label }}
      </button>
      <div class="nav-label">协作与安全</div>
      <button
        v-if="owner"
        class="nav-item"
        :class="{ selected: active === 'members' }"
        @click="active = 'members'"
      >
        <span>◎</span>项目成员
      </button>
      <button
        class="nav-item"
        :class="{ selected: active === 'policies' }"
        @click="active = 'policies'"
      >
        <span>◇</span>目标访问策略
      </button>
      <div class="sidebar-footer">
        <span class="status-dot"></span> 独立的质量工作空间<br /><small
          >定义清晰，回归有据。</small
        >
      </div>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <div class="breadcrumb">
          工作空间 <span>/</span> {{ titles[active] }}
        </div>
        <div class="account-menu">
          <span class="avatar">{{
            user.username.slice(0, 1).toUpperCase()
          }}</span
          ><span>{{ user.username }}</span
          ><el-tag v-if="user.globalAdmin" size="small" type="info"
            >管理员</el-tag
          ><el-button link @click="logout">退出登录</el-button>
        </div>
      </header>
      <main>
        <section v-if="active !== 'runs'" class="workspace-heading">
          <div>
            <span class="eyebrow">PROJECT WORKSPACE</span>
            <h1>把每一次测试，变成可靠的记录。</h1>
            <p>从清晰的接口定义出发，观察执行，也理解结果。</p>
          </div>
          <el-button v-if="user.globalAdmin" @click="newProject"
            >新建项目</el-button
          >
        </section>
        <div v-if="!['users', 'ownership'].includes(active)" class="project-strip">
          <div class="project-symbol">P</div>
          <div class="project-picker">
            <label>当前项目</label
            ><el-select
              v-model="projectId"
              aria-label="当前项目"
              placeholder="选择项目"
              @change="
                active === 'members' && !owner ? (active = 'cases') : null
              "
              ><el-option
                v-for="p in projects"
                :key="p.id"
                :label="p.name + (p.status === 'ARCHIVED' ? ' · 已归档' : '')"
                :value="p.id"
            /></el-select>
          </div>
          <el-tag
            v-if="project"
            :type="project.role === 'VIEWER' ? 'info' : 'primary'"
            >{{ roleNames[project.role ?? ""] }}</el-tag
          ><el-tag v-if="project?.status === 'ARCHIVED'" type="warning"
            >已归档</el-tag
          >
          <div class="project-actions">
            <el-button link @click="refreshProjects">刷新项目</el-button
            ><el-button v-if="owner" link type="primary" @click="editProject"
              >项目设置</el-button
            >
          </div>
        </div>
        <div
          v-if="project?.role === 'VIEWER' && !['users', 'ownership'].includes(active)"
          class="read-only-note"
        >
          你以只读成员身份访问此项目，可查看配置，不能修改。
        </div>
        <section
          class="content-card"
          :class="{ 'execution-card': active === 'runs' }"
        >
          <UserView v-if="active === 'users' && user.globalAdmin" @changed="refreshProjects" />
          <OwnershipView v-else-if="active === 'ownership' && user.globalAdmin" @changed="refreshProjects" />
          <template v-else-if="project">
            <EnvironmentView
              v-if="active === 'environments'"
              :key="projectId + 'env'"
              :project-id="projectId"
              :writable="writable"
            />
            <QualityView
              v-if="active === 'quality'"
              :key="projectId + 'quality'"
              :project-id="projectId"
              :writable="writable"
              @run="inspectQualityRun"
            />
            <RunView
              v-if="active === 'runs'"
              :key="projectId + 'runs'"
              :project-id="projectId"
              :writable="writable"
              :case-id="launchCaseId"
              :plan-id="launchPlanId"
              :run-id="launchRunId"
            />
            <CaseView
              v-if="active === 'cases'"
              :key="projectId + 'case'"
              :project-id="projectId"
              :writable="writable"
              @run="startRun"
            />
            <PlanView
              v-if="active === 'plans'"
              :key="projectId + 'plan'"
              @run="startPlan"
              :project-id="projectId"
              :writable="writable"
            />
            <MemberView
              v-if="active === 'members' && owner"
              :key="projectId + 'member'"
              :project-id="projectId"
              :current-user-id="user.id"
              :writable="project.status === 'ACTIVE'"
              @changed="refreshProjects"
            />
            <PolicyView
              v-if="active === 'policies'"
              :key="projectId + 'policy'"
              :project-id="projectId"
              :admin="user.globalAdmin"
            />
          </template>
          <el-empty v-else description="尚无可访问的项目"
            ><el-button
              v-if="user.globalAdmin"
              type="primary"
              @click="newProject"
              >创建第一个项目</el-button
            >
            <p v-else class="muted">请联系项目所有者添加成员权限。</p></el-empty
          >
        </section>
      </main>
    </div>
    <el-dialog
      v-model="projectDialog"
      :title="editingProject ? '项目设置' : '新建项目'"
      width="500px"
      destroy-on-close
    >
      <el-form label-position="top"
        ><el-form-item label="项目名称"
          ><el-input v-model="projectForm.name" maxlength="100"
        /></el-form-item>
        <el-form-item v-if="!editingProject" label="初始 OWNER 用户 ID"
          ><el-input v-model="projectForm.ownerId" />
          <p class="field-hint">
            默认使用你的用户 ID。项目仅对已加入的成员可见。
          </p></el-form-item
        >
        <el-form-item v-if="editingProject" label="状态"
          ><el-select v-model="projectForm.status"
            ><el-option label="活动" value="ACTIVE" /><el-option
              label="已归档"
              value="ARCHIVED" /></el-select
        ></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="projectDialog = false">取消</el-button
        ><el-button type="primary" :loading="savingProject" @click="saveProject"
          >保存项目</el-button
        ></template
      >
    </el-dialog>
  </div>
</template>
