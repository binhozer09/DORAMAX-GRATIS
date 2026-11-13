/* DORAMAX access control - Supabase client-side integration.
   The publishable key is intended for client apps. Never put a service_role/secret key here. */
(() => {
  "use strict";

  const SUPABASE_URL = "https://qbwsaahlkovrejwliefv.supabase.co";
  const SUPABASE_KEY = "sb_publishable_IUeH0RsYhkHpsBflAPo1WQ_1VVPbsAK";
  const PICPay_URL = "https://link.picpay.com/p/17945341236af66aeb27abc";
  const STORAGE_KEY = "doramax_session_v1";

  const feed = document.getElementById("feed");
  const cards = [...document.querySelectorAll("#feed .video")];
  if (!feed || cards.length < 3) return;

  let session = null;
  let currentUser = null;
  let isAdmin = false;
  let unlocked = false;
  let authMode = "login";

  const apiHeaders = (token) => ({
    "apikey": SUPABASE_KEY,
    "Authorization": "Bearer " + (token || SUPABASE_KEY),
    "Content-Type": "application/json"
  });

  async function api(path, options = {}, token) {
    const response = await fetch(SUPABASE_URL + path, {
      ...options,
      headers: { ...apiHeaders(token), ...(options.headers || {}) }
    });
    const raw = await response.text();
    let data = null;
    try { data = raw ? JSON.parse(raw) : null; } catch (_) { data = raw; }
    if (!response.ok) {
      const message = data && (data.msg || data.message || data.error_description || data.error)
        ? (data.msg || data.message || data.error_description || data.error)
        : "Erro ao comunicar com o servidor (" + response.status + ").";
      throw new Error(message);
    }
    return data;
  }

  function saveSession(value) {
    session = value;
    if (value) localStorage.setItem(STORAGE_KEY, JSON.stringify(value));
    else localStorage.removeItem(STORAGE_KEY);
  }

  function addStyles() {
    const style = document.createElement("style");
    style.textContent = `
      .dm-cover{position:absolute;inset:0;z-index:12;display:flex;align-items:center;justify-content:center;padding:24px;background:rgba(0,0,0,.94);text-align:center}
      .dm-cover-card{width:min(100%,340px);padding:26px 22px;border:1px solid #ffffff24;border-radius:20px;background:#151515;box-shadow:0 12px 45px #000}
      .dm-cover h2,.dm-modal h2{font-size:23px;margin:0 0 10px}
      .dm-cover p,.dm-modal p{font-size:14px;line-height:1.5;color:#ddd;margin:0 0 18px}
      .dm-btn{display:block;width:100%;border:0;border-radius:12px;padding:14px 16px;margin:9px 0;background:#e62e49;color:#fff;font-size:15px;font-weight:800}
      .dm-btn.secondary{background:#292929;border:1px solid #555}
      .dm-btn.green{background:#159b60}
      .dm-modal-backdrop{position:fixed;inset:0;z-index:1000;display:none;align-items:center;justify-content:center;padding:18px;background:#000d}
      .dm-modal{width:min(100%,420px);max-height:90dvh;overflow:auto;padding:24px 20px;border:1px solid #ffffff26;border-radius:20px;background:#141414;color:#fff;box-shadow:0 15px 60px #000}
      .dm-modal input{display:block;width:100%;padding:14px;margin:10px 0;border-radius:10px;border:1px solid #555;background:#090909;color:#fff;font-size:16px}
      .dm-modal .dm-small{font-size:12px;color:#aaa}
      .dm-modal .dm-error{color:#ff8292;min-height:18px;font-size:13px}
      .dm-close{float:right;border:0;background:transparent;color:#fff;font-size:25px;padding:0 5px}
      .dm-admin-list{margin-top:16px;text-align:left}
      .dm-user{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:12px 0;border-bottom:1px solid #333;font-size:13px}
      .dm-user button{border:0;border-radius:9px;padding:9px 11px;background:#159b60;color:#fff;font-weight:700}
      .dm-user button.lock{background:#b52b3f}
      .dm-status{font-size:12px;color:#bbb;margin:8px 0}
    `;
    document.head.appendChild(style);
  }

  function createModal() {
    const backdrop = document.createElement("div");
    backdrop.className = "dm-modal-backdrop";
    backdrop.id = "dmModal";
    backdrop.innerHTML = `
      <section class="dm-modal" role="dialog" aria-modal="true" aria-label="Conta DORAMAX">
        <button class="dm-close" id="dmClose" aria-label="Fechar">×</button>
        <h2 id="dmModalTitle">Entrar no DORAMAX</h2>
        <p id="dmModalDesc">Entre com sua conta para consultar seu acesso.</p>
        <form id="dmAuthForm">
          <input id="dmEmail" type="email" autocomplete="email" placeholder="Seu e-mail" required>
          <input id="dmPassword" type="password" autocomplete="current-password" placeholder="Sua senha" minlength="6" required>
          <button class="dm-btn" id="dmSubmit" type="submit">Entrar</button>
        </form>
        <button class="dm-btn secondary" id="dmSwitch" type="button">Criar uma conta</button>
        <button class="dm-btn secondary" id="dmLogout" type="button" style="display:none">Sair da conta</button>
        <div class="dm-error" id="dmError" role="status"></div>
        <div class="dm-status" id="dmStatus"></div>
        <div id="dmAdmin" style="display:none">
          <h2>Painel ADM</h2>
          <p>Libere ou bloqueie o acesso pesquisando pelo e-mail cadastrado.</p>
          <input id="dmSearch" type="email" placeholder="E-mail do usuário">
          <button class="dm-btn" id="dmSearchBtn" type="button">Buscar usuário</button>
          <div class="dm-admin-list" id="dmAdminResult"></div>
          <button class="dm-btn secondary" id="dmRefreshBtn" type="button">Listar usuários cadastrados</button>
        </div>
        <p class="dm-small">O pagamento é conferido manualmente. Use o mesmo e-mail cadastrado no aplicativo.</p>
      </section>`;
    document.body.appendChild(backdrop);
    document.getElementById("dmClose").onclick = closeModal;
    backdrop.addEventListener("click", e => { if (e.target === backdrop) closeModal(); });
    document.getElementById("dmSwitch").onclick = () => {
      authMode = authMode === "login" ? "signup" : "login";
      updateAuthMode();
    };
    document.getElementById("dmAuthForm").addEventListener("submit", submitAuth);
    document.getElementById("dmLogout").onclick = logout;
    document.getElementById("dmSearchBtn").onclick = searchUser;
    document.getElementById("dmRefreshBtn").onclick = listUsers;
  }

  function updateAuthMode() {
    const signup = authMode === "signup";
    document.getElementById("dmModalTitle").textContent = signup ? "Criar conta" : "Entrar no DORAMAX";
    document.getElementById("dmModalDesc").textContent = signup
      ? "Cadastre seu e-mail e crie uma senha para acompanhar sua liberação."
      : "Entre com sua conta para consultar seu acesso.";
    document.getElementById("dmSubmit").textContent = signup ? "Criar conta" : "Entrar";
    document.getElementById("dmSwitch").textContent = signup ? "Já tenho uma conta" : "Criar uma conta";
    document.getElementById("dmPassword").autocomplete = signup ? "new-password" : "current-password";
  }

  function openModal(mode = "login") {
    authMode = mode;
    updateAuthMode();
    document.getElementById("dmError").textContent = "";
    const adminPanel = document.getElementById("dmAdmin");
    const authForm = document.getElementById("dmAuthForm");
    const switchButton = document.getElementById("dmSwitch");
    if (isAdmin && session) {
      authForm.style.display = "none";
      switchButton.style.display = "none";
      adminPanel.style.display = "block";
      document.getElementById("dmModalTitle").textContent = "Painel ADM";
      document.getElementById("dmLogout").style.display = "block";
      listUsers();
    } else {
      authForm.style.display = "block";
      switchButton.style.display = "block";
      adminPanel.style.display = "none";
      document.getElementById("dmLogout").style.display = session ? "block" : "none";
      document.getElementById("dmModalTitle").textContent = mode === "signup" ? "Criar conta" : "Entrar no DORAMAX";
    }
    document.getElementById("dmModal").style.display = "flex";
  }

  function closeModal() {
    document.getElementById("dmModal").style.display = "none";
  }

  function setError(message) {
    document.getElementById("dmError").textContent = message || "";
  }

  function makeCover(card, index) {
    if (card.querySelector(".dm-cover")) return;
    const cover = document.createElement("div");
    cover.className = "dm-cover";
    cover.innerHTML = `
      <div class="dm-cover-card">
        <h2>Continue assistindo</h2>
        <p>Os 2 primeiros vídeos são grátis. Para liberar os demais, faça seu cadastro e pague R$ 5,00 uma única vez.</p>
        <button class="dm-btn" type="button">Entrar / Criar conta</button>
        <button class="dm-btn green" type="button">Pagar R$ 5 pelo PicPay</button>
        <p class="dm-small">Depois do pagamento, aguarde a confirmação e liberação pelo administrador.</p>
      </div>`;
    cover.querySelector(".dm-btn").onclick = () => openModal("login");
    cover.querySelector(".dm-btn.green").onclick = () => {
      window.location.href = PICPay_URL;
    };
    card.style.position = "relative";
    card.appendChild(cover);
  }

  function updateGate() {
    cards.forEach((card, index) => {
      const existing = card.querySelector(".dm-cover");
      if (index < 2 || unlocked || isAdmin) {
        if (existing) existing.remove();
      } else {
        makeCover(card, index);
      }
    });
  }

  async function loadAccess() {
    if (!session || !session.access_token) {
      currentUser = null;
      isAdmin = false;
      unlocked = false;
      updateGate();
      return;
    }
    try {
      currentUser = await api("/auth/v1/user", { method: "GET" }, session.access_token);
      const uid = currentUser.id;
      const adminRows = await api("/rest/v1/admin_users?select=user_id&user_id=eq." + encodeURIComponent(uid), { method: "GET" }, session.access_token);
      isAdmin = Array.isArray(adminRows) && adminRows.length > 0;
      if (isAdmin) {
        unlocked = true;
      } else {
        const rows = await api("/rest/v1/access_grants?select=is_unlocked&user_id=eq." + encodeURIComponent(uid), { method: "GET" }, session.access_token);
        unlocked = !!(Array.isArray(rows) && rows[0] && rows[0].is_unlocked === true);
      }
      updateGate();
      document.getElementById("dmLogout").style.display = "block";
      document.getElementById("dmAdmin").style.display = isAdmin ? "block" : "none";
      document.getElementById("dmStatus").textContent = isAdmin
        ? "Administrador conectado."
        : (unlocked ? "Acesso liberado. Aproveite todos os vídeos!" : "Conta conectada. Acesso aguardando liberação.");
      if (isAdmin) await listUsers();
    } catch (error) {
      saveSession(null);
      currentUser = null;
      isAdmin = false;
      unlocked = false;
      updateGate();
      document.getElementById("dmStatus").textContent = "Sua sessão expirou. Entre novamente.";
    }
  }

  async function submitAuth(event) {
    event.preventDefault();
    setError("");
    const email = document.getElementById("dmEmail").value.trim().toLowerCase();
    const password = document.getElementById("dmPassword").value;
    const signup = authMode === "signup";
    const button = document.getElementById("dmSubmit");
    button.disabled = true;
    button.textContent = "Aguarde...";
    try {
      if (signup) {
        const result = await api("/auth/v1/signup", {
          method: "POST",
          body: JSON.stringify({ email, password })
        });
        if (result && result.access_token) {
          saveSession(result);
          await loadAccess();
          closeModal();
        } else {
          setError("Cadastro iniciado. Confira seu e-mail para confirmar a conta e depois entre.");
        }
      } else {
        const result = await api("/auth/v1/token?grant_type=password", {
          method: "POST",
          body: JSON.stringify({ email, password })
        });
        saveSession(result);
        await loadAccess();
        if (isAdmin) {
          document.getElementById("dmModalTitle").textContent = "Painel ADM";
          document.getElementById("dmAuthForm").style.display = "none";
          document.getElementById("dmSwitch").style.display = "none";
        } else {
          closeModal();
          if (!unlocked) {
            document.getElementById("dmStatus").textContent = "Pagamento ainda não liberado. Após pagar R$ 5, aguarde o administrador.";
            openModal("login");
            document.getElementById("dmStatus").textContent = "Conta conectada, mas o acesso ainda está bloqueado.";
          }
        }
      }
    } catch (error) {
      setError(error.message || "Não foi possível entrar. Confira e-mail e senha.");
    } finally {
      button.disabled = false;
      updateAuthMode();
    }
  }

  async function searchUser() {
    if (!isAdmin || !session) return;
    const email = document.getElementById("dmSearch").value.trim().toLowerCase();
    const target = document.getElementById("dmAdminResult");
    target.textContent = "Buscando...";
    if (!email) { target.textContent = "Digite o e-mail cadastrado."; return; }
    try {
      const profiles = await api("/rest/v1/profiles?select=user_id,email&email=ilike." + encodeURIComponent(email), { method: "GET" }, session.access_token);
      if (!profiles.length) { target.textContent = "Usuário não encontrado. Ele precisa criar uma conta no aplicativo primeiro."; return; }
      target.innerHTML = "";
      for (const profile of profiles) await renderAdminUser(profile, target);
    } catch (error) {
      target.textContent = "Erro ao buscar: " + error.message;
    }
  }

  async function renderAdminUser(profile, target) {
    const grants = await api("/rest/v1/access_grants?select=is_unlocked&user_id=eq." + encodeURIComponent(profile.user_id), { method: "GET" }, session.access_token);
    const isUnlocked = !!(grants[0] && grants[0].is_unlocked);
    const row = document.createElement("div");
    row.className = "dm-user";
    const label = document.createElement("span");
    label.textContent = profile.email + " — " + (isUnlocked ? "LIBERADO" : "BLOQUEADO");
    const button = document.createElement("button");
    button.className = isUnlocked ? "lock" : "";
    button.textContent = isUnlocked ? "Bloquear" : "Liberar";
    button.onclick = async () => {
      button.disabled = true;
      try {
        await api("/rest/v1/access_grants?user_id=eq." + encodeURIComponent(profile.user_id), {
          method: "PATCH",
          headers: { "Prefer": "return=minimal" },
          body: JSON.stringify({ is_unlocked: !isUnlocked })
        }, session.access_token);
        await renderAdminUser(profile, target);
        row.remove();
      } catch (error) {
        button.disabled = false;
        setError("Não foi possível alterar o acesso: " + error.message);
      }
    };
    row.append(label, button);
    target.appendChild(row);
  }

  async function listUsers() {
    if (!isAdmin || !session) return;
    const target = document.getElementById("dmAdminResult");
    target.textContent = "Carregando usuários...";
    try {
      const profiles = await api("/rest/v1/profiles?select=user_id,email&order=created_at.desc&limit=100", { method: "GET" }, session.access_token);
      target.innerHTML = "";
      if (!profiles.length) { target.textContent = "Ainda não há usuários cadastrados."; return; }
      for (const profile of profiles) await renderAdminUser(profile, target);
    } catch (error) {
      target.textContent = "Erro ao listar: " + error.message;
    }
  }

  async function logout() {
    try {
      if (session && session.access_token) {
        await api("/auth/v1/logout", { method: "POST" }, session.access_token);
      }
    } catch (_) {}
    saveSession(null);
    currentUser = null;
    isAdmin = false;
    unlocked = false;
    document.getElementById("dmAdmin").style.display = "none";
    document.getElementById("dmAuthForm").style.display = "block";
    document.getElementById("dmSwitch").style.display = "block";
    document.getElementById("dmLogout").style.display = "none";
    updateGate();
    closeModal();
  }

  function installAdminEntry() {
    const brand = document.querySelector(".brand");
    const adminButton = document.getElementById("adminAccessBtn");
    if (adminButton) adminButton.addEventListener("click", () => openModal("login"));
    if (!brand) return;
    let pressTimer;
    brand.style.pointerEvents = "auto";
    brand.addEventListener("touchstart", () => {
      pressTimer = setTimeout(() => openModal("login"), 900);
    }, { passive: true });
    ["touchend", "touchcancel", "touchmove"].forEach(name => brand.addEventListener(name, () => clearTimeout(pressTimer), { passive: true }));
    brand.addEventListener("dblclick", () => openModal("login"));
  }

  addStyles();
  createModal();
  installAdminEntry();
  updateGate();

  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved) saveSession(JSON.parse(saved));
  } catch (_) { saveSession(null); }

  loadAccess();
  // Refresh access when the app returns to foreground.
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && session) loadAccess();
  });
})();
