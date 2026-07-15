export const MOTION_BOOTSTRAP_SCRIPT = `(function () {
  if (
    "IntersectionObserver" in window &&
    typeof window.matchMedia === "function" &&
    !window.matchMedia("(prefers-reduced-motion: reduce)").matches
  ) {
    document.documentElement.dataset.motionReady = "true";
  }
})();`;
