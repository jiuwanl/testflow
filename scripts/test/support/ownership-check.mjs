import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

import { root } from '../../lib/project-paths.mjs';
const secrets = JSON.parse(await readFile(new URL('.local/dev-secrets.json', root)));
const base = process.env.TF_OWNERSHIP_URL || 'http://127.0.0.1:18080';
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw new Error('Local test URL required');
function assert(value, message) { if (!value) throw new Error(message); }
function session() {
  let csrf; const cookies = new Map();
  async function request(path, method='GET', body, expected=200) {
    const response = await fetch(base+'/api/v1'+path, {method, headers: {
      'Content-Type':'application/json', Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; '),
      ...(csrf && method !== 'GET' ? {[csrf.headerName]:csrf.token}:{}),
    }, body:body === undefined ? undefined:JSON.stringify(body), signal:AbortSignal.timeout(20000)});
    for (const cookie of response.headers.getSetCookie()) { const pair=cookie.split(';')[0], at=pair.indexOf('='); cookies.set(pair.slice(0,at),pair.slice(at+1)); }
    const payload=await response.json();
    assert(response.status === expected, `${method} ${path}: ${response.status} expected ${expected} ${payload.error?.code || ''}`);
    return payload;
  }
  async function login(username,password) { csrf=(await request('/auth/csrf')).data; await request('/auth/login','POST',{username,password}); csrf=(await request('/auth/csrf')).data; }
  return {request,login};
}
const admin=session(); await admin.login('test-admin',secrets.TestAdminPassword);
const checks=[], stamp=Date.now(), password=randomUUID();
async function user(suffix) { return (await admin.request('/admin/users','POST',{username:`gov-${suffix}-${stamp}`,password,globalAdmin:false},201)).data; }
const owner=await user('owner'), successor=await user('next'), other=await user('other');
async function project(name,ownerId) { return (await admin.request('/projects','POST',{name,ownerId},201)).data; }
const p=await project('所有权修复 · 独立进程 '+stamp,owner.id);
await admin.request(`/projects/${p.id}/cases`,'GET',undefined,404);
checks.push('non-member-admin-business-isolation');
const impact=(await admin.request(`/admin/users/${owner.id}/disable-impact`)).data;
assert(impact.projects.find(x=>x.id===p.id).requiresTransfer,'sole owner must need transfer');
await admin.request(`/admin/users/${owner.id}`,'PATCH',{version:owner.version,status:'DISABLED'},409);
checks.push('sole-owner-disable-requires-transfer');
await admin.request(`/admin/projects/${p.id}/ownership`,'PUT',{version:p.version,fromUserId:owner.id,successorUserId:successor.id,reason:'独立进程跨项目交接'});
await admin.request(`/projects/${p.id}/runs`,'GET',undefined,404);
checks.push('cross-project-admin-transfer-without-business-grant');
const ownerSession=session(); await ownerSession.login(owner.username,password);
await admin.request(`/admin/users/${owner.id}`,'PATCH',{version:owner.version,status:'DISABLED'});
await ownerSession.request('/auth/me','GET',undefined,401);
checks.push('disable-after-handover-invalidates-session');
const q=await project('所有权修复 · 共同所有者 '+stamp,other.id);
await admin.request(`/admin/projects/${q.id}/ownership`,'PUT',{version:q.version,successorUserId:successor.id,reason:'共同所有者治理'});
await admin.request(`/admin/users/${other.id}`,'PATCH',{version:other.version,status:'DISABLED'});
checks.push('co-owner-disable-without-handover');
const owner2=await user('atomic'), q1=await project('所有权修复 · 原子交接一 '+stamp,owner2.id),q2=await project('所有权修复 · 原子交接二 '+stamp,owner2.id);
const transfers=[q1,q2].map(x=>({projectId:x.id,version:x.version,successorUserId:successor.id}));
await admin.request(`/admin/users/${owner2.id}`,'PATCH',{version:owner2.version,status:'DISABLED',ownershipTransfers:transfers});
for(const x of [q1,q2]) { const d=(await admin.request(`/admin/projects/${x.id}/ownership`)).data; assert(d.project.activeOwners===1 && d.owners[0].userId===successor.id,'atomic handover failed'); }
checks.push('multi-project-handover-and-disable');
const ordinary=session(); await ordinary.login(successor.username,password);
await ordinary.request('/admin/projects','GET',undefined,403);
await ordinary.request(`/admin/users/${successor.id}/disable-impact`,'GET',undefined,403);
checks.push('ordinary-user-governance-denied');
// Dedicated browser fixtures, separate from the checks above. Secrets stay in ignored .local.
const browserOwner=await user('browser'), browserNext=await user('browser-next');
const browserProject=await project('所有权修复 · 浏览器治理 '+stamp,browserOwner.id);
const browserDisableProject=await project('所有权修复 · 浏览器禁用 '+stamp,browserOwner.id);
const adminId=(await admin.request('/auth/me')).data.id;
const browserSelfProject=await project('所有权修复 · 自身降级 '+stamp,browserNext.id);
const browserRemoveProject=await project('所有权修复 · 自身移除 '+stamp,browserNext.id);
for(const x of [browserSelfProject,browserRemoveProject]) await admin.request(`/admin/projects/${x.id}/ownership`,'PUT',{version:x.version,successorUserId:adminId,reason:'浏览器双所有者确认场景准备'});
// Viewer administrator reproduction and UI recovery fixtures live only in testflow_test.
const viewerOwner=await user('test1'), viewerSuccessor=await user('viewer-next');
const viewerProject=await project('project1 · Viewer管理员复现 '+stamp,viewerOwner.id);
const ownerClient=session(); await ownerClient.login(viewerOwner.username,password);
await ownerClient.request(`/projects/${viewerProject.id}/members/${adminId}`,'PUT',{role:'VIEWER'});
await admin.request(`/projects/${viewerProject.id}/members`,'GET',undefined,403);
await admin.request(`/admin/projects/${viewerProject.id}/ownership`);
const rejected=await admin.request(`/admin/users/${viewerOwner.id}`,'PATCH',{version:viewerOwner.version,status:'DISABLED'},409);
assert(rejected.error.code==='OWNER_TRANSFER_REQUIRED' && rejected.error.fieldErrors.some(x=>x.id===viewerProject.id && x.name===viewerProject.name),'structured blocked project details missing');
checks.push('viewer-admin-normal-members-denied-but-governance-allowed','viewer-admin-disable-error-identifies-project');
await mkdir(new URL('.local/verification/ownership/',root),{recursive:true});
await writeFile(new URL('.local/verification/ownership/browser-fixture.json',root),JSON.stringify({password,browserOwner,browserNext,browserProject,browserDisableProject,browserSelfProject,browserRemoveProject,viewerOwner,viewerSuccessor,viewerProject,adminUsername:'test-admin'}));
await writeFile(new URL('.local/verification/ownership/process.json',root),JSON.stringify({at:new Date().toISOString(),base,checks,passed:checks.length},null,2));
console.log(JSON.stringify({passed:checks.length,checks}));
