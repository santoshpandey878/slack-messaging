const { test, expect } = require('@playwright/test');
const { setupTwoUsers, sendMessage, waitForMessage, countElements } = require('../helpers');

test.describe('Multi-user Messaging', () => {
  let env;

  test.beforeEach(async ({ browser }) => {
    env = await setupTwoUsers(browser);
  });

  test.afterEach(async () => {
    await env.contextA.close();
    await env.contextB.close();
  });

  test('User A sends message, User B sees it via WebSocket', async () => {
    await sendMessage(env.pageA, 'Hello from Admin');

    // Admin sees own message immediately (optimistic)
    await waitForMessage(env.pageA, 'Hello from Admin');

    // Bob sees it via WS
    await waitForMessage(env.pageB, 'Hello from Admin');
  });

  test('User B sends message, User A sees it via WebSocket', async () => {
    await sendMessage(env.pageB, 'Hello from Bob');

    await waitForMessage(env.pageB, 'Hello from Bob');
    await waitForMessage(env.pageA, 'Hello from Bob');
  });

  test('Messages from self are not duplicated by WS echo', async () => {
    await sendMessage(env.pageA, 'No duplicate please');
    await env.pageA.waitForTimeout(2000); // wait for WS echo to arrive

    // Should appear exactly once, not twice
    const count = await countElements(env.pageA, '.msg-content:has-text("No duplicate please")');
    expect(count).toBe(1);
  });

  test('Both users exchange messages in correct order', async () => {
    await sendMessage(env.pageA, 'First from Admin');
    await waitForMessage(env.pageB, 'First from Admin');

    await sendMessage(env.pageB, 'Reply from Bob');
    await waitForMessage(env.pageA, 'Reply from Bob');

    await sendMessage(env.pageA, 'Second from Admin');
    await waitForMessage(env.pageB, 'Second from Admin');

    // Verify order in Bob's view
    const messages = await env.pageB.locator('.msg-content').allTextContents();
    const filtered = messages.filter(m =>
      m.includes('First from Admin') || m.includes('Reply from Bob') || m.includes('Second from Admin')
    );
    expect(filtered).toEqual(['First from Admin', 'Reply from Bob', 'Second from Admin']);
  });
});
