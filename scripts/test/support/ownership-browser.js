async (page) => {
  // Test-OwnershipBrowser.ps1 substitutes this only in an ignored, temporary copy.
  const fixture = /* TEST_FIXTURE */ null;
  const checks = [];
  const assert = (value, message) => { if (!value) throw new Error(message); };
  const login = async (target, username, password) => {
    await target.goto('http://127.0.0.1:5173/');
    await target.locator('.login-card, .account-menu').waitFor();
    if (await target.getByRole('button', { name: '退出登录', exact: true }).isVisible()) await target.getByRole('button', { name: '退出登录', exact: true }).click();
    await target.getByRole('textbox', { name: '账号', exact: true }).fill(username);
    await target.getByRole('textbox', { name: '密码', exact: true }).fill(password);
    await target.getByRole('button', { name: '登录', exact: true }).click();
    await target.getByRole('button', { name: '退出登录', exact: true }).waitFor();
  };
  const choose = async (target, box, label) => {
    const option = target.getByRole('option', { name: label, exact: true });
    if (!await option.isVisible()) await box.press("ArrowDown");
    await option.click();
  };
  const selectProject = async (target, name) => {
    await choose(target, target.getByRole('combobox', { name: '当前项目', exact: true }), name);
  };
  if (fixture.phase === 'governance') {
    await login(page, fixture.adminUsername, fixture.adminPassword);
    await page.getByRole('button', { name: /项目管理/ }).click();
    await page.getByRole('row').filter({ hasText: fixture.browserProject.name })
      .getByRole('button', { name: '交接所有权' }).click();
    const dialog = page.getByRole('dialog', { name: '项目所有权治理', exact: true });
    await choose(page, dialog.getByRole('combobox', { name: '交出的 OWNER' }), fixture.browserOwner.username);
    const successor = dialog.getByRole('combobox', { name: '继任者', exact: true });
    await successor.fill(fixture.browserNext.username);
    await choose(page, successor, fixture.browserNext.username + ' · ' + fixture.browserNext.id);
    await dialog.getByRole('textbox', { name: '治理原因' }).fill('浏览器验证：管理员不加入项目，独立完成所有权交接');
    await page.screenshot({ path: 'output/playwright/ownership-governance.png', fullPage: true });
    await dialog.getByRole('button', { name: '确认交接', exact: true }).click();
    await page.getByRole('button', { name: '确定', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
    const response = await page.request.get('http://127.0.0.1:5173/api/v1/admin/projects/' + fixture.browserProject.id + '/ownership');
    const detail = (await response.json()).data;
    assert(detail.project.activeOwners === 1 && detail.owners[0].userId === fixture.browserNext.id, 'governance did not transfer ownership');
    const forbidden = await page.request.get('http://127.0.0.1:5173/api/v1/projects/' + fixture.browserProject.id + '/cases');
    assert(forbidden.status() === 404, 'governance granted implicit business access');
    checks.push('admin-non-member-transfer-through-ui', 'business-permission-remains-isolated');
  }
  if (fixture.phase === 'disable') {
    const oldContext = await page.context().browser().newContext();
    try {
      const oldPage = await oldContext.newPage();
      await login(oldPage, fixture.browserOwner.username, fixture.password);
      await page.getByRole('button', { name: /账号管理/ }).click();
      await page.getByRole('row').filter({ hasText: fixture.browserOwner.username })
        .getByRole('button', { name: '管理', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: '管理账号', exact: true });
      await choose(page, dialog.getByRole('combobox'), '禁用');
      await dialog.getByText(fixture.browserDisableProject.name, { exact: true }).waitFor();
      await dialog.getByRole('combobox', { name: '继任者', exact: true }).fill(fixture.browserNext.username);
      await page.getByRole('option', { name: fixture.browserNext.username + ' · ' + fixture.browserNext.id, exact: true }).click();
      await page.screenshot({ path: 'output/playwright/ownership-disable.png', fullPage: true });
      let writes = 0;
      page.on('request', request => { if (request.method() === 'PATCH' && request.url().includes('/admin/users/')) writes++; });
      await dialog.getByRole('button', { name: '确认禁用账号', exact: true }).click();
      await page.getByRole('dialog', { name: '确认交接与禁用', exact: true }).getByRole('button', { name: '取消', exact: true }).click();
      assert(writes === 0, 'cancel sent a disable write');
      await dialog.getByRole('button', { name: '确认禁用账号', exact: true }).click();
      await page.getByRole('button', { name: '确定', exact: true }).click();
      await dialog.waitFor({ state: 'hidden' });
      assert(writes === 1, 'disable was not one atomic HTTP mutation');
      assert((await oldPage.request.get('http://127.0.0.1:5173/api/v1/auth/me')).status() === 401, 'old session survived disable');
      await oldPage.reload();
      await oldPage.getByRole('button', { name: '登录', exact: true }).waitFor();
      const response = await page.request.get('http://127.0.0.1:5173/api/v1/admin/projects/' + fixture.browserDisableProject.id + '/ownership');
      const detail = (await response.json()).data;
      assert(detail.project.activeOwners === 1 && detail.owners[0].userId === fixture.browserNext.id, 'disable handover mismatch');
      checks.push('disable-impact-and-successor-ui', 'cancel-does-not-submit-disable', 'atomic-handover-and-disable-ui', 'disabled-session-returns-to-login');
    } finally { await oldContext.close(); }
  }
  if (fixture.phase === 'self') {
    const context = await page.context().browser().newContext();
    try {
      const userPage = await context.newPage();
      await login(userPage, fixture.browserNext.username, fixture.password);
      assert(await userPage.getByRole('button', { name: /项目管理/ }).count() === 0, 'normal user sees admin governance');
      await selectProject(userPage, fixture.browserSelfProject.name);
      await userPage.getByRole('button', { name: /项目成员/ }).click();
      let puts = 0, deletes = 0;
      userPage.on('request', request => {
        if (request.url().includes('/members/')) {
          if (request.method() === 'PUT') puts++;
          if (request.method() === 'DELETE') deletes++;
        }
      });
      await userPage.getByRole('row').filter({ hasText: fixture.browserNext.username }).getByRole('button', { name: '修改角色' }).click();
      const edit = userPage.getByRole('dialog', { name: '修改成员角色', exact: true });
      await choose(userPage, edit.getByRole('combobox'), 'VIEWER · 查看配置与报告');
      await edit.getByRole('button', { name: '保存成员' }).click();
      const warning = userPage.getByRole('dialog', { name: '确认降低自身权限', exact: true });
      await warning.getByText(/失去成员管理与写入权限/).waitFor();
      await warning.getByRole('button', { name: '取消', exact: true }).click();
      assert(puts === 0, 'cancel submitted self demotion');
      await edit.getByRole('button', { name: '保存成员' }).click();
      await userPage.getByRole('button', { name: '确定', exact: true }).click();
      await userPage.getByRole('button', { name: /项目成员/ }).waitFor({ state: 'hidden' });
      assert(puts === 1, 'self demotion request count mismatch');
      await userPage.getByText('你以只读成员身份访问此项目，可查看配置，不能修改。', { exact: true }).waitFor();
      checks.push('self-demotion-warning-and-cancel', 'self-demotion-refreshes-role');
      await selectProject(userPage, fixture.browserRemoveProject.name);
      await userPage.getByRole('button', { name: /项目成员/ }).click();
      const row = userPage.getByRole('row').filter({ hasText: fixture.browserNext.username });
      await row.getByRole('button', { name: '移除', exact: true }).click();
      const remove = userPage.getByRole('dialog', { name: '移除 ' + fixture.browserNext.username, exact: true });
      await remove.getByText(/你将失去本项目的访问权限/).waitFor();
      await remove.getByRole('button', { name: '取消', exact: true }).click();
      assert(deletes === 0, 'cancel submitted self removal');
      await row.getByRole('button', { name: '移除', exact: true }).click();
      await userPage.screenshot({ path: 'output/playwright/ownership-self-remove.png', fullPage: true });
      await userPage.getByRole('button', { name: '确定', exact: true }).click();
      await remove.waitFor({ state: 'hidden' });
      assert((await userPage.request.get('http://127.0.0.1:5173/api/v1/projects/' + fixture.browserRemoveProject.id)).status() === 404, 'removed user still has project access');
      assert(deletes === 1, 'self removal request count mismatch');
      checks.push('self-removal-warning-and-cancel', 'self-removal-revokes-project-access', 'normal-user-admin-navigation-hidden');
    } finally { await context.close(); }
  }
  if (fixture.phase === 'viewer') {
    await login(page, fixture.adminUsername, fixture.adminPassword);
    await page.getByRole('button', { name: /账号管理/ }).click();
    await page.getByText('平台管理', { exact: true }).waitFor();
    const endpoint = 'http://127.0.0.1:5173/api/v1';
    const previewPath = '**/api/v1/admin/users/' + fixture.viewerOwner.id + '/disable-impact';
    let previews = 0, writes = 0;
    const submitted = [];
    page.on('request', request => {
      if (request.url().endsWith('/admin/users/' + fixture.viewerOwner.id + '/disable-impact')) previews++;
      if (request.method() === 'PATCH' && request.url().endsWith('/admin/users/' + fixture.viewerOwner.id)) {
        writes++; submitted.push(request.postDataJSON());
      }
    });
    await page.route(previewPath, route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: { code: 'DEPENDENCY_UNAVAILABLE', message: '合成故障：影响服务暂不可用', fieldErrors: [] } }) }));
    await page.getByRole('row').filter({ hasText: fixture.viewerOwner.username }).getByRole('button', { name: '管理', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '管理账号', exact: true });
    await choose(page, dialog.getByRole('combobox'), '禁用');
    await dialog.getByText('无法加载禁用影响，已禁止提交', { exact: true }).waitFor();
    const submit = dialog.getByRole('button', { name: '确认禁用账号', exact: true });
    assert(previews === 1 && await submit.isDisabled() && writes === 0, 'failed automatic preview did not prevent writes');
    await page.screenshot({ path: 'output/playwright/governance-ux-load-error.png', fullPage: true });
    checks.push('automatic-impact-load-failure-blocks-submit');
    await page.unroute(previewPath);
    await dialog.getByRole('button', { name: '重新加载影响' }).click();
    const first = dialog.getByRole('article', { name: '项目 ' + fixture.viewerProject.id, exact: true });
    await first.getByText(fixture.viewerProject.name, { exact: true }).waitFor();
    await first.getByText('项目 ID ' + fixture.viewerProject.id + ' · 禁用后有效 OWNER 0 人', { exact: true }).waitFor();
    await first.getByRole('combobox', { name: '继任者', exact: true }).fill(fixture.viewerSuccessor.username);
    const successorLabel = fixture.viewerSuccessor.username + ' · ' + fixture.viewerSuccessor.id;
    await page.getByRole('option', { name: successorLabel, exact: true }).click();
    await submit.click();
    await page.getByRole('dialog', { name: '确认交接与禁用', exact: true }).getByRole('button', { name: '取消', exact: true }).click();
    assert(writes === 0, 'cancel wrote account changes');
    checks.push('viewer-admin-sees-project-name-id-and-successor', 'cancel-keeps-selection-with-zero-writes');
    // Actual server-side state change between preview and submit, not an invented 409 payload.
    const csrf = (await (await page.request.get(endpoint + '/auth/csrf')).json()).data;
    const addedResponse = await page.request.post(endpoint + '/projects', {
      headers: { [csrf.headerName]: csrf.token },
      data: { name: 'project2 · 预览后新增 ' + Date.now(), ownerId: fixture.viewerOwner.id },
    });
    assert(addedResponse.status() === 201, 'concurrent project setup failed');
    const added = (await addedResponse.json()).data;
    const conflictResponse = page.waitForResponse(response => response.request().method() === 'PATCH' && response.url().endsWith('/admin/users/' + fixture.viewerOwner.id));
    await submit.click(); await page.getByRole('button', { name: '确定', exact: true }).click();
    const rejected = await conflictResponse;
    const rejection = await rejected.json();
    assert(rejected.status() === 409 && rejection.error.code === 'OWNER_TRANSFER_REQUIRED' && rejection.error.fieldErrors.some(p => p.id === added.id), 'expected structured new blocker from real backend');
    const second = dialog.getByRole('article', { name: '项目 ' + added.id, exact: true });
    await second.getByText(added.name, { exact: true }).waitFor();
    await dialog.getByText(/禁用未完成：以下项目需要交接/).waitFor();
    await first.getByText(successorLabel, { exact: true }).first().waitFor();
    assert(await submit.isDisabled() && writes === 1, 'conflict silently retried or lost required handover');
    await page.screenshot({ path: 'output/playwright/governance-ux-conflict.png', fullPage: true });
    const unchanged = (await (await page.request.get(endpoint + '/admin/projects/' + fixture.viewerProject.id + '/ownership')).json()).data;
    assert(unchanged.owners.some(u => u.userId === fixture.viewerOwner.id), 'rejected transaction partially applied old handover');
    checks.push('real-stale-preview-shows-new-project-details', 'existing-selection-preserved-and-no-auto-retry', 'failed-multi-project-request-rolled-back');
    await second.getByRole('combobox', { name: '继任者', exact: true }).fill(fixture.viewerSuccessor.username);
    await page.getByRole('option', { name: successorLabel, exact: true }).click();
    await submit.click(); await page.getByRole('button', { name: '确定', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
    assert(writes === 2 && submitted[1].ownershipTransfers.length === 2, 'handover was not one final atomic submission');
    for (const id of [fixture.viewerProject.id, added.id]) {
      const detail = (await (await page.request.get(endpoint + '/admin/projects/' + id + '/ownership')).json()).data;
      assert(detail.project.activeOwners === 1 && detail.owners[0].userId === fixture.viewerSuccessor.id, 'final owner mismatch');
    }
    const projectDetail = (await (await page.request.get(endpoint + '/projects/' + fixture.viewerProject.id)).json()).data;
    assert(projectDetail.role === 'VIEWER', 'administrator project role was elevated implicitly');
    assert((await page.request.get(endpoint + '/projects/' + fixture.viewerProject.id + '/members')).status() === 403, 'viewer gained normal member management');
    checks.push('viewer-admin-final-multi-project-atomic-disable', 'viewer-role-not-elevated-by-governance');
  }
  if (fixture.phase === 'responsive') {
    await page.setViewportSize({ width: 900, height: 550 });
    await page.getByRole('button', { name: /项目管理/ }).click();
    await page.getByRole('heading', { name: '项目管理', exact: true }).waitFor();
    await page.screenshot({ path: 'output/playwright/ownership-900.png', fullPage: true });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'page overflows horizontally');
    checks.push('governance-900px-layout');
  }
  return { phase: fixture.phase, at: new Date().toISOString(), checks, passed: checks.length };
}
