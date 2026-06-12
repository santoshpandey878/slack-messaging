/**
 * Browser test helpers — common operations for multi-user testing.
 * All API calls go through the REST API (not the UI) for speed and reliability.
 * UI interactions use Playwright page objects.
 */

const HOST = process.env.TEST_HOST || 'host.docker.internal';
const BASE = `http://${HOST}:8080`;
const AUTH_BASE = `http://${HOST}:8081`;
const CH_BASE = `http://${HOST}:8082`;
const MSG_BASE = `http://${HOST}:8083`;

/**
 * Register a new tenant and return { token, userId, tenantId, slug }.
 */
async function registerTenant(slug) {
  const res = await fetch(`${AUTH_BASE}/api/v1/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      tenantName: slug, tenantSlug: slug,
      email: `admin@${slug}.com`, displayName: 'Admin', password: 'test123456'
    })
  });
  const data = await res.json();
  return data.data;
}

/**
 * Create a second user in the tenant and return { token, userId }.
 */
async function createUser(adminToken, slug, name, email) {
  const res = await fetch(`${AUTH_BASE}/api/v1/users`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${adminToken}` },
    body: JSON.stringify({ email, displayName: name, password: 'test123456' })
  });
  const data = await res.json();
  const userId = data.data.userId;

  const loginRes = await fetch(`${AUTH_BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantSlug: slug, email, password: 'test123456' })
  });
  const loginData = await loginRes.json();
  return { token: loginData.data.token, userId };
}

/**
 * Create a channel and return channelId.
 */
async function createChannel(token, name) {
  const res = await fetch(`${CH_BASE}/api/v1/channels`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
    body: JSON.stringify({ name, type: 'PUBLIC' })
  });
  const data = await res.json();
  return data.data.id;
}

/**
 * Add a user to a channel.
 */
async function addMember(token, channelId, userId) {
  await fetch(`${CH_BASE}/api/v1/channels/${channelId}/members`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
    body: JSON.stringify({ userIds: [userId] })
  });
}

/**
 * Login a user via the browser UI.
 * Fills in slug, email, password and clicks Login.
 */
async function loginViaUI(page, slug, email) {
  await page.goto(BASE);
  await page.waitForSelector('#authPanel.show');
  await page.fill('#slug', slug);
  await page.fill('#email', email);
  await page.fill('#pass', 'test123456');
  // Click the Login button inside the auth panel (not the sidebar button)
  await page.locator('#authPanel .btn-primary').click();
  await page.waitForSelector('#wsStatus.connected', { timeout: 10000 });
}

/**
 * Select a channel by name in the sidebar.
 */
async function selectChannel(page, channelName) {
  await page.click(`.channel-list li:has-text("${channelName}")`);
  // Wait for channel to be fully selected and history loaded
  await page.waitForSelector(`#activeChannelName:has-text("${channelName}")`, { timeout: 5000 });
  await page.waitForTimeout(1000);
}

/**
 * Send a message via the UI composer.
 */
async function sendMessage(page, content) {
  await page.fill('#msgInput', content);
  await page.click('.composer-box button:has-text("Send")');
  await page.waitForTimeout(500);
}

/**
 * Wait for a message with specific text to appear in the messages area.
 */
async function waitForMessage(page, text, timeout = 10000) {
  await page.waitForSelector(`.msg-content:has-text("${text}")`, { timeout });
}

/**
 * Count elements matching a selector.
 */
async function countElements(page, selector) {
  return await page.locator(selector).count();
}

/**
 * Get text content of an element.
 */
async function getText(page, selector) {
  return await page.locator(selector).first().textContent();
}

/**
 * Set up a full two-user test environment:
 * - Register tenant
 * - Create User B
 * - Create channel
 * - Add User B to channel
 * - Both users logged in via browser with channel selected
 *
 * Returns { slug, channelName, channelId, pageA, pageB, contextA, contextB }
 */
async function setupTwoUsers(browser) {
  const slug = 'bt-' + Date.now();
  const channelName = 'test-ch';

  // API setup
  const admin = await registerTenant(slug);
  const userB = await createUser(admin.token, slug, 'Bob', `bob@${slug}.com`);
  const channelId = await createChannel(admin.token, channelName);
  await addMember(admin.token, channelId, userB.userId);

  // Browser contexts (like two separate incognito windows)
  const contextA = await browser.newContext();
  const contextB = await browser.newContext();
  const pageA = await contextA.newPage();
  const pageB = await contextB.newPage();

  // Login both users via UI
  await loginViaUI(pageA, slug, `admin@${slug}.com`);
  await loginViaUI(pageB, slug, `bob@${slug}.com`);

  // Select the channel in both
  await selectChannel(pageA, channelName);
  await selectChannel(pageB, channelName);

  return { slug, channelName, channelId, pageA, pageB, contextA, contextB, adminToken: admin.token, bobToken: userB.token };
}

module.exports = {
  BASE, AUTH_BASE, CH_BASE, MSG_BASE,
  registerTenant, createUser, createChannel, addMember,
  loginViaUI, selectChannel, sendMessage, waitForMessage,
  countElements, getText, setupTwoUsers
};
