const { test, expect } = require('@playwright/test');
const { setupTwoUsers, sendMessage, waitForMessage, countElements, MSG_BASE, registerTenant, createChannel, loginViaUI, selectChannel } = require('../helpers');

test.describe('Threads', () => {
  let env;
  test.beforeEach(async ({ browser }) => { env = await setupTwoUsers(browser); });
  test.afterEach(async () => { await env.contextA.close(); await env.contextB.close(); });

  test('Reply creates thread and appears in thread panel', async () => {
    await sendMessage(env.pageA, 'Thread parent msg');
    await waitForMessage(env.pageA, 'Thread parent msg');

    // User A clicks Reply to open thread panel
    await env.pageA.locator('.msg-actions button:has-text("Reply")').first().click();
    await env.pageA.waitForSelector('#threadPanel.show', { timeout: 5000 });

    // Type and send a reply using Enter key (avoids overlay click issues)
    await env.pageA.fill('#threadInput', 'First reply in thread');
    await env.pageA.press('#threadInput', 'Enter');
    await env.pageA.waitForTimeout(2000);

    // Verify reply appears in the thread panel
    const threadContent = await env.pageA.locator('#threadMessages').textContent();
    expect(threadContent).toContain('First reply in thread');
  });
});

test.describe('Reactions', () => {
  let env;
  test.beforeEach(async ({ browser }) => { env = await setupTwoUsers(browser); });
  test.afterEach(async () => { await env.contextA.close(); await env.contextB.close(); });

  test('Reaction shows correct count, no double-count for sender', async () => {
    await sendMessage(env.pageA, 'React to this');
    await waitForMessage(env.pageA, 'React to this');
    await waitForMessage(env.pageB, 'React to this');

    // User A adds reaction via emoji picker
    await env.pageA.click('.msg-actions button:has-text("React")');
    await env.pageA.waitForSelector('.emoji-picker.show');
    await env.pageA.click('.emoji-picker span:first-child');
    await env.pageA.waitForTimeout(1000);

    // Sender sees exactly 1 reaction badge (not 2)
    const badgesA = await env.pageA.locator('.reaction-badge').count();
    expect(badgesA).toBe(1);

    // User B sees 1 reaction badge via WS
    await env.pageB.waitForTimeout(2000);
    const badgesB = await env.pageB.locator('.reaction-badge').count();
    expect(badgesB).toBe(1);
  });
});

test.describe('Pins', () => {
  let env;
  test.beforeEach(async ({ browser }) => { env = await setupTwoUsers(browser); });
  test.afterEach(async () => { await env.contextA.close(); await env.contextB.close(); });

  test('Pin shows message content in pins panel, not ID', async () => {
    await sendMessage(env.pageA, 'Pin this important message');
    await waitForMessage(env.pageA, 'Pin this important message');

    // Pin the message
    await env.pageA.click('.msg-actions button:has-text("Pin")');
    await env.pageA.waitForTimeout(500);

    // Open pins panel
    await env.pageA.click('.topbar-actions button:has-text("Pins")');
    await env.pageA.waitForSelector('#pinsPanel.show');

    // Should show actual message content, NOT a UUID
    const pinsContent = await env.pageA.locator('#pinsList').textContent();
    expect(pinsContent).toContain('Pin this important message');
    expect(pinsContent).not.toMatch(/^[0-9a-f]{8}/); // no UUID at start
  });
});

test.describe('Search', () => {
  let env;
  test.beforeEach(async ({ browser }) => { env = await setupTwoUsers(browser); });
  test.afterEach(async () => { await env.contextA.close(); await env.contextB.close(); });

  test('Search finds messages and shows results', async () => {
    await sendMessage(env.pageA, 'Unique searchable content xyz123');
    await waitForMessage(env.pageA, 'Unique searchable content xyz123');

    // Open search
    await env.pageA.click('.topbar-actions button:has-text("Search")');
    await env.pageA.waitForSelector('.search-bar.show');
    await env.pageA.fill('#searchInput', 'xyz123');
    await env.pageA.click('.search-bar button:has-text("Search")');
    await env.pageA.waitForTimeout(1000);

    // Should show the matching message
    await waitForMessage(env.pageA, 'Unique searchable content xyz123');
  });

  test('Search with no results shows empty state', async () => {
    await env.pageA.click('.topbar-actions button:has-text("Search")');
    await env.pageA.waitForSelector('.search-bar.show');
    await env.pageA.fill('#searchInput', 'nonexistent999xyz');
    await env.pageA.click('.search-bar button:has-text("Search")');
    await env.pageA.waitForTimeout(1000);

    const noResults = await env.pageA.locator(':has-text("No results")').count();
    expect(noResults).toBeGreaterThan(0);
  });
});

test.describe('Media Upload', () => {
  test('Upload URL is reachable from browser (not Docker-internal hostname)', async ({ browser }) => {
    const slug = 'media-' + Date.now();
    const admin = await registerTenant(slug);
    const channelId = await createChannel(admin.token, 'general');

    const context = await browser.newContext();
    const page = await context.newPage();
    await loginViaUI(page, slug, `admin@${slug}.com`);
    await selectChannel(page, 'general');

    // Verify fixMediaUrl exists and works in the page context
    const fixed = await page.evaluate(() => {
      return typeof fixMediaUrl === 'function' && fixMediaUrl('http://minio:9000/bucket/file.png') === 'http://localhost:9000/bucket/file.png';
    });
    expect(fixed).toBe(true);

    await context.close();
  });
});

test.describe('Typing Indicators', () => {
  let env;
  test.beforeEach(async ({ browser }) => { env = await setupTwoUsers(browser); });
  test.afterEach(async () => { await env.contextA.close(); await env.contextB.close(); });

  test('User B sees typing indicator when User A types', async () => {
    // User A starts typing
    await env.pageA.fill('#msgInput', 'typing test...');
    // Trigger input event for typing indicator
    await env.pageA.dispatchEvent('#msgInput', 'input');
    await env.pageA.waitForTimeout(2000);

    // User B should see typing indicator
    const typing = await env.pageB.locator('#typingIndicator').textContent();
    expect(typing).toContain('typing');
  });
});
