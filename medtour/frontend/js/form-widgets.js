// ============================================================
// MedTour India — reusable form widgets
// Requires js/countries.js (COUNTRIES, countryFlagEmoji) to be loaded first.
// ============================================================

/**
 * Turns a text input + hidden input + menu <div> into a searchable,
 * flag-labelled country dropdown. No external dependency — the flags are
 * rendered from Unicode regional-indicator symbols via countryFlagEmoji().
 *
 * @param {Object} opts
 * @param {string} opts.inputId       - visible text input the user types/searches in
 * @param {string} opts.menuId        - empty <div> that will hold the dropdown list
 * @param {string} opts.hiddenIso2Id  - hidden input that stores the selected ISO2 code
 * @param {string} [opts.initialIso2] - ISO2 code to preselect (e.g. from a logged-in profile)
 * @param {(country: {name:string, iso2:string}) => void} [opts.onSelect]
 */
function initCountryDropdown(opts) {
  const input = document.getElementById(opts.inputId);
  const menu = document.getElementById(opts.menuId);
  const hidden = document.getElementById(opts.hiddenIso2Id);
  const hiddenName = opts.hiddenNameId ? document.getElementById(opts.hiddenNameId) : null;
  if (!input || !menu || !hidden) return null;

  function render(filterText) {
    const q = (filterText || "").trim().toLowerCase();
    const matches = q
      ? COUNTRIES.filter(c => c.name.toLowerCase().includes(q))
      : COUNTRIES;

    if (matches.length === 0) {
      menu.innerHTML = `<div class="country-select-empty">No countries match "${escapeHtml(filterText)}".</div>`;
      return;
    }

    menu.innerHTML = matches.slice(0, 60).map(c => `
      <button type="button" class="country-select-option" data-iso2="${c.iso2}">
        <span class="country-flag">${countryFlagEmoji(c.iso2)}</span>
        <span>${escapeHtml(c.name)}</span>
      </button>
    `).join("");

    menu.querySelectorAll(".country-select-option").forEach(btn => {
      btn.addEventListener("click", () => {
        const iso2 = btn.getAttribute("data-iso2");
        const country = COUNTRIES.find(c => c.iso2 === iso2);
        selectCountry(country);
      });
    });
  }

  function selectCountry(country) {
    if (!country) return;
    input.value = `${countryFlagEmoji(country.iso2)}  ${country.name}`;
    hidden.value = country.iso2;
    if (hiddenName) hiddenName.value = country.name;
    clearFieldError(input);
    closeMenu();
    if (typeof opts.onSelect === "function") opts.onSelect(country);
  }

  function openMenu() {
    render(hidden.value ? "" : input.value);
    menu.classList.add("open");
  }
  function closeMenu() {
    menu.classList.remove("open");
  }

  input.addEventListener("focus", openMenu);
  input.addEventListener("input", () => {
    hidden.value = ""; // typing invalidates any prior selection until they pick again
    render(input.value);
    menu.classList.add("open");
  });
  document.addEventListener("click", (e) => {
    if (!menu.contains(e.target) && e.target !== input) closeMenu();
  });
  input.addEventListener("keydown", (e) => {
    if (e.key === "Escape") closeMenu();
  });

  if (opts.initialIso2) {
    const country = COUNTRIES.find(c => c.iso2 === opts.initialIso2);
    if (country) selectCountry(country);
  }

  return { selectCountry, getIso2: () => hidden.value };
}

/**
 * Wraps intl-tel-input on a <input type="tel">. Loads the plugin's own
 * validation/formatting utils (Google's libphonenumber port) so we never
 * hand-roll per-country phone regexes.
 *
 * @param {string} inputId
 * @returns {Promise<Object>} resolves to the intl-tel-input instance ("iti")
 */
function initPhoneField(inputId) {
  const ITI_VERSION = "29.2.3";
  const input = document.getElementById(inputId);
  if (!input || typeof window.intlTelInput !== "function") return Promise.resolve(null);

  const iti = window.intlTelInput(input, {
    initialCountry: "in",
    separateDialCode: true,
    countrySearch: true,
    strictMode: true,
    loadUtils: () => import(`https://cdn.jsdelivr.net/npm/intl-tel-input@${ITI_VERSION}/dist/js/utils.js`)
  });

  input.addEventListener("blur", () => {
    validatePhoneField(iti, input);
  });

  return iti.promise.then(() => iti);
}

/** Validates a phone field via its iti instance and shows/clears the inline error. */
function validatePhoneField(iti, input) {
  if (!iti) return true;
  const value = input.value.trim();
  if (!value) {
    setFieldError(input, "Phone number is required.");
    return false;
  }
  if (!iti.isValidNumber()) {
    setFieldError(input, "Enter a valid phone number for the selected country.");
    return false;
  }
  clearFieldError(input);
  return true;
}

/** Returns the E.164 number (e.g. "+919876543210") or null if invalid/empty. */
function getPhoneE164(iti) {
  if (!iti || !iti.isValidNumber()) return null;
  return iti.getNumber(); // defaults to E.164 format
}
