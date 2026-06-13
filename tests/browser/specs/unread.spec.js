const { test, expect } = require('@playwright/test');
const { registerTenant, createUser, createChannel, addMember, loginViaUI, selectChannel, BASE, MSG_BASE } = require('../helpers');

test.describe('Unread Counts', () => {

  test('Unread badge shows for messages sent while user was offline', async ({ browser }) => {
    const slug = 'unread-' + Date.now();
    const channelName = 'general';

    // API setup
    const admin = await registerTenant(slug);
    const bob = await createUser(admin.token, slug, 'Bob', `bob@${slug}.com`);
    const channelId = await createChannel(admin.token, channelName);
    await addMember(admin.token, channelId, bob.userId);

    // Admin sends 3 messages while Bob is OFFLINE (no browser, no WS)
    for (let i = 1; i <= 3; i++) {
      await fetch(`${MSG_BASE}/api/v1/channels/${channelId}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin.token}` },
        body: JSON.stringify({ content: `Offline msg ${i}`, idempotencyKey: `unread-${slug}-${i}` })
      });
    }

    // Now Bob opens browser and logs in
    const context = await browser.newContext();
    const page = await context.newPage();
    await loginViaUI(page, slug, `bob@${slug}.com`);
    await page.waitForTimeout(1000);

    // Bob should see unread badge with count 3
    const badge = page.locator('.unread-badge');
    await expect(badge.first()).toBeVisible({ timeout: 5000 });
    const text = await badge.first().textContent();
    expect(parseInt(text)).toBe(3);

    await context.close();
  });

  test('Unread badge clears when channel is selected', async ({ browser }) => {
    const slug = 'unread-clr-' + Date.now();
    const channelName = 'general';

    // API setup
    const admin = await registerTenant(slug);
    const bob = await createUser(admin.token, slug, 'Bob', `bob@${slug}.com`);
    const channelId = await createChannel(admin.token, channelName);
    await addMember(admin.token, channelId, bob.userId);

    // Admin sends messages while Bob is offline
    await fetch(`${MSG_BASE}/api/v1/channels/${channelId}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin.token}` },
      body: JSON.stringify({ content: 'Clear test', idempotencyKey: `clr-${slug}` })
    });

    // Bob opens browser — should see badge
    const context = await browser.newContext();
    const page = await context.newPage();
    await loginViaUI(page, slug, `bob@${slug}.com`);
    await page.waitForTimeout(1000);

    const badgeBefore = await page.locator('.unread-badge').count();
    expect(badgeBefore).toBeGreaterThan(0);

    // Bob clicks the channel — badge should clear
    await selectChannel(page, channelName);
    await page.waitForTimeout(1000);

    // After selecting, the active channel should not have a badge
    const activeBadge = await page.locator('.channel-list li.active .unread-badge').count();
    expect(activeBadge).toBe(0);

    await context.close();
  });
});
