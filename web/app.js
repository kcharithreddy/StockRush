// StockRush shop page. Plain browser JavaScript, no dependencies and no build step.
//
// Login: OpenID Connect authorization code flow with PKCE against Keycloak (public client "stockrush-web").
// Tokens live only in this page's memory: nothing readable by other scripts is stored, and a reload asks
// Keycloak again (instant while the Keycloak session is alive). Requests to the API use credentials: "omit",
// so no cookie for this host (Grafana's, for example: cookies ignore ports) ever reaches the services.

const meta = (name) => document.querySelector(`meta[name="${name}"]`).content;
const ISSUER = meta("stockrush-issuer");
const CLIENT_ID = meta("stockrush-client");
const SALE = chooseSale();
const REDIRECT_URI = `${location.origin}/`;
const OIDC = `${ISSUER}/protocol/openid-connect`;

/** ?sale=2 picks another sale (sale 2 is the waiting-room demo). It survives the trip to Keycloak in sessionStorage. */
function chooseSale() {
  const params = new URLSearchParams(location.search);
  const pending = params.has("code") || params.has("error")
    ? JSON.parse(sessionStorage.getItem("stockrush.pkce") || "null")?.sale
    : params.get("sale");
  const id = Number(pending);
  return Number.isInteger(id) && id > 0 ? id : Number(meta("stockrush-sale"));
}

const homeUrl = () => (SALE === Number(meta("stockrush-sale")) ? "/" : `/?sale=${SALE}`);

const $ = (id) => document.getElementById(id);
const ui = {
  user: $("user"), login: $("login"), logout: $("logout"),
  meter: $("meter-fill"), stock: $("stock-text"), buy: $("buy"), message: $("message"),
  queue: $("queue"), position: $("queue-position"),
  order: $("order"), orderStatus: $("order-status"), pay: $("pay"), orderHint: $("order-hint"),
};

let session = null;        // { access, refresh, idToken, expiresAt, refreshExpiresAt, sub, username, roles }
let stock = null;          // last known units left, null until the first answer
let totalStock = 100;      // the demo sale's size; grows if a larger stock is ever seen
let busy = false;          // a buy or queue round-trip is in flight
let order = null;          // { token, status }
let admitRetries = 0;      // guards against an admit / not-admitted loop
let soldOut = false;       // set only when the server says so (410): stock 0 can also mean "not opened yet"

class LoginNeeded extends Error {}

// ---------- helpers ----------

const randomString = (bytes) => base64url(crypto.getRandomValues(new Uint8Array(bytes)));

function base64url(bytes) {
  let s = "";
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Reads a JWT's claims. For display and routing only: the services verify every token themselves. */
function claims(jwt) {
  const part = jwt.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
  const json = new TextDecoder().decode(Uint8Array.from(atob(part), (c) => c.charCodeAt(0)));
  return JSON.parse(json);
}

function say(text, kind = "") {
  ui.message.textContent = text;
  ui.message.className = `message ${kind}`.trim();
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ---------- login ----------

async function login() {
  const verifier = randomString(32);
  const state = randomString(16);
  const challenge = base64url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
  sessionStorage.setItem("stockrush.pkce", JSON.stringify({ verifier, state, sale: SALE }));
  location.assign(`${OIDC}/auth?${new URLSearchParams({
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    response_type: "code",
    scope: "openid",
    state,
    code_challenge: challenge,
    code_challenge_method: "S256",
  })}`);
}

/** Back from Keycloak with ?code=…&state=…: swap the code for tokens. */
async function finishLogin(params) {
  history.replaceState(null, "", homeUrl());
  const saved = JSON.parse(sessionStorage.getItem("stockrush.pkce") || "null");
  sessionStorage.removeItem("stockrush.pkce");
  if (params.get("error")) {
    say(`Login failed: ${params.get("error_description") || params.get("error")}`, "warn");
    return;
  }
  if (!saved || saved.state !== params.get("state")) {
    say("Login failed: the reply did not match this page's request. Please try again.", "warn");
    return;
  }
  await tokenRequest({
    grant_type: "authorization_code",
    code: params.get("code"),
    redirect_uri: REDIRECT_URI,
    code_verifier: saved.verifier,
  });
}

async function tokenRequest(fields) {
  const res = await fetch(`${OIDC}/token`, {
    method: "POST",
    body: new URLSearchParams({ client_id: CLIENT_ID, ...fields }),
    credentials: "omit",
  });
  if (!res.ok) {
    session = null;
    if (fields.grant_type === "authorization_code") say("Login failed. Please try again.", "warn");
    return false;
  }
  const t = await res.json();
  const access = claims(t.access_token);
  const id = t.id_token ? claims(t.id_token) : {};
  const now = Date.now();
  session = {
    access: t.access_token,
    refresh: t.refresh_token,
    idToken: t.id_token || session?.idToken,
    expiresAt: now + t.expires_in * 1000,
    refreshExpiresAt: now + (t.refresh_expires_in || 0) * 1000,
    sub: access.sub,
    username: id.preferred_username || access.preferred_username || "you",
    roles: access.realm_access?.roles || [],
  };
  return true;
}

/** A valid access token, refreshed shortly before it expires. */
async function accessToken() {
  if (!session) throw new LoginNeeded();
  if (Date.now() > session.expiresAt - 30_000) {
    const ok = session.refresh && Date.now() < session.refreshExpiresAt
      && await tokenRequest({ grant_type: "refresh_token", refresh_token: session.refresh });
    if (!ok) {
      session = null;
      render();
      throw new LoginNeeded();
    }
  }
  return session.access;
}

function logout() {
  const idToken = session?.idToken;
  session = null;
  const params = new URLSearchParams({ client_id: CLIENT_ID, post_logout_redirect_uri: REDIRECT_URI });
  if (idToken) params.set("id_token_hint", idToken);
  location.assign(`${OIDC}/logout?${params}`);
}

// ---------- API ----------

async function api(method, path) {
  const res = await fetch(path, {
    method,
    headers: { Authorization: `Bearer ${await accessToken()}`, Accept: "application/json" },
    credentials: "omit",
    cache: "no-store",
  });
  let body = null;
  try { body = await res.json(); } catch { /* empty or non-JSON body (e.g. 429 from Nginx) */ }
  if (res.status === 401) {
    session = null;
    render();
    throw new LoginNeeded();
  }
  return { status: res.status, body };
}

// ---------- stock ----------

async function refreshStock() {
  try {
    const res = await fetch(`/sales/${SALE}/stock`, { credentials: "omit", cache: "no-store" });
    if (res.ok) {
      stock = (await res.json()).stock;
      totalStock = Math.max(totalStock, stock);
    }
  } catch { /* keep the last value; try again on the next tick */ }
  render();
}

async function pollStock() {
  for (;;) {
    if (!document.hidden) await refreshStock();
    await sleep(2000);
  }
}

// ---------- buying ----------

async function buy() {
  if (!session) return login();
  busy = true;
  render();
  say("");
  try {
    const { status, body } = await api("POST", `/sales/${SALE}/buy`);
    const code = body?.status;
    if (status === 202 && body.orderToken) {
      admitRetries = 0;
      startOrder(body.orderToken);
      refreshStock();
    } else if (code === "NOT_ADMITTED" && admitRetries < 3) {
      admitRetries++;
      await waitInQueue("POST");
    } else if (status === 410) {
      soldOut = true;
      say("Sold out. Every unit has been claimed.", "warn");
    } else if (status === 409) {
      say("You already bought one: it is one per customer.", "warn");
    } else if (code === "SALE_NOT_ACTIVE") {
      say("The sale is not open right now.", "warn");
    } else if (status === 429) {
      say("Too many tries at once. Wait a moment and press Buy again.", "warn");
    } else if (status === 403) {
      say("This account cannot buy. Log in as a buyer (for example buyer1).", "warn");
    } else {
      say("The shop is very busy. Please press Buy again.", "warn");
    }
  } catch (e) {
    say(e instanceof LoginNeeded ? "Please log in again." : "Network problem. Check your connection and try again.", "warn");
  } finally {
    busy = false;
    render();
  }
}

/** Join (POST) or check (GET) the waiting room until admitted, then buy. */
async function waitInQueue(method) {
  ui.queue.hidden = false;
  ui.position.textContent = "Joining…";
  for (;;) {
    const { status, body } = await api(method, `/sales/${SALE}/queue`);
    switch (body?.status) {
      case "WAITING":
        // 1-based: 1 means next in line.
        ui.position.textContent = body.position <= 1 ? "You're next" : `Number ${body.position.toLocaleString()} in line`;
        method = "GET";
        await sleep(2000);
        continue;
      case "NOT_IN_QUEUE":
        method = "POST";
        continue;
      case "ADMITTED":
      case "NO_WAITING_ROOM":
        ui.queue.hidden = true;
        busy = false;
        return buy();
      case "SOLD_OUT":
        soldOut = true;
        ui.queue.hidden = true;
        return say("Sold out while you waited. Sorry!", "warn");
      case "SALE_NOT_ACTIVE":
        ui.queue.hidden = true;
        return say("The sale is not open right now.", "warn");
      default:
        if (status === 429 || status >= 500) {
          await sleep(3000);
          continue;
        }
        ui.queue.hidden = true;
        return say("Could not join the waiting room. Please press Buy again.", "warn");
    }
  }
}

// ---------- the order ----------

const orderKey = () => `stockrush.order.${SALE}.${session.sub}`;

function startOrder(token) {
  order = { token, status: "PROCESSING" };
  sessionStorage.setItem(orderKey(), token);
  say("Got it! One unit is reserved for you.", "ok");
  followOrder();
}

/** The order is written asynchronously (Kafka → order-service), so poll until it appears. */
async function followOrder() {
  for (let i = 0; order && order.status === "PROCESSING"; i++) {
    try {
      const { status, body } = await api("GET", `/orders/${order.token}`);
      if (status === 200) order.status = body.status;
    } catch (e) {
      if (e instanceof LoginNeeded) return;
    }
    render();
    if (order.status === "PROCESSING") await sleep(i < 20 ? 1000 : 5000);
  }
  render();
}

async function pay() {
  ui.pay.disabled = true;
  try {
    const { status, body } = await api("POST", `/orders/${order.token}/pay`);
    if (status === 200) {
      order.status = body.status;
    } else {
      const again = await api("GET", `/orders/${order.token}`);
      if (again.status === 200) order.status = again.body.status;
    }
  } catch (e) {
    say(e instanceof LoginNeeded ? "Please log in again to pay." : "Network problem. Press Pay again.", "warn");
  } finally {
    ui.pay.disabled = false;
    render();
  }
}

// ---------- rendering ----------

function render() {
  const loggedIn = Boolean(session);
  ui.user.hidden = !loggedIn;
  ui.user.textContent = loggedIn ? session.username : "";
  ui.login.hidden = loggedIn;
  ui.logout.hidden = !loggedIn;

  if (stock === null) {
    ui.stock.textContent = "Checking stock…";
  } else {
    ui.meter.style.width = `${Math.round((stock / totalStock) * 100)}%`;
    ui.stock.textContent = soldOut ? "Sold out" : `${stock} of ${totalStock} left`;
  }

  const haveOrder = Boolean(order);
  ui.buy.disabled = busy || soldOut || haveOrder || !ui.queue.hidden;
  ui.buy.textContent = !loggedIn ? "Log in to buy" : busy ? "Buying…" : soldOut ? "Sold out" : haveOrder ? "Bought" : "Buy now";

  ui.order.hidden = !haveOrder;
  if (!haveOrder) return;
  const text = {
    PROCESSING: ["Confirming your order…", ""],
    PENDING_PAYMENT: ["Reserved. Pay within 10 minutes to keep it.", "If you don't pay in time, the unit goes back on sale."],
    PAID: ["Paid. It's yours!", "Thanks for shopping."],
    CANCELLED: ["Cancelled: the payment time ran out.", "The unit went back on sale."],
  }[order.status] || [`Status: ${order.status}`, ""];
  ui.orderStatus.textContent = text[0];
  ui.orderStatus.className = order.status === "PAID" ? "ok" : "";
  ui.orderHint.textContent = text[1];
  ui.pay.hidden = order.status !== "PENDING_PAYMENT";
}

// ---------- start ----------

async function main() {
  ui.login.addEventListener("click", login);
  ui.logout.addEventListener("click", logout);
  ui.buy.addEventListener("click", buy);
  ui.pay.addEventListener("click", pay);

  const params = new URLSearchParams(location.search);
  if (params.has("code") || params.has("error")) await finishLogin(params);

  if (session) {
    const saved = sessionStorage.getItem(orderKey());
    if (saved) {
      order = { token: saved, status: "PROCESSING" };
      followOrder();
    }
    if (!session.roles.includes("buyer")) say("This account is not a buyer, so it cannot buy.", "warn");
  }
  render();
  pollStock();
}

main();
