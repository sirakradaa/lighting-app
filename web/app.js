const $ = (id) => document.getElementById(id);

const PRESETS = [
  ["Warm white", "#ffe1b0", 255, 214, 160],
  ["Candle", "#ff8a3d", 255, 120, 48],
  ["White", "#ffffff", 255, 255, 255],
  ["Red", "#ff2a2a", 255, 0, 0],
  ["Orange", "#ff7a18", 255, 90, 0],
  ["Yellow", "#ffd000", 255, 190, 0],
  ["Green", "#3dff7a", 0, 255, 80],
  ["Cyan", "#22e0ff", 0, 210, 255],
  ["Blue", "#3d6bff", 40, 80, 255],
  ["Purple", "#b44dff", 170, 50, 255],
  ["Pink", "#ff3d8a", 255, 40, 140],
  ["Mint", "#7dffe0", 90, 255, 190],
];

let state = { lights: [], profiles: [], colorOrders: [], rhythmModes: [], demo: false };
let checked = new Set();
let stageKey = null;
let picker = { hue: 28, sat: 0.72 };
let dragging = false;
let scanning = false;

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (ch) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  }[ch]));
}

function toast(message) {
  if (!message) return;
  const node = document.createElement("div");
  node.className = "toast";
  node.textContent = message;
  $("toasts").appendChild(node);
  setTimeout(() => node.remove(), 5000);
}

async function api(path, body) {
  const response = await fetch(path, body ? {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  } : undefined);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (data.lights) applyState(data);
    throw new Error(data.error || "Request failed");
  }
  return data;
}

function profileMeta(id) {
  return state.profiles.find((item) => item.id === id) || null;
}

function selected() {
  return chosen().filter((light) => light.connected);
}

function hsvToRgb(hue, sat, value) {
  const c = value * sat;
  const hp = (hue % 360) / 60;
  const x = c * (1 - Math.abs((hp % 2) - 1));
  let r = 0;
  let g = 0;
  let b = 0;
  if (hp < 1) [r, g, b] = [c, x, 0];
  else if (hp < 2) [r, g, b] = [x, c, 0];
  else if (hp < 3) [r, g, b] = [0, c, x];
  else if (hp < 4) [r, g, b] = [0, x, c];
  else if (hp < 5) [r, g, b] = [x, 0, c];
  else [r, g, b] = [c, 0, x];
  const m = value - c;
  return [Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255)];
}

function rgbToHsv(r, g, b) {
  r /= 255;
  g /= 255;
  b /= 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const d = max - min;
  let h = 0;
  if (d !== 0) {
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h *= 60;
    if (h < 0) h += 360;
  }
  return { h, s: max === 0 ? 0 : d / max, v: max };
}

function cssColor(r, g, b, brightness = 100) {
  const scale = Math.max(0, Math.min(100, brightness)) / 100;
  return `rgb(${Math.round(r * scale)}, ${Math.round(g * scale)}, ${Math.round(b * scale)})`;
}

function hexOf(r, g, b) {
  return "#" + [r, g, b].map((part) => part.toString(16).padStart(2, "0")).join("");
}

function chosen() {
  return state.lights.filter((light) => checked.has(light.address));
}

function applyState(next) {
  state = next;
  const known = new Set(state.lights.map((light) => light.address));
  checked = new Set([...checked].filter((address) => known.has(address)));
  renderBanner();
  renderList();
  const key = chosen()
    .map((light) => [light.address, light.profile, light.mode, light.colorOrder, light.powerStyle, light.connected].join(":"))
    .sort()
    .join("|");
  if (key !== stageKey && !dragging) {
    stageKey = key;
    const first = chosen()[0];
    if (first) {
      const hsv = rgbToHsv(first.r, first.g, first.b);
      picker = { hue: hsv.h, sat: hsv.s };
    }
    renderStage();
  } else if (key === stageKey) {
    syncStage();
  }
  if (next.warning) {
    toast(next.warning);
    delete next.warning;
  }
}

function renderBanner() {
  const banner = $("banner");
  if (state.radioError) {
    banner.hidden = false;
    banner.className = "banner";
    banner.textContent = state.radioError;
    return;
  }
  if (state.demo) {
    banner.hidden = false;
    banner.className = "banner demo";
    banner.textContent = "Demo mode. These three lamps are samples, and no Bluetooth radio is used. Start run.bat without --demo to control the real lights.";
    return;
  }
  banner.hidden = true;
}

function statusLine(light) {
  const meta = profileMeta(light.profile);
  const kind = meta ? meta.label : "Unrecognized";
  if (light.connecting) return "Connecting…";
  if (light.connected) return `${kind} · connected`;
  if (light.seen) return `${kind} · in range`;
  return `${kind} · saved, not seen in the last scan`;
}

function renderList() {
  const visible = state.lights.filter((light) => $("show-all").checked || light.profile || light.saved || light.connected);
  const list = $("light-list");
  if (!visible.length) {
    list.innerHTML = `<p class="empty">No lights yet. Power the lamps on and scan. Phone, watch, and speaker Bluetooth devices stay hidden unless you show other devices.</p>`;
    return;
  }
  list.innerHTML = visible.map((light) => {
    const active = checked.has(light.address) ? " active" : "";
    const action = light.connected
      ? `<button class="tiny" type="button" data-action="disconnect" data-address="${esc(light.address)}">Disconnect</button>`
      : `<button class="tiny" type="button" data-action="connect" data-address="${esc(light.address)}">Connect</button>`;
    return `<article class="light${active}">
      <input type="checkbox" data-action="check" data-address="${esc(light.address)}" ${checked.has(light.address) ? "checked" : ""}>
      <button class="light-main" type="button" data-action="focus" data-address="${esc(light.address)}">
        <span class="swatch" style="background:${cssColor(light.r, light.g, light.b, light.on ? light.brightness : 8)}"></span>
        <span>
          <strong>${esc(light.nickname)}</strong>
          <small>${esc(statusLine(light))}${light.rssi != null ? ` · ${light.rssi} dBm` : ""}</small>
        </span>
      </button>
      ${action}
    </article>`;
  }).join("");
}

function sharedProfile() {
  const lights = selected();
  if (!lights.length) return null;
  const id = lights[0].profile;
  if (lights.every((light) => light.profile === id)) return profileMeta(id);
  return null;
}

function renderStage() {
  const lights = chosen();
  const stage = $("stage");
  if (!lights.length) {
    stage.innerHTML = `<section class="panel">
      <h2>No lamp selected</h2>
      <p class="hint">Scan, then connect a lamp. The phone apps have to be closed, and this computer has to be in Bluetooth range. Windows may ask to pair the first time — accept that prompt.</p>
    </section>`;
    return;
  }
  const online = lights.filter((light) => light.connected);
  if (!online.length) {
    const primary = lights[0];
    stage.innerHTML = `<section class="panel">
      <div class="stage-head">
        <div>
          <h2>${esc(lights.length === 1 ? primary.nickname : `${lights.length} lamps`)}</h2>
          <p class="hint">${esc(primary.error || "Connected lamps can be colored from here. Connect this one when it is powered on and the phone app is closed.")}</p>
        </div>
        ${lights.length === 1 ? `<button id="stage-connect" class="primary" type="button">Connect</button>` : ""}
      </div>
      ${lights.length === 1 ? settingsBlock(primary) : ""}
    </section>`;
    bindSettings();
    const connect = $("stage-connect");
    if (connect) {
      connect.addEventListener("click", async () => {
        connect.disabled = true;
        try {
          applyState(await api("/api/connect", { address: primary.address }));
        } catch (error) {
          toast(error.message);
        }
      });
    }
    return;
  }
  const primary = online[0];
  const meta = sharedProfile();
  const names = online.map((light) => light.nickname).join(", ");
  const mixed = online.length > 1 && !meta;
  stage.innerHTML = `<section class="panel">
    <div class="stage-head">
      <div>
        <h2>${esc(online.length === 1 ? primary.nickname : `${online.length} lamps`)}</h2>
        <p>${esc(names)}</p>
      </div>
      <button id="power-btn" class="power ${primary.on ? "on" : ""}" type="button" aria-pressed="${primary.on}">${primary.on ? "On" : "Off"}</button>
    </div>
    <div class="composer">
      <div class="pad-wrap">
        <canvas id="pad" width="560" height="360"></canvas>
        <div id="knob" class="knob"></div>
      </div>
      <div>
        <div class="orb-row">
          <div id="orb" class="orb"></div>
          <input id="hex" class="hex" value="${hexOf(primary.r, primary.g, primary.b)}" aria-label="Hex color" maxlength="7">
          <span class="hint" id="source">${esc(sourceText(primary, lights))}</span>
        </div>
        <div class="preset-row" id="presets">
          ${PRESETS.map((preset, index) => `<button class="preset" type="button" data-preset="${index}" style="background:${preset[1]}" title="${esc(preset[0])}" aria-label="${esc(preset[0])}"></button>`).join("")}
        </div>
        <label class="slider">Brightness <strong id="bright-read">${primary.brightness}</strong>
          <input id="brightness" type="range" min="0" max="100" value="${primary.brightness}">
        </label>
        ${speedControl(meta, primary)}
      </div>
    </div>
    ${effectsBlock(meta, primary, mixed)}
    ${online.length === 1 ? settingsBlock(primary) : `<p class="hint">Select a single lamp to change its type, color order, or name.</p>`}
  </section>`;
  drawPad();
  bindStage();
}

function sourceText(primary, lights) {
  if (lights.length > 1) return "The next change is sent to every selected lamp.";
  if (primary.stateSource === "lamp") return "Color was read from the lamp.";
  if (primary.stateSource === "sent") return "Showing the last command sent.";
  return "Showing the color remembered from last time.";
}

function speedControl(meta, primary) {
  if (!meta || !meta.supportsSpeed) return "";
  if (primary.profile === "ks03_tilde" && primary.mode !== "effect") return "";
  return `<label class="slider">Pattern speed <strong id="speed-read">${primary.speed}</strong>
    <input id="speed" type="range" min="0" max="100" value="${primary.speed}">
  </label>`;
}

function effectsBlock(meta, primary, mixed) {
  if (mixed) {
    return `<p class="hint">These lamps use different pattern lists. Color, power, and brightness still apply to all of them. Select one type of lamp to run a pattern.</p>`;
  }
  if (!meta) return "";
  const named = meta.effects.length
    ? `<div class="effect-grid">${meta.effects.map((effect) => `<button class="effect" type="button" data-effect="${effect.id}" aria-pressed="${primary.effect === effect.id}">${esc(effect.name)}</button>`).join("")}</div>`
    : "";
  const index = meta.supportsEffectIndex
    ? `<div class="pattern-row">
        <input id="effect-index" type="number" min="${meta.effectMin}" max="${meta.effectMax}" value="${primary.effect ?? 0}">
        <button id="apply-effect" class="ghost" type="button">Play pattern</button>
        <span class="hint">LED LAMP strips have patterns 0–210. Scrub until you like one.</span>
      </div>`
    : "";
  const rhythm = meta.supportsRhythm
    ? `<h3>Microphone</h3><div class="segment" id="rhythm">
        ${state.rhythmModes.map((mode) => `<button type="button" data-rhythm="${mode.id}" aria-pressed="${primary.rhythm === mode.id}">${esc(mode.name)}</button>`).join("")}
      </div>
      <label class="slider">Mic sensitivity <strong id="sens-read">${primary.sensitivity}</strong>
        <input id="sensitivity" type="range" min="1" max="8" value="${primary.sensitivity}">
        <span class="hint">1 reacts to quiet sounds. 8 waits for louder ones. The lamp uses its own microphone.</span>
      </label>`
    : "";
  return `<div class="section">
    <h3>Patterns</h3>
    <div class="effect-grid"><button class="effect" type="button" id="solid-btn" aria-pressed="${primary.mode === "solid"}">Solid color</button></div>
    ${named}
    ${index}
    ${rhythm}
    ${primary.warning ? `<p class="warn">${esc(primary.warning)}</p>` : ""}
  </div>`;
}

function settingsBlock(light) {
  const profiles = state.profiles.map((item) => `<option value="${item.id}" ${item.id === light.profile ? "selected" : ""}>${esc(item.label)}</option>`).join("");
  const orders = state.colorOrders.map((order) => `<option value="${order}" ${order === light.colorOrder ? "selected" : ""}>${esc(order.toUpperCase())}</option>`).join("");
  const power = light.profile === "leddmx"
    ? `<label>Power commands
        <select id="power-style">
          <option value="standard" ${light.powerStyle === "standard" ? "selected" : ""}>LED LAMP standard</option>
          <option value="alt" ${light.powerStyle === "alt" ? "selected" : ""}>Alternate on/off</option>
        </select>
      </label>`
    : "";
  return `<details class="settings">
    <summary>Lamp settings</summary>
    <div class="grid">
      <label>Name <input id="nick" class="nick" value="${esc(light.nickname)}" maxlength="40"></label>
      <label>Type
        <select id="profile">${profiles}</select>
      </label>
      <label>Color order
        <select id="color-order">${orders}</select>
        <span class="hint">If green and blue are swapped, choose RBG.</span>
      </label>
      ${power}
      <p>Address <span class="command">${esc(light.address)}</span></p>
      <p>Last command <span class="command" id="last-command">${esc(light.lastCommand || "none yet")}</span></p>
      ${light.error ? `<p class="warn">${esc(light.error)}</p>` : ""}
      <div class="row">
        <button id="save-name" class="ghost" type="button">Save name</button>
        <button id="forget" class="danger" type="button">Forget lamp</button>
      </div>
    </div>
  </details>`;
}

function drawPad() {
  const canvas = $("pad");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  const width = canvas.width;
  const height = canvas.height;
  for (let x = 0; x < width; x += 2) {
    const [r, g, b] = hsvToRgb((x / width) * 360, 1, 1);
    const gradient = ctx.createLinearGradient(0, 0, 0, height);
    gradient.addColorStop(0, `rgb(${r},${g},${b})`);
    gradient.addColorStop(1, "#ffffff");
    ctx.fillStyle = gradient;
    ctx.fillRect(x, 0, 2, height);
  }
  placeKnob();
  paintOrb();
}

function placeKnob() {
  const canvas = $("pad");
  const knob = $("knob");
  if (!canvas || !knob) return;
  const rect = canvas.getBoundingClientRect();
  knob.style.left = `${(picker.hue / 360) * rect.width}px`;
  knob.style.top = `${(1 - picker.sat) * rect.height}px`;
}

function paintOrb() {
  const orb = $("orb");
  const primary = selected()[0];
  if (!orb || !primary) return;
  const [r, g, b] = hsvToRgb(picker.hue, picker.sat, 1);
  orb.style.background = cssColor(r, g, b, primary.brightness);
  orb.style.boxShadow = `0 0 32px ${cssColor(r, g, b, Math.max(primary.brightness, 30))}`;
}

function bindStage() {
  const canvas = $("pad");
  if (!canvas) return;
  const move = (event) => {
    const rect = canvas.getBoundingClientRect();
    const x = Math.min(Math.max(event.clientX - rect.left, 0), rect.width);
    const y = Math.min(Math.max(event.clientY - rect.top, 0), rect.height);
    picker.hue = (x / rect.width) * 359.9;
    picker.sat = 1 - y / rect.height;
    placeKnob();
    paintOrb();
    const [r, g, b] = hsvToRgb(picker.hue, picker.sat, 1);
    const hex = $("hex");
    if (hex) hex.value = hexOf(r, g, b);
    scheduleColor(r, g, b);
  };
  canvas.addEventListener("pointerdown", (event) => {
    dragging = true;
    canvas.setPointerCapture(event.pointerId);
    move(event);
  });
  canvas.addEventListener("pointermove", (event) => {
    if (dragging) move(event);
  });
  canvas.addEventListener("pointerup", () => {
    dragging = false;
    flushColor();
  });
  $("power-btn").addEventListener("click", async () => {
    const lights = selected();
    const turnOn = lights.some((light) => !light.on);
    await send({ type: "power", on: turnOn });
  });
  $("presets").addEventListener("click", (event) => {
    const button = event.target.closest("[data-preset]");
    if (!button) return;
    const preset = PRESETS[Number(button.dataset.preset)];
    const hsv = rgbToHsv(preset[2], preset[3], preset[4]);
    picker = { hue: hsv.h, sat: hsv.s };
    drawPad();
    $("hex").value = hexOf(preset[2], preset[3], preset[4]);
    send({ type: "color", r: preset[2], g: preset[3], b: preset[4] });
  });
  $("hex").addEventListener("change", () => {
    const match = /^#?([0-9a-fA-F]{6})$/.exec($("hex").value.trim());
    if (!match) return;
    const value = match[1];
    const r = parseInt(value.slice(0, 2), 16);
    const g = parseInt(value.slice(2, 4), 16);
    const b = parseInt(value.slice(4, 6), 16);
    const hsv = rgbToHsv(r, g, b);
    picker = { hue: hsv.h, sat: hsv.s };
    drawPad();
    send({ type: "color", r, g, b });
  });
  bindRange("brightness", "bright-read", (level) => {
    paintOrb();
    return { type: "brightness", level };
  });
  bindRange("speed", "speed-read", (level) => ({ type: "speed", level }));
  bindRange("sensitivity", "sens-read", (level) => {
    const current = selected()[0];
    return { type: "rhythm", mode: current && current.rhythm ? current.rhythm : 1, sensitivity: level };
  });
  document.querySelectorAll("[data-effect]").forEach((button) => {
    button.addEventListener("click", () => send({ type: "effect", id: Number(button.dataset.effect) }));
  });
  const apply = $("apply-effect");
  if (apply) {
    apply.addEventListener("click", () => {
      const index = Number($("effect-index").value);
      send({ type: "effect", id: index });
    });
  }
  const solid = $("solid-btn");
  if (solid) {
    solid.addEventListener("click", () => {
      const [r, g, b] = hsvToRgb(picker.hue, picker.sat, 1);
      send({ type: "color", r, g, b });
    });
  }
  document.querySelectorAll("[data-rhythm]").forEach((button) => {
    button.addEventListener("click", () => {
      const current = selected()[0];
      send({
        type: "rhythm",
        mode: Number(button.dataset.rhythm),
        sensitivity: current ? current.sensitivity : 4,
      });
    });
  });
  bindSettings();
}

function bindSettings() {
  const saveName = $("save-name");
  if (saveName) saveName.addEventListener("click", () => meta({ nickname: $("nick").value }));
  const profile = $("profile");
  if (profile) profile.addEventListener("change", () => meta({ profile: profile.value }));
  const order = $("color-order");
  if (order) order.addEventListener("change", () => meta({ colorOrder: order.value }));
  const powerStyle = $("power-style");
  if (powerStyle) powerStyle.addEventListener("change", () => meta({ powerStyle: powerStyle.value }));
  const forget = $("forget");
  if (forget) {
    forget.addEventListener("click", async () => {
      const light = chosen()[0];
      if (!light) return;
      checked.delete(light.address);
      try {
        applyState(await api("/api/forget", { address: light.address }));
      } catch (error) {
        toast(error.message);
      }
    });
  }
}

let pendingColor = null;
let colorTimer = 0;

function scheduleColor(r, g, b) {
  pendingColor = { type: "color", r, g, b };
  clearTimeout(colorTimer);
  colorTimer = setTimeout(flushColor, 90);
}

function flushColor() {
  if (!pendingColor) return;
  const payload = pendingColor;
  pendingColor = null;
  send(payload);
}

function bindRange(id, readId, build) {
  const input = $(id);
  if (!input) return;
  input.addEventListener("input", () => {
    const read = $(readId);
    if (read) read.textContent = input.value;
    const payload = build(Number(input.value));
    clearTimeout(input._timer);
    input._timer = setTimeout(() => send(payload), 90);
  });
}

async function send(payload) {
  const addresses = selected().map((light) => light.address);
  if (!addresses.length) return;
  try {
    applyState(await api("/api/control", { ...payload, addresses }));
  } catch (error) {
    toast(error.message);
  }
}

async function meta(fields) {
  const light = chosen()[0];
  if (!light) return;
  try {
    applyState(await api("/api/meta", { address: light.address, ...fields }));
  } catch (error) {
    toast(error.message);
  }
}

function syncStage() {
  const primary = selected()[0];
  if (!primary || dragging) return;
  const power = $("power-btn");
  if (power) {
    power.textContent = primary.on ? "On" : "Off";
    power.classList.toggle("on", primary.on);
    power.setAttribute("aria-pressed", String(primary.on));
  }
  const bright = $("brightness");
  if (bright && document.activeElement !== bright) {
    bright.value = String(primary.brightness);
    const read = $("bright-read");
    if (read) read.textContent = String(primary.brightness);
  }
  const last = $("last-command");
  if (last) last.textContent = primary.lastCommand || "none yet";
  const source = $("source");
  if (source) source.textContent = sourceText(primary, selected());
  document.querySelectorAll("[data-effect]").forEach((button) => {
    button.setAttribute("aria-pressed", String(Number(button.dataset.effect) === primary.effect));
  });
  const solid = $("solid-btn");
  if (solid) solid.setAttribute("aria-pressed", String(primary.mode === "solid"));
  document.querySelectorAll("[data-rhythm]").forEach((button) => {
    button.setAttribute("aria-pressed", String(Number(button.dataset.rhythm) === primary.rhythm));
  });
  paintOrb();
}

$("scan-btn").addEventListener("click", async () => {
  scanning = true;
  $("scan-btn").disabled = true;
  $("scan-btn").textContent = "Scanning…";
  try {
    applyState(await api("/api/scan", { seconds: state.demo ? 2 : 6 }));
  } catch (error) {
    toast(error.message);
  } finally {
    scanning = false;
    $("scan-btn").disabled = false;
    $("scan-btn").textContent = "Scan";
  }
});

$("all-btn").addEventListener("click", () => {
  checked = new Set(state.lights.filter((light) => light.connected).map((light) => light.address));
  stageKey = null;
  applyState(state);
});

$("show-all").addEventListener("change", renderList);

$("light-list").addEventListener("click", async (event) => {
  const check = event.target.closest("[data-action='check']");
  if (check) {
    if (check.checked) checked.add(check.dataset.address);
    else checked.delete(check.dataset.address);
    stageKey = null;
    applyState(state);
    return;
  }
  const button = event.target.closest("[data-action]");
  if (!button) return;
  const address = button.dataset.address;
    if (button.dataset.action === "focus") {
      checked = new Set([address]);
      stageKey = null;
      applyState(state);
      return;
    }
  if (button.dataset.action === "connect") {
    button.disabled = true;
    try {
      applyState(await api("/api/connect", { address }));
      checked = new Set([address]);
      stageKey = null;
      applyState(state);
    } catch (error) {
      toast(error.message);
    }
    return;
  }
  if (button.dataset.action === "disconnect") {
    checked.delete(address);
    try {
      applyState(await api("/api/disconnect", { address }));
    } catch (error) {
      toast(error.message);
    }
  }
});

async function refresh() {
  if (dragging || scanning) return;
  try {
    const next = await api("/api/state");
    applyState(next);
  } catch (_error) {
    /* The next poll retries. A toast on every blip is noisier than a missed refresh. */
  }
}

refresh();
setInterval(refresh, 3000);
window.addEventListener("resize", placeKnob);
