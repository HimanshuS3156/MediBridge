// ============================================================
// MedTour India — shared frontend helpers
// All pages talk to the Spring Boot backend through this tiny wrapper.
// ============================================================

const API_BASE = "/api"; // same-origin — works on any Railway domain, no hardcoded host
const SESSION_KEY = "medtour_session"; // { token, fullName, email, role }

// ---------- API calls ----------
// The backend (GlobalExceptionHandler + RestAuthenticationEntryPoint/RestAccessDeniedHandler for
// the two cases that happen inside Spring Security's filter chain) returns EVERY error — validation
// failures, auth failures, permission failures, conflicts, and unexpected server errors — in the
// same structured JSON shape: { status, message, fieldErrors }. Nothing here ever shows the person
// a raw exception or stack trace; handleResponse() below is the one place that turns a non-2xx HTTP
// status into a real Error, and every caller shows err.message through a clean toast/inline alert.

function authHeaders() {
  const token = getToken();
  return token ? { "Authorization": `Bearer ${token}` } : {};
}

// `hadToken` tells us whether this specific request carried a bearer token. A 401 WITH a token
// attached means "your session is no longer valid" (expired/invalid JWT) — safe to auto-clear the
// session and bounce to login. A 401 WITHOUT a token (e.g. a failed login attempt itself, or a
// wrong-password response from /api/auth/login) is just a normal request failure the calling page
// is already showing inline — auto-redirecting there would be wrong (and would loop on the login
// page itself).
async function handleResponse(res, hadToken) {
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    if (res.status === 401 && hadToken) {
      clearSession();
      const redirect = encodeURIComponent(window.location.pathname.split("/").pop() + window.location.search);
      if (!window.location.pathname.endsWith("login.html")) {
        showToast("Your session has expired. Please log in again.", "error");
        setTimeout(() => { window.location.href = `login.html?redirect=${redirect}`; }, 900);
      }
    }
    const err = new Error(data.message || "Something went wrong. Please try again.");
    err.fieldErrors = data.fieldErrors || null;
    err.status = res.status;
    throw err;
  }
  return data;
}

async function apiGet(path) {
  const hadToken = !!getToken();
  const res = await fetch(`${API_BASE}${path}`, { headers: { ...authHeaders() } });
  return handleResponse(res, hadToken);
}

async function apiPost(path, body) {
  const hadToken = !!getToken();
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify(body)
  });
  return handleResponse(res, hadToken);
}

async function apiPut(path, body) {
  const hadToken = !!getToken();
  const res = await fetch(`${API_BASE}${path}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify(body)
  });
  return handleResponse(res, hadToken);
}

async function apiDelete(path) {
  const hadToken = !!getToken();
  const res = await fetch(`${API_BASE}${path}`, {
    method: "DELETE",
    headers: { ...authHeaders() }
  });
  if (res.status === 204) return {};
  return handleResponse(res, hadToken);
}

// ---------- Session (JWT) ----------

function saveSession(authResponse) {
  localStorage.setItem(SESSION_KEY, JSON.stringify(authResponse));
}

function getSession() {
  try {
    return JSON.parse(localStorage.getItem(SESSION_KEY));
  } catch {
    return null;
  }
}

function getToken() {
  const session = getSession();
  return session ? session.token : null;
}

function clearSession() {
  localStorage.removeItem(SESSION_KEY);
}

function isLoggedIn() {
  return !!getToken();
}

function hasRole(role) {
  const session = getSession();
  return !!session && session.role === role;
}

function logout() {
  clearSession();
  window.location.href = "login.html";
}

// Every page includes an <li><a id="nav-auth-link">...</a></li> in its nav —
// keep it in sync with session state without needing per-page wiring.
function syncNavAuthLink() {
  const link = document.getElementById("nav-auth-link");
  if (!link) return;
  if (isLoggedIn()) {
    link.textContent = "Log Out";
    link.href = "#";
    link.onclick = (e) => { e.preventDefault(); logout(); };
  } else {
    link.textContent = "Login";
    link.href = "login.html";
    link.onclick = null;
  }
}

// Doctors get a "Dashboard" link injected just before the login/logout link, on every page
// that includes api.js — avoids hand-editing the nav markup of every single .html file.
// Skipped on doctor-dashboard.html itself, which already has its own static "Dashboard" nav item.
function injectDoctorDashboardLink() {
  if (!hasRole("DOCTOR")) return;
  if (window.location.pathname.endsWith("doctor-dashboard.html")) return;
  const navList = document.querySelector(".nav-links");
  const authLi = document.getElementById("nav-auth-link")?.closest("li");
  if (!navList || !authLi || document.getElementById("nav-doctor-link")) return;

  const li = document.createElement("li");
  const a = document.createElement("a");
  a.id = "nav-doctor-link";
  a.href = "doctor-dashboard.html";
  a.textContent = "Dashboard";
  li.appendChild(a);
  navList.insertBefore(li, authLi);
}

document.addEventListener("DOMContentLoaded", () => {
  syncNavAuthLink();
  injectDoctorDashboardLink();
});

// ---------- Toast notifications ----------
// A clean, non-blocking way to surface backend messages (errors, confirmations) instead of
// native alert()/confirm() boxes. Every toast shows only err.message from a structured ApiError —
// never a raw exception or stack trace.

function ensureToastContainer() {
  let container = document.getElementById("toast-container");
  if (!container) {
    container = document.createElement("div");
    container.id = "toast-container";
    document.body.appendChild(container);
  }
  return container;
}

function showToast(message, type = "success", durationMs = 4500) {
  const container = ensureToastContainer();
  const toast = document.createElement("div");
  toast.className = `toast toast-${type}`;
  toast.textContent = message;
  container.appendChild(toast);

  // Force layout so the enter transition actually plays, then trigger it.
  requestAnimationFrame(() => toast.classList.add("toast-visible"));

  const dismiss = () => {
    toast.classList.remove("toast-visible");
    toast.addEventListener("transitionend", () => toast.remove(), { once: true });
  };
  toast.addEventListener("click", dismiss);
  setTimeout(dismiss, durationMs);
}

// ---------- Formatting ----------

function formatInr(n) {
  // Indian digit grouping (lakh/crore): 150000 -> "1,50,000"
  const num = Math.round(Number(n) || 0);
  const [intPart, ] = String(num).split(".");
  const sign = intPart.startsWith("-") ? "-" : "";
  const digits = intPart.replace("-", "");
  const last3 = digits.slice(-3);
  const rest = digits.slice(0, -3);
  const grouped = rest ? rest.replace(/\B(?=(\d{2})+(?!\d))/g, ",") + "," + last3 : last3;
  return "₹" + sign + grouped;
}

function escapeHtml(str) {
  const div = document.createElement("div");
  div.textContent = str ?? "";
  return div.innerHTML;
}

// Doctor names in the database aren't consistent about whether they already include a "Dr."
// prefix (seed data does, self-registered accounts might not), so anywhere we display a doctor
// name with a "Dr." in front, use this instead of hardcoding "Dr. " + name — it adds the prefix
// only if it isn't already there, so it never renders as "Dr. Dr. Someone".
function formatDoctorName(name) {
  if (!name) return "";
  const trimmed = name.trim();
  return /^dr\.?\s/i.test(trimmed) ? trimmed : "Dr. " + trimmed;
}

// Reads ?key=value pairs from the current page URL
function getUrlParam(key) {
  return new URLSearchParams(window.location.search).get(key);
}

// ---------- Inline field validation ----------

// Matches the backend's @Pattern check: username@domain.tld
const EMAIL_REGEX = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

function isValidEmailFormat(email) {
  return EMAIL_REGEX.test(String(email || "").trim());
}

// Matches the backend's password @Pattern + @Size checks (RegisterRequest / ChangePasswordRequest):
// at least 8 characters, at least one letter and one digit. Keeping this in one place means the
// frontend hint never silently drifts from what the backend will actually accept.
function isValidPasswordFormat(password) {
  const value = String(password || "");
  return value.length >= 8 && /[A-Za-z]/.test(value) && /\d/.test(value);
}

// Shows/clears an inline error under a field. Expects the field's parent
// (usually .form-group) to contain a sibling element with class "field-error".
function setFieldError(inputEl, message) {
  if (!inputEl) return;
  const errEl = inputEl.closest(".form-group")?.querySelector(".field-error")
             || inputEl.parentElement.querySelector(".field-error");
  inputEl.classList.add("invalid");
  if (errEl) {
    if (message) errEl.textContent = message;
    errEl.style.display = "block";
  }
}

function clearFieldError(inputEl) {
  if (!inputEl) return;
  const errEl = inputEl.closest(".form-group")?.querySelector(".field-error")
             || inputEl.parentElement.querySelector(".field-error");
  inputEl.classList.remove("invalid");
  if (errEl) errEl.style.display = "none";
}

// Applies field-level errors returned by the backend (err.fieldErrors) onto a form,
// matching each key to an input with that id. Falls back silently if no match.
function applyBackendFieldErrors(fieldErrors) {
  if (!fieldErrors) return;
  Object.entries(fieldErrors).forEach(([field, message]) => {
    const input = document.getElementById(field);
    if (input) setFieldError(input, message);
  });
}
