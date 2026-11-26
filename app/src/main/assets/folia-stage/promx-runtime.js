(() => {
  const quality = window.PromXStageQuality || { name: "balanced", fps: 45 };
  document.documentElement.dataset.promxQuality = quality.name;
  if (quality.name !== "full") {
    document.documentElement.dataset.reduceMotion = "uiMicroMotion";
  }

  window.PromXStageRuntime = {
    quality,
    setVisibility(visible) {
      document.documentElement.dataset.promxVisible = visible ? "true" : "false";
      if (!visible) {
        document.documentElement.dataset.reduceMotion = "uiMicroMotion";
      } else if (quality.name === "full") {
        delete document.documentElement.dataset.reduceMotion;
      }
    },
    clearCaches() {
      window.requestAnimationFrame(() => {
        document.documentElement.style.transform = "translateZ(0)";
      });
    },
  };
})();
