// ============================================================
// Doctor Dashboard — talks only to /api/doctor/* (see js/api.js for the fetch wrapper).
// Every request carries the doctor's JWT; the backend re-derives the doctor from that token and
// scopes every query to them, so this file never needs to (and never does) pass a doctorId.
// ============================================================

// ---------- Guard ----------
// UX nicety only — the real enforcement is server-side (SecurityConfig: /api/doctor/** -> hasRole("DOCTOR")).
if (!hasRole("DOCTOR")) {
  window.location.href = "login.html?redirect=doctor-dashboard.html";
}

const DAY_LABELS = {
  MONDAY: "Monday", TUESDAY: "Tuesday", WEDNESDAY: "Wednesday", THURSDAY: "Thursday",
  FRIDAY: "Friday", SATURDAY: "Saturday", SUNDAY: "Sunday"
};
// Backend stores/sorts DayOfWeek alphabetically (it's a STRING enum column) — reorder to a real
// calendar week client-side so Monday shows before Sunday instead of Friday-first.
const DAY_ORDER = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];

const loadedSections = new Set();
let currentAppointmentFilter = "";
let modalAppointmentId = null;

// ---------- Sidebar / section switching ----------

function showSection(name) {
  document.querySelectorAll(".dashboard-section").forEach(el => { el.hidden = el.id !== `section-${name}`; });
  document.querySelectorAll(".dashboard-nav-item[data-section]").forEach(btn => {
    btn.classList.toggle("active", btn.dataset.section === name);
  });
  loadSection(name);
}

function loadSection(name, force = false) {
  if (loadedSections.has(name) && !force) return;
  loadedSections.add(name);
  switch (name) {
    case "overview": loadOverview(); break;
    case "profile": loadProfile(); break;
    case "appointments": loadAppointments(); break;
    case "patients": loadPatients(); break;
    case "availability": loadAvailability(); break;
    case "history": loadHistory(); break;
    case "notifications": loadNotifications(); break;
  }
}

document.querySelectorAll(".dashboard-nav-item[data-section]").forEach(btn => {
  btn.addEventListener("click", () => showSection(btn.dataset.section));
});
document.getElementById("logout-btn").addEventListener("click", logout);

// ---------- Sidebar profile snippet + notification badge (loaded once, up front) ----------

async function loadSidebarSummary() {
  try {
    const profile = await apiGet("/doctor/profile");
    document.getElementById("sidebar-name").textContent = formatDoctorName(profile.name);
    document.getElementById("sidebar-specialization").textContent = profile.specialization;
    document.getElementById("sidebar-avatar").textContent = initials(profile.name);
  } catch (err) {
    document.getElementById("sidebar-name").textContent = "Doctor";
  }
  refreshNotifBadge();
}

async function refreshNotifBadge() {
  try {
    const notifications = await apiGet("/doctor/notifications");
    const unread = notifications.filter(n => !n.read).length;
    const badge = document.getElementById("notif-badge");
    if (unread > 0) {
      badge.textContent = unread > 99 ? "99+" : unread;
      badge.style.display = "inline-block";
    } else {
      badge.style.display = "none";
    }
  } catch { /* non-critical — leave badge as-is */ }
}

function initials(name) {
  if (!name) return "Dr";
  const parts = name.replace(/^Dr\.?\s*/i, "").trim().split(/\s+/);
  return (parts[0]?.[0] || "D").toUpperCase() + (parts[1]?.[0] || "").toUpperCase();
}

// ---------- Overview ----------

async function loadOverview() {
  const tbody = document.getElementById("overview-table-body");
  tbody.innerHTML = `<tr><td colspan="4"><span class="spinner"></span>Loading…</td></tr>`;
  try {
    const [stats, appointments] = await Promise.all([
      apiGet("/doctor/dashboard"),
      apiGet("/doctor/appointments")
    ]);
    document.getElementById("stat-today").textContent = stats.todayAppointments;
    document.getElementById("stat-upcoming").textContent = stats.upcomingAppointments;
    document.getElementById("stat-patients").textContent = stats.totalPatients;
    document.getElementById("stat-pending").textContent = stats.pendingRequests;

    const recent = appointments.slice(0, 5);
    tbody.innerHTML = recent.length ? recent.map(a => `
      <tr>
        <td>${escapeHtml(a.patientName)}</td>
        <td>${escapeHtml(a.preferredDate)}</td>
        <td>${escapeHtml(a.treatmentName || "—")}</td>
        <td><span class="status-pill status-${a.status}">${escapeHtml(a.status)}</span></td>
      </tr>
    `).join("") : `<tr><td colspan="4">No appointment requests yet.</td></tr>`;
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="4">Could not load dashboard data. (${escapeHtml(err.message)})</td></tr>`;
  }
}
document.getElementById("overview-refresh").addEventListener("click", () => loadSection("overview", true));

// ---------- Profile ----------

async function loadProfile() {
  const successAlert = document.getElementById("profile-success");
  const errorAlert = document.getElementById("profile-error");
  successAlert.style.display = "none";
  errorAlert.style.display = "none";
  try {
    const profile = await apiGet("/doctor/profile");
    document.getElementById("profile-name").value = profile.name;
    document.getElementById("profile-specialization").value = profile.specialization;
    document.getElementById("profile-experience").value = profile.experienceYears;
    document.getElementById("profile-fee").value = profile.consultationFeeInr;
    document.getElementById("profile-image").value = profile.imageUrl || "";
    document.getElementById("profile-hospital").value = profile.hospitalName || "";
  } catch (err) {
    errorAlert.style.display = "block";
    errorAlert.textContent = "Could not load your profile: " + err.message;
  }
}

document.getElementById("profile-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const successAlert = document.getElementById("profile-success");
  const errorAlert = document.getElementById("profile-error");
  successAlert.style.display = "none";
  errorAlert.style.display = "none";

  const btn = document.getElementById("profile-save-btn");
  btn.disabled = true;
  const originalText = btn.textContent;
  btn.innerHTML = `<span class="spinner"></span>Saving…`;

  try {
    const updated = await apiPut("/doctor/profile", {
      name: document.getElementById("profile-name").value.trim(),
      specialization: document.getElementById("profile-specialization").value.trim(),
      experienceYears: Number(document.getElementById("profile-experience").value),
      consultationFeeInr: Number(document.getElementById("profile-fee").value),
      imageUrl: document.getElementById("profile-image").value.trim()
    });
    document.getElementById("sidebar-name").textContent = formatDoctorName(updated.name);
    document.getElementById("sidebar-specialization").textContent = updated.specialization;
    document.getElementById("sidebar-avatar").textContent = initials(updated.name);
    successAlert.style.display = "block";
    successAlert.textContent = "Profile updated.";
  } catch (err) {
    errorAlert.style.display = "block";
    errorAlert.textContent = err.message;
    applyBackendFieldErrors(err.fieldErrors);
  } finally {
    btn.disabled = false;
    btn.textContent = originalText;
  }
});

// ---------- Appointments ----------

document.querySelectorAll("#appointments-tabs .dashboard-tab").forEach(tab => {
  tab.addEventListener("click", () => {
    document.querySelectorAll("#appointments-tabs .dashboard-tab").forEach(t => t.classList.remove("active"));
    tab.classList.add("active");
    currentAppointmentFilter = tab.dataset.status;
    renderAppointmentsTable();
  });
});
document.getElementById("appointments-refresh").addEventListener("click", () => loadSection("appointments", true));

let cachedAppointments = [];

async function loadAppointments() {
  const tbody = document.getElementById("appointments-table-body");
  tbody.innerHTML = `<tr><td colspan="6"><span class="spinner"></span>Loading…</td></tr>`;
  try {
    cachedAppointments = await apiGet("/doctor/appointments");
    renderAppointmentsTable();
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="6">Could not load appointments. (${escapeHtml(err.message)})</td></tr>`;
  }
}

function renderAppointmentsTable() {
  const tbody = document.getElementById("appointments-table-body");
  const rows = currentAppointmentFilter
    ? cachedAppointments.filter(a => a.status === currentAppointmentFilter)
    : cachedAppointments;

  tbody.innerHTML = rows.length ? rows.map(a => `
    <tr>
      <td>${escapeHtml(a.patientName)}<br><span class="card-meta">${escapeHtml(a.country || "")}</span></td>
      <td>${escapeHtml(a.preferredDate)}</td>
      <td>${escapeHtml(a.treatmentName || "—")}</td>
      <td>${formatInr(a.estimatedTotalInr || 0)}</td>
      <td><span class="status-pill status-${a.status}">${escapeHtml(a.status)}</span></td>
      <td>${appointmentActionButtons(a)}</td>
    </tr>
  `).join("") : `<tr><td colspan="6">No appointments in this view.</td></tr>`;

  tbody.querySelectorAll("[data-action]").forEach(btn => {
    btn.addEventListener("click", () => handleAppointmentAction(btn.dataset.action, Number(btn.dataset.id)));
  });
}

function appointmentActionButtons(a) {
  const btns = [`<button class="btn btn-ghost btn-sm" data-action="view" data-id="${a.id}">View</button>`];
  if (a.status === "Pending") {
    btns.push(`<button class="btn btn-secondary btn-sm" data-action="confirm" data-id="${a.id}">Accept</button>`);
    btns.push(`<button class="btn btn-outline btn-sm" data-action="reject" data-id="${a.id}">Reject</button>`);
  }
  if (a.status === "Confirmed") {
    btns.push(`<button class="btn btn-secondary btn-sm" data-action="complete" data-id="${a.id}">Complete</button>`);
    btns.push(`<button class="btn btn-outline btn-sm" data-action="reject" data-id="${a.id}">Reject</button>`);
  }
  if (a.status === "Pending" || a.status === "Confirmed") {
    btns.push(`<button class="btn btn-ghost btn-sm" data-action="reschedule" data-id="${a.id}">Reschedule</button>`);
  }
  return `<div class="action-btns">${btns.join("")}</div>`;
}

async function handleAppointmentAction(action, id) {
  if (action === "view") return openAppointmentModal(id, false);
  if (action === "reschedule") return openAppointmentModal(id, true);

  const statusMap = { confirm: "Confirmed", reject: "Rejected", complete: "Completed" };
  const newStatus = statusMap[action];
  if (!newStatus) return;
  if (action === "reject" && !confirm("Reject this appointment? The patient will be notified by email.")) return;

  try {
    await apiPut(`/doctor/appointments/${id}/status`, { status: newStatus });
    await loadSection("appointments", true);
    loadSection("overview", true);
    refreshNotifBadge();
    showToast(`Appointment #${id} is now ${newStatus}.`, "success");
  } catch (err) {
    showToast("Could not update the appointment: " + err.message, "error");
  }
}

// ---------- Appointment modal (view details / reschedule) ----------

const modalOverlay = document.getElementById("appt-modal-overlay");
const rescheduleBlock = document.getElementById("appt-reschedule-block");
const rescheduleConfirmBtn = document.getElementById("appt-modal-reschedule-confirm");

function openAppointmentModal(id, isReschedule) {
  const appt = cachedAppointments.find(a => a.id === id);
  if (!appt) return;
  modalAppointmentId = id;

  document.getElementById("appt-modal-title").textContent = `Appointment #${appt.id}`;
  document.getElementById("appt-modal-body").innerHTML = `
    <p><strong>Patient:</strong> ${escapeHtml(appt.patientName)}</p>
    <p><strong>Email:</strong> ${escapeHtml(appt.email)}</p>
    <p><strong>Phone:</strong> ${escapeHtml(appt.phone)}</p>
    <p><strong>Country:</strong> ${escapeHtml(appt.country || "—")}</p>
    <p><strong>Treatment:</strong> ${escapeHtml(appt.treatmentName || "—")}</p>
    <p><strong>Hospital:</strong> ${escapeHtml(appt.hospitalName || "—")}</p>
    <p><strong>Preferred Date:</strong> ${escapeHtml(appt.preferredDate)}</p>
    <p><strong>Estimated Cost:</strong> ${formatInr(appt.estimatedTotalInr || 0)}</p>
    <p><strong>Status:</strong> <span class="status-pill status-${appt.status}">${escapeHtml(appt.status)}</span></p>
    ${appt.message ? `<p><strong>Patient Note:</strong> ${escapeHtml(appt.message)}</p>` : ""}
  `;

  rescheduleBlock.hidden = !isReschedule;
  rescheduleConfirmBtn.hidden = !isReschedule;
  if (isReschedule) {
    document.getElementById("reschedule-date").value = appt.preferredDate;
    document.getElementById("reschedule-date").min = new Date().toISOString().split("T")[0];
  }

  modalOverlay.classList.add("open");
}

document.getElementById("appt-modal-close").addEventListener("click", () => modalOverlay.classList.remove("open"));
modalOverlay.addEventListener("click", (e) => { if (e.target === modalOverlay) modalOverlay.classList.remove("open"); });

rescheduleConfirmBtn.addEventListener("click", async () => {
  const newDate = document.getElementById("reschedule-date").value;
  if (!newDate) return;
  rescheduleConfirmBtn.disabled = true;
  try {
    await apiPut(`/doctor/appointments/${modalAppointmentId}/reschedule`, { preferredDate: newDate });
    modalOverlay.classList.remove("open");
    await loadSection("appointments", true);
    loadSection("overview", true);
    showToast("Appointment rescheduled — the patient has been notified.", "success");
  } catch (err) {
    showToast("Could not reschedule: " + err.message, "error");
  } finally {
    rescheduleConfirmBtn.disabled = false;
  }
});

// ---------- Patients ----------

async function loadPatients() {
  const tbody = document.getElementById("patients-table-body");
  tbody.innerHTML = `<tr><td colspan="6"><span class="spinner"></span>Loading…</td></tr>`;
  try {
    const patients = await apiGet("/doctor/patients");
    tbody.innerHTML = patients.length ? patients.map(p => `
      <tr>
        <td>${escapeHtml(p.name)}</td>
        <td>${escapeHtml(p.email)}<br><span class="card-meta">${escapeHtml(p.phone)}</span></td>
        <td>${escapeHtml(p.country || "—")}</td>
        <td>${p.appointmentCount}</td>
        <td>${escapeHtml(p.lastAppointmentDate)}</td>
        <td><span class="status-pill status-${p.lastStatus}">${escapeHtml(p.lastStatus)}</span></td>
      </tr>
    `).join("") : `<tr><td colspan="6">No patients yet — they'll show up here after their first booking with you.</td></tr>`;
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="6">Could not load patients. (${escapeHtml(err.message)})</td></tr>`;
  }
}

// ---------- Availability ----------

async function loadAvailability() {
  const list = document.getElementById("availability-list");
  list.innerHTML = `<span class="spinner"></span>Loading…`;
  try {
    const slots = await apiGet("/doctor/availability");
    slots.sort((a, b) => DAY_ORDER.indexOf(a.dayOfWeek) - DAY_ORDER.indexOf(b.dayOfWeek) || a.startTime.localeCompare(b.startTime));
    list.innerHTML = slots.length ? slots.map(s => `
      <div class="availability-row ${s.active ? "" : "inactive"}">
        <div>
          <strong>${DAY_LABELS[s.dayOfWeek] || s.dayOfWeek}</strong>
          <span class="card-meta">${s.startTime} – ${s.endTime}${s.active ? "" : " (inactive)"}</span>
        </div>
        <button class="btn btn-ghost btn-sm" data-delete-avail="${s.id}">Remove</button>
      </div>
    `).join("") : `<p class="card-meta">No availability slots set yet — add one above.</p>`;

    list.querySelectorAll("[data-delete-avail]").forEach(btn => {
      btn.addEventListener("click", async () => {
        if (!confirm("Remove this availability slot?")) return;
        try {
          await apiDelete(`/doctor/availability/${btn.dataset.deleteAvail}`);
          loadSection("availability", true);
        } catch (err) {
          showToast("Could not remove slot: " + err.message, "error");
        }
      });
    });
  } catch (err) {
    list.innerHTML = `Could not load availability. (${escapeHtml(err.message)})`;
  }
}

document.getElementById("availability-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const errorAlert = document.getElementById("availability-error");
  errorAlert.style.display = "none";

  const btn = document.getElementById("avail-add-btn");
  btn.disabled = true;

  try {
    await apiPost("/doctor/availability", {
      dayOfWeek: document.getElementById("avail-day").value,
      startTime: document.getElementById("avail-start").value,
      endTime: document.getElementById("avail-end").value,
      active: true
    });
    document.getElementById("availability-form").reset();
    loadSection("availability", true);
  } catch (err) {
    errorAlert.style.display = "block";
    errorAlert.textContent = err.message;
  } finally {
    btn.disabled = false;
  }
});

// ---------- Consultation History ----------

async function loadHistory() {
  const tbody = document.getElementById("history-table-body");
  tbody.innerHTML = `<tr><td colspan="5"><span class="spinner"></span>Loading…</td></tr>`;
  try {
    const history = await apiGet("/doctor/consultation-history");
    tbody.innerHTML = history.length ? history.map(a => `
      <tr>
        <td>${escapeHtml(a.patientName)}</td>
        <td>${escapeHtml(a.preferredDate)}</td>
        <td>${escapeHtml(a.treatmentName || "—")}</td>
        <td>${escapeHtml(a.hospitalName || "—")}</td>
        <td>${formatInr(a.estimatedTotalInr || 0)}</td>
      </tr>
    `).join("") : `<tr><td colspan="5">No completed consultations yet.</td></tr>`;
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="5">Could not load history. (${escapeHtml(err.message)})</td></tr>`;
  }
}

// ---------- Notifications ----------

async function loadNotifications() {
  const list = document.getElementById("notifications-list");
  list.innerHTML = `<span class="spinner"></span>Loading…`;
  try {
    const notifications = await apiGet("/doctor/notifications");
    list.innerHTML = notifications.length ? notifications.map(n => `
      <div class="notification-item ${n.read ? "" : "unread"}">
        <div>
          <div class="notification-title">${escapeHtml(n.title)}</div>
          <div class="notification-message">${escapeHtml(n.message)}</div>
          <div class="notification-time">${new Date(n.createdAt).toLocaleString()}</div>
        </div>
        ${n.read ? "" : `<button class="btn btn-ghost btn-sm" data-mark-read="${n.id}">Mark read</button>`}
      </div>
    `).join("") : `<p class="card-meta">No notifications yet.</p>`;

    list.querySelectorAll("[data-mark-read]").forEach(btn => {
      btn.addEventListener("click", async () => {
        try {
          await apiPut(`/doctor/notifications/${btn.dataset.markRead}/read`, {});
          loadSection("notifications", true);
          refreshNotifBadge();
        } catch (err) {
          showToast("Could not update notification: " + err.message, "error");
        }
      });
    });
  } catch (err) {
    list.innerHTML = `Could not load notifications. (${escapeHtml(err.message)})`;
  }
}

document.getElementById("mark-all-read-btn").addEventListener("click", async () => {
  try {
    await apiPut("/doctor/notifications/read-all", {});
    loadSection("notifications", true);
    refreshNotifBadge();
  } catch (err) {
    showToast("Could not mark all as read: " + err.message, "error");
  }
});

// ---------- Settings ----------

document.getElementById("password-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const successAlert = document.getElementById("settings-success");
  const errorAlert = document.getElementById("settings-error");
  successAlert.style.display = "none";
  errorAlert.style.display = "none";

  const newPasswordInput = document.getElementById("new-password");
  if (!isValidPasswordFormat(newPasswordInput.value)) {
    setFieldError(newPasswordInput, "Password must be at least 8 characters and include a letter and a number.");
    errorAlert.style.display = "block";
    errorAlert.textContent = "Please fix the highlighted field before submitting.";
    return;
  }
  clearFieldError(newPasswordInput);

  const btn = document.getElementById("password-save-btn");
  btn.disabled = true;
  const originalText = btn.textContent;
  btn.innerHTML = `<span class="spinner"></span>Updating…`;

  try {
    await apiPut("/doctor/settings/password", {
      currentPassword: document.getElementById("current-password").value,
      newPassword: document.getElementById("new-password").value
    });
    successAlert.style.display = "block";
    successAlert.textContent = "Password updated.";
    document.getElementById("password-form").reset();
  } catch (err) {
    errorAlert.style.display = "block";
    errorAlert.textContent = err.message;
  } finally {
    btn.disabled = false;
    btn.textContent = originalText;
  }
});

// ---------- Init ----------

loadSidebarSummary();
loadSection("overview");
