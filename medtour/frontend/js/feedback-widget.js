// ============================================================
// MedTour India — footer feedback widget (star rating + form)
// Safe to include on any page; does nothing if #feedback-form isn't present.
// ============================================================

(function () {
  const form = document.getElementById("feedback-form");
  if (!form) return;

  const nameInput = document.getElementById("feedback-name");
  const emailInput = document.getElementById("feedback-email");
  const commentInput = document.getElementById("feedback-comment");
  const ratingError = document.getElementById("feedback-rating-error");
  const successAlert = document.getElementById("feedback-success");
  const errorAlert = document.getElementById("feedback-error");
  const submitBtn = document.getElementById("feedback-submit-btn");

  function getSelectedRating() {
    const checked = form.querySelector('input[name="feedback-rating"]:checked');
    return checked ? Number(checked.value) : null;
  }

  // ---- Inline validation on blur / change ----
  nameInput.addEventListener("blur", () => {
    nameInput.value.trim() ? clearFieldError(nameInput) : setFieldError(nameInput, "Please enter your name.");
  });
  emailInput.addEventListener("blur", () => {
    isValidEmailFormat(emailInput.value) ? clearFieldError(emailInput) : setFieldError(emailInput, "Enter a valid email, e.g. name@example.com.");
  });
  commentInput.addEventListener("blur", () => {
    commentInput.value.trim() ? clearFieldError(commentInput) : setFieldError(commentInput, "Please share a few words of feedback.");
  });
  form.querySelectorAll('input[name="feedback-rating"]').forEach(radio => {
    radio.addEventListener("change", () => { ratingError.style.display = "none"; });
  });

  function validate() {
    let ok = true;

    if (!nameInput.value.trim()) { setFieldError(nameInput, "Please enter your name."); ok = false; }
    else clearFieldError(nameInput);

    if (!isValidEmailFormat(emailInput.value)) {
      setFieldError(emailInput, "Enter a valid email, e.g. name@example.com.");
      ok = false;
    } else clearFieldError(emailInput);

    if (!getSelectedRating()) { ratingError.style.display = "block"; ok = false; }
    else ratingError.style.display = "none";

    if (!commentInput.value.trim()) { setFieldError(commentInput, "Please share a few words of feedback."); ok = false; }
    else clearFieldError(commentInput);

    return ok;
  }

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    successAlert.style.display = "none";
    errorAlert.style.display = "none";

    if (!validate()) {
      errorAlert.style.display = "block";
      errorAlert.textContent = "Please fix the highlighted fields before submitting.";
      return;
    }

    submitBtn.disabled = true;
    submitBtn.innerHTML = `<span class="spinner"></span>Submitting…`;

    try {
      // Name/email/comment are also validated and sanitized server-side (FeedbackController) —
      // this form never gets to skip that just because the frontend already checked.
      const result = await apiPost("/feedback", {
        name: nameInput.value.trim(),
        email: emailInput.value.trim(),
        rating: getSelectedRating(),
        comment: commentInput.value.trim()
      });
      successAlert.style.display = "block";
      successAlert.textContent = result.message || "Thank you! Your feedback helps us improve the healthcare experience.";
      form.reset();
    } catch (err) {
      errorAlert.style.display = "block";
      errorAlert.textContent = err.message;
      applyBackendFieldErrors(err.fieldErrors);
    } finally {
      submitBtn.disabled = false;
      submitBtn.textContent = "Submit Feedback";
    }
  });
})();
