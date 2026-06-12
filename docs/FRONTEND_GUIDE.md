# Frontend Guide — HTML Demo Client

The demo client is a single-file HTML app at `api-gateway/src/main/resources/static/index.html` (~448 lines). When building a new feature, the agent MUST update this file to make the feature visible and testable in the browser.

## Architecture

```
index.html (single file)
├── <style>        — CSS variables, layout, components
├── <body>         — Sidebar, main area, panel overlays
└── <script>       — API calls, WebSocket, DOM manipulation
    ├── State:     token, ws, channelId, channels, myUserId, myDisplayName
    ├── api():     Generic REST client (method, path, body) → JSON
    ├── connectWs(): WebSocket connection + message handler
    ├── appendMessage(): Renders a message in the chat area
    └── Feature functions: register, login, createChannel, sendMsg, etc.
```

## Key Patterns

### REST API Call
```javascript
async function doSomething() {
  const r = await api('POST', '/api/v1/channels/' + channelId + '/endpoint', { field: 'value' });
  if (r.success) {
    toast('Success message');
    // Update UI
  } else {
    toast(r.message, true);  // true = error toast
  }
}
```

The `api()` function already handles:
- Authorization header (Bearer token)
- JSON content type
- Activity log entry
- Response parsing

### WebSocket Event Handler
Current handler only processes `message.new`. To handle new event types:

```javascript
// In ws.onmessage callback (line ~303):
ws.onmessage = (e) => {
  logMsg('WS <- ' + e.data.substring(0, 100));
  try {
    const msg = JSON.parse(e.data);

    // Existing: new messages
    if (msg.type === 'message.new') {
      // ... existing code ...
    }

    // NEW: Add handlers for new event types
    if (msg.type === 'message.edited') {
      handleMessageEdited(msg.data);
    }
    if (msg.type === 'message.deleted') {
      handleMessageDeleted(msg.data);
    }
    if (msg.type === 'reaction.added') {
      handleReactionAdded(msg.data);
    }
    if (msg.type === 'typing.start') {
      handleTypingIndicator(msg.data);
    }
    if (msg.type === 'thread.reply') {
      handleThreadReply(msg.data);
    }
    if (msg.type === 'presence.change') {
      handlePresenceChange(msg.data);
    }

  } catch(err) {}
};
```

**IMPORTANT:** The new event envelope format is:
```json
{
  "type": "event.type",
  "tenantId": "...",
  "channelId": "...",
  "timestamp": "...",
  "data": { ... event-specific fields ... }
}
```
Event-specific data is in `msg.data`, NOT at the top level. The old `message.new` format puts message fields in `msg.message` — the agent should update the handler to also support the new envelope (check `msg.data` first, fall back to `msg.message` for backward compat).

### Adding a UI Panel (Modal Dialog)
```html
<!-- Add before </body> -->
<div class="panel-overlay" id="myFeaturePanel">
  <div class="panel">
    <h3>Feature Title</h3>
    <label>Field Name</label>
    <input id="myField" placeholder="Enter value" />
    <div>
      <button class="btn btn-primary" onclick="doMyAction()">Submit</button>
      <button class="btn btn-secondary" onclick="hidePanel('myFeaturePanel')">Cancel</button>
    </div>
    <div class="info" id="myFeatureInfo"></div>
  </div>
</div>
```

Open it: `showPanel('myFeaturePanel')`
Close it: `hidePanel('myFeaturePanel')`

### Adding a Sidebar Button
```html
<!-- In .sidebar-actions div -->
<button onclick="showPanel('myFeaturePanel')">+ My Feature</button>
```

### Adding a Toolbar Button (in topbar)
```html
<!-- In .topbar div, alongside channel name -->
<button style="background:transparent;border:1px solid var(--border);color:var(--text-secondary);padding:4px 10px;border-radius:4px;font-size:11px;cursor:pointer;" onclick="doAction()">Action</button>
```

### Rendering Messages with New Fields

The `appendMessage()` function renders a message. To add new indicators (edited, thread count, reactions):

```javascript
function appendMessage(sender, content, time, mediaUrl, messageType, extraData) {
  // ... existing avatar + header rendering ...

  // Add edited indicator
  let editedHtml = '';
  if (extraData && extraData.editedAt) {
    editedHtml = '<span style="font-size:11px;color:var(--text-muted);margin-left:6px">(edited)</span>';
  }

  // Add thread indicator
  let threadHtml = '';
  if (extraData && extraData.replyCount > 0) {
    threadHtml = '<div style="font-size:12px;color:var(--accent-light);cursor:pointer;margin-top:4px" onclick="openThread(\'' + extraData.messageId + '\')">' + extraData.replyCount + ' replies</div>';
  }

  // Add reactions
  let reactionsHtml = '';
  if (extraData && extraData.reactions) {
    reactionsHtml = '<div style="display:flex;gap:4px;margin-top:4px;flex-wrap:wrap">';
    Object.entries(extraData.reactions).forEach(([emoji, count]) => {
      reactionsHtml += '<span style="background:var(--bg-input);border:1px solid var(--border);border-radius:12px;padding:2px 8px;font-size:12px;cursor:pointer">' + emoji + ' ' + count + '</span>';
    });
    reactionsHtml += '</div>';
  }

  // Append to message div
}
```

### Typing Indicator Area
```html
<!-- Add below messages-area, above composer -->
<div id="typingIndicator" style="padding:2px 20px;font-size:12px;color:var(--text-muted);height:18px;"></div>
```

```javascript
let typingUsers = {};
function handleTypingIndicator(data) {
  if (data.userId === myUserId) return;
  typingUsers[data.userId] = { name: data.displayName, time: Date.now() };
  updateTypingDisplay();
}

function updateTypingDisplay() {
  const now = Date.now();
  const active = Object.values(typingUsers).filter(t => now - t.time < 5000);
  const el = document.getElementById('typingIndicator');
  if (active.length === 0) { el.textContent = ''; return; }
  if (active.length === 1) { el.textContent = active[0].name + ' is typing...'; return; }
  if (active.length === 2) { el.textContent = active[0].name + ' and ' + active[1].name + ' are typing...'; return; }
  el.textContent = 'Several people are typing...';
}
setInterval(updateTypingDisplay, 1000);
```

### Sending Typing Events
```javascript
// In composer input handler
let lastTypingSent = 0;
document.getElementById('msgInput').addEventListener('input', () => {
  if (!channelId || !ws || ws.readyState !== 1) return;
  const now = Date.now();
  if (now - lastTypingSent < 3000) return; // throttle to 1 per 3s
  lastTypingSent = now;
  ws.send(JSON.stringify({ type: 'typing', channelId: channelId }));
});
```

## CSS Variables (use these, don't hardcode colors)

```css
--bg-dark: #1a1d21       /* page background */
--bg-sidebar: #19171d    /* sidebar background */
--bg-main: #222529       /* main content area */
--bg-input: #2c2d31      /* input fields, badges */
--bg-hover: #2c2d31      /* hover states */
--bg-active: #1264a3     /* active/selected items */
--text-primary: #d1d2d3  /* main text */
--text-secondary: #ababad /* secondary text */
--text-muted: #6b6c6e    /* timestamps, meta */
--accent: #1264a3        /* buttons, links */
--accent-light: #1d9bd1  /* hover accent */
--green: #2bac76         /* success */
--red: #e01e5a           /* error */
--border: #333538        /* borders */
--radius: 6px            /* border radius */
```

## Security Rules
- **Always use `escapeHtml()`** before inserting user content into DOM
- **Never use `innerHTML` with user data** — use `textContent` or `escapeHtml()`
- **URLs from API:** Use `escapeHtml()` on media URLs before inserting into `src` or `href`

## State Variables (global)
```javascript
token        // JWT string (set after login)
ws           // WebSocket instance
channelId    // Currently selected channel UUID
channels     // Array of channel objects from API
myUserId     // Current user UUID
myDisplayName // Current user display name
myTenantId   // Current tenant UUID
userNames    // Map of userId → displayName (for name resolution)
```

## Feature UI Checklist
When adding UI for a new feature:
- [ ] API function added (using `api()` helper)
- [ ] WS event handler added (in `ws.onmessage`)
- [ ] UI trigger (button/panel) added in sidebar or topbar
- [ ] Message rendering updated if message display changes
- [ ] Toast notifications for success/error
- [ ] Activity log entries (automatic via `api()`)
- [ ] No XSS — all user content escaped
