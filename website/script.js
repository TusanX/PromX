(() => {
  "use strict";

  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const header = document.getElementById("siteHeader");
  const menuButton = document.querySelector(".menu-toggle");
  const mobileMenu = document.getElementById("mobileMenu");

  const updateHeader = () => {
    header?.classList.toggle("is-scrolled", window.scrollY > 12);
  };
  window.addEventListener("scroll", updateHeader, { passive: true });
  updateHeader();

  let lyricFrame = 0;
  const updateLyricShift = () => {
    lyricFrame = 0;
    const shift = Math.min(120, window.scrollY * 0.035);
    document.documentElement.style.setProperty("--lyric-shift", `${shift.toFixed(2)}px`);
  };
  window.addEventListener("scroll", () => {
    if (!lyricFrame) lyricFrame = requestAnimationFrame(updateLyricShift);
  }, { passive: true });
  updateLyricShift();

  const closeMenu = () => {
    if (!menuButton || !mobileMenu) return;
    menuButton.setAttribute("aria-expanded", "false");
    menuButton.setAttribute("aria-label", "打开导航");
    mobileMenu.classList.remove("is-open");
    header?.classList.remove("is-menu-open");
    document.body.classList.remove("menu-open");
  };

  menuButton?.addEventListener("click", () => {
    const open = menuButton.getAttribute("aria-expanded") !== "true";
    menuButton.setAttribute("aria-expanded", String(open));
    menuButton.setAttribute("aria-label", open ? "关闭导航" : "打开导航");
    mobileMenu?.classList.toggle("is-open", open);
    header?.classList.toggle("is-menu-open", open);
    document.body.classList.toggle("menu-open", open);
  });

  mobileMenu?.querySelectorAll("a").forEach((link) => link.addEventListener("click", closeMenu));
  window.addEventListener("keydown", (event) => {
    if (event.key === "Escape") closeMenu();
  });
  window.addEventListener("resize", () => {
    if (window.innerWidth > 860) closeMenu();
  }, { passive: true });

  const storySteps = Array.from(document.querySelectorAll("[data-story-step]"));
  const storyScreens = Array.from(document.querySelectorAll(".story-screen"));
  const stageIndex = document.getElementById("stageIndex");
  const stageContext = document.getElementById("stageContext");
  const stageTrack = document.getElementById("stageTrack");
  let activeStory = -1;

  const selectStory = (index) => {
    if (index < 0 || index === activeStory || !storySteps[index]) return;
    activeStory = index;
    const step = storySteps[index];
    storyScreens.forEach((screen) => {
      screen.classList.toggle("is-active", screen.dataset.screen === step.dataset.screen);
    });
    storySteps.forEach((item, itemIndex) => item.classList.toggle("is-active", itemIndex === index));
    if (stageIndex) stageIndex.textContent = `${String(index + 1).padStart(2, "0")} — ${String(storySteps.length).padStart(2, "0")}`;
    if (stageContext) stageContext.textContent = step.dataset.context || "界面";
    if (stageTrack) stageTrack.textContent = step.dataset.track || "";
    const storyDevice = document.querySelector(".device--story");
    if (storyDevice && window.gsap && !reduceMotion) {
      window.gsap.fromTo(storyDevice,
        { y: 8, rotateY: 2.2 },
        { y: 0, rotateY: 0, duration: 0.52, ease: "power3.out", overwrite: true });
    }
  };
  selectStory(0);

  if ("IntersectionObserver" in window) {
    const observer = new IntersectionObserver((entries) => {
      const visible = entries
        .filter((entry) => entry.isIntersecting)
        .sort((left, right) => right.intersectionRatio - left.intersectionRatio)[0];
      if (!visible) return;
      const index = storySteps.indexOf(visible.target);
      selectStory(index);
    }, { rootMargin: "-38% 0px -42% 0px", threshold: [0, 0.25, 0.5, 0.75, 1] });
    storySteps.forEach((step) => observer.observe(step));
  }

  const signalCanvas = document.getElementById("signalField");
  const signalHost = document.getElementById("heroProduct");
  const heroDevice = document.querySelector(".device--hero");
  if (signalCanvas && signalHost) {
    const context = signalCanvas.getContext("2d");
    if (context) {
      let width = 0;
      let height = 0;
      let dpr = 1;
      let frame = 0;
      let running = false;
      let visible = true;
      let pointerX = 0.58;
      let pointerY = 0.5;
      let lastFrame = 0;

      const resizeSignal = () => {
        const rect = signalHost.getBoundingClientRect();
        dpr = Math.min(window.devicePixelRatio || 1, 2);
        width = Math.max(1, rect.width);
        height = Math.max(1, rect.height);
        signalCanvas.width = Math.round(width * dpr);
        signalCanvas.height = Math.round(height * dpr);
        context.setTransform(dpr, 0, 0, dpr, 0, 0);
        if (reduceMotion) drawSignal(1600);
      };

      const drawSignal = (timestamp) => {
        context.clearRect(0, 0, width, height);
        const time = timestamp * 0.001;
        const centerX = width * pointerX;
        const centerY = height * pointerY;
        context.lineWidth = 1;

        for (let index = 0; index < 46; index += 1) {
          const baseY = height * 0.11 + (index / 45) * height * 0.78;
          const distance = Math.abs(baseY - centerY) / (height * 0.5);
          const proximity = Math.max(0, 1 - distance);
          const amplitude = 1.2 + proximity * 5.5 + Math.sin(index * 0.67 + time * 0.32) * 0.9;
          context.beginPath();
          for (let x = -8; x <= width + 8; x += 7) {
            const envelope = Math.exp(-Math.pow((x - centerX) / (width * 0.34), 2));
            const wave =
              Math.sin(x * 0.026 + time * 1.05 + index * 0.19) * amplitude * envelope +
              Math.sin(x * 0.011 - time * 0.72 + index * 0.41) * amplitude * 0.34;
            const y = baseY + wave;
            if (x === -8) context.moveTo(x, y);
            else context.lineTo(x, y);
          }
          const accentLine = index % 13 === 0;
          context.strokeStyle = accentLine
            ? `rgba(98, 125, 255, ${0.13 + proximity * 0.08})`
            : `rgba(255, 255, 255, ${0.025 + proximity * 0.045})`;
          context.stroke();
        }

        const playhead = width * (0.16 + ((time * 0.025) % 0.68));
        context.beginPath();
        context.moveTo(playhead, height * 0.12);
        context.lineTo(playhead, height * 0.88);
        context.strokeStyle = "rgba(98, 125, 255, 0.16)";
        context.stroke();
      };

      const loopSignal = (timestamp) => {
        if (!visible || document.hidden) {
          running = false;
          return;
        }
        if (timestamp - lastFrame > 32) {
          drawSignal(timestamp);
          lastFrame = timestamp;
        }
        frame = requestAnimationFrame(loopSignal);
      };

      const startSignal = () => {
        if (reduceMotion || running) return;
        running = true;
        frame = requestAnimationFrame(loopSignal);
      };

      const stopSignal = () => {
        running = false;
        cancelAnimationFrame(frame);
      };

      const onPointerMove = (event) => {
        if (!window.matchMedia("(hover: hover) and (pointer: fine)").matches) return;
        const rect = signalHost.getBoundingClientRect();
        pointerX = Math.min(0.86, Math.max(0.14, (event.clientX - rect.left) / rect.width));
        pointerY = Math.min(0.82, Math.max(0.18, (event.clientY - rect.top) / rect.height));
        if (heroDevice) {
          heroDevice.style.setProperty("--tilt-x", `${(pointerY - 0.5) * 2.2}deg`);
          heroDevice.style.setProperty("--tilt-y", `${(pointerX - 0.5) * 3.4}deg`);
        }
      };

      const resetPointer = () => {
        pointerX = 0.58;
        pointerY = 0.5;
        heroDevice?.style.removeProperty("--tilt-x");
        heroDevice?.style.removeProperty("--tilt-y");
      };

      resizeSignal();
      if (reduceMotion) drawSignal(1600);
      startSignal();
      const resizeObserver = new ResizeObserver(resizeSignal);
      resizeObserver.observe(signalHost);
      signalHost.addEventListener("pointermove", onPointerMove, { passive: true });
      signalHost.addEventListener("pointerleave", resetPointer, { passive: true });
      const visibilityObserver = new IntersectionObserver(([entry]) => {
        visible = entry.isIntersecting;
        if (visible) startSignal();
        else stopSignal();
      }, { rootMargin: "120px" });
      visibilityObserver.observe(signalHost);
      document.addEventListener("visibilitychange", () => {
        if (document.hidden) stopSignal();
        else if (visible) startSignal();
      });
    }
  }

  const gsap = window.gsap;
  const ScrollTrigger = window.ScrollTrigger;
  if (!gsap || !ScrollTrigger || reduceMotion) return;

  gsap.registerPlugin(ScrollTrigger);
  ScrollTrigger.config({ ignoreMobileResize: true });

  const intro = gsap.timeline({ defaults: { ease: "power3.out" } });
  intro
    .from(".site-header", { y: -14, opacity: 0, duration: 0.5 })
    .from(".release-line", { y: 12, opacity: 0, duration: 0.45 }, 0.08)
    .from(".headline-line", { yPercent: 34, opacity: 0, duration: 0.78, stagger: 0.06 }, 0.14)
    .from(".hero-lead", { y: 16, opacity: 0, duration: 0.55 }, 0.38)
    .from(".hero-actions > *", { y: 12, opacity: 0, duration: 0.42, stagger: 0.05 }, 0.48)
    .from(".hero-facts div", { y: 10, opacity: 0, duration: 0.36, stagger: 0.04 }, 0.58)
    .from(".device--hero", { y: 34, opacity: 0, duration: 0.9 }, 0.2)
    .from(".now-playing", { x: -16, opacity: 0, duration: 0.65 }, 0.6)
    .from(".product-rail", { opacity: 0, duration: 0.45, stagger: 0.08 }, 0.5);

  ScrollTrigger.batch("[data-source-row]", {
    start: "top 86%",
    once: true,
    onEnter: (rows) => gsap.from(rows, {
      y: 18,
      opacity: 0,
      duration: 0.55,
      stagger: 0.06,
      ease: "power3.out",
    }),
  });

  gsap.from(".lyric-stage", {
    clipPath: "inset(0 0 14% 0 round 16px)",
    y: 26,
    opacity: 0,
    duration: 0.8,
    ease: "power3.out",
    scrollTrigger: { trigger: ".lyrics-section", start: "top 68%", once: true },
  });

  window.addEventListener("load", () => ScrollTrigger.refresh(), { once: true });
})();
