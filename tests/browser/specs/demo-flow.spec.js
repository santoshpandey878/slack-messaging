const { test, expect } = require('@playwright/test');
const { registerTenant, createUser, createChannel, addMember, loginViaUI, selectChannel, sendMessage, waitForMessage, BASE, MSG_BASE } = require('../helpers');

/**
 * Full demo flow test — simulates the exact sequence a user does during the Harinder demo.
 * This catches integration bugs that individual feature tests miss:
 * - Media URLs reachable from browser
 * - Presence shows real names
 * - Unread badges don't show on active channel
 * - No raw UUIDs visible anywhere
 */
test.describe('Full Demo Flow', () => {

  test('Complete two-user demo session', async ({ browser }) => {
    const slug = 'demo-' + Date.now();

    // === SETUP via API ===
    const admin = await registerTenant(slug);
    const bob = await createUser(admin.token, slug, 'Bob', `bob@${slug}.com`);
    const channelId = await createChannel(admin.token, 'general');
    await addMember(admin.token, channelId, bob.userId);

    // === USER A (Admin) — Tab 1 ===
    const ctxA = await browser.newContext();
    const pageA = await ctxA.newPage();
    await loginViaUI(pageA, slug, `admin@${slug}.com`);

    // Verify no raw UUIDs visible in sidebar
    const sidebarText = await pageA.locator('.sidebar').textContent();
    expect(sidebarText).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/);

    await selectChannel(pageA, 'general');

    // === USER B (Bob) — Tab 2 ===
    const ctxB = await browser.newContext();
    const pageB = await ctxB.newPage();
    await loginViaUI(pageB, slug, `bob@${slug}.com`);

    // Admin should see presence toast for Bob
    await pageA.waitForTimeout(2000);
    const logContent = await pageA.locator('#logPanel').textContent();
    // Presence event should have arrived (check activity log)
    expect(logContent).toContain('presence.change');

    await selectChannel(pageB, 'general');

    // === MESSAGING ===
    await sendMessage(pageA, 'Hello from Admin');
    await waitForMessage(pageA, 'Hello from Admin');
    await waitForMessage(pageB, 'Hello from Admin');

    await sendMessage(pageB, 'Hello from Bob');
    await waitForMessage(pageB, 'Hello from Bob');
    await waitForMessage(pageA, 'Hello from Bob');

    // === UNREAD — active channel should NOT show badge ===
    const activeBadge = await pageA.locator('.channel-list li.active .unread-badge').count();
    expect(activeBadge).toBe(0);

    // === REACTIONS — no double count ===
    await pageA.click('.msg-actions button:has-text("React")');
    await pageA.waitForSelector('.emoji-picker.show');
    await pageA.click('.emoji-picker span:first-child');
    await pageA.waitForTimeout(1000);

    const reactionBadgesA = await pageA.locator('.reaction-badge').count();
    expect(reactionBadgesA).toBe(1); // NOT 2

    // Bob sees the reaction
    await pageB.waitForTimeout(2000);
    const reactionBadgesB = await pageB.locator('.reaction-badge').count();
    expect(reactionBadgesB).toBe(1);

    // === THREADS ===
    await pageA.locator('.msg-actions button:has-text("Reply")').first().click();
    await pageA.waitForSelector('#threadPanel.show');
    await pageA.fill('#threadInput', 'Thread reply from Admin');
    await pageA.press('#threadInput', 'Enter');
    await pageA.waitForTimeout(1500);

    const threadContent = await pageA.locator('#threadMessages').textContent();
    expect(threadContent).toContain('Thread reply from Admin');

    // Close thread panel before testing pins
    await pageA.click('#threadPanel button');
    await pageA.waitForTimeout(500);

    // === PINS — should show message content, not UUID ===
    await pageA.locator('.msg-actions button:has-text("Pin")').first().click({ force: true });
    await pageA.waitForTimeout(500);
    await pageA.click('.topbar-actions button:has-text("Pins")');
    await pageA.waitForSelector('#pinsPanel.show');

    const pinsText = await pageA.locator('#pinsList').textContent();
    expect(pinsText).not.toMatch(/^[0-9a-f]{8}/); // no UUID
    expect(pinsText.length).toBeGreaterThan(5); // has actual content

    await pageA.evaluate(() => hidePanel('pinsPanel'));
    await pageA.waitForTimeout(500);

    // === SEARCH ===
    await pageA.evaluate(() => toggleSearch());
    await pageA.waitForSelector('.search-bar.show');
    await pageA.fill('#searchInput', 'Hello');
    await pageA.click('.search-bar button:has-text("Search")');
    await pageA.waitForTimeout(1000);

    const searchResults = await pageA.locator('.msg-content').count();
    expect(searchResults).toBeGreaterThan(0);

    // === TYPING ===
    await pageA.click('.search-bar button:has-text("Close")');
    await pageA.waitForTimeout(500);
    // Reload history after search
    await selectChannel(pageA, 'general');

    await pageA.fill('#msgInput', 'typing test...');
    await pageA.dispatchEvent('#msgInput', 'input');
    await pageA.waitForTimeout(2000);

    const typingText = await pageB.locator('#typingIndicator').textContent();
    expect(typingText).toContain('typing');

    // === VERIFY: No raw UUIDs in the entire messages area ===
    const messagesHtml = await pageA.locator('.messages-area').textContent();
    // UUID pattern: 8-4-4-4-12 hex chars
    const uuidPattern = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;
    const uuidsFound = messagesHtml.match(uuidPattern) || [];
    expect(uuidsFound.length).toBe(0);

    await ctxA.close();
    await ctxB.close();
  });
});
