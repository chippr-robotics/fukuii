/* ============================================================
   Fukuii landing page — interactive behaviors
   - hex-grid canvas background
   - reveal-on-scroll
   - count-up stats
   - feature tabs
   - copy-to-clipboard
   - card mouse-light tracking
   ============================================================ */
(function () {
  "use strict";

  function ready(fn) {
    if (document.readyState !== "loading") fn();
    else document.addEventListener("DOMContentLoaded", fn);
  }

  function prefersReducedMotion() {
    return window.matchMedia &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  }

  /* ---------- Hex-grid canvas ---------- */
  function startHexCanvas() {
    var canvas = document.querySelector(".fk-landing canvas.fk-hex-canvas");
    if (!canvas) return;
    if (prefersReducedMotion()) return;

    var ctx = canvas.getContext("2d");
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    var width = 0, height = 0;
    var nodes = [];
    var raf = 0;
    var t = 0;

    function resize() {
      var rect = canvas.parentElement.getBoundingClientRect();
      width = rect.width;
      height = rect.height;
      canvas.width = Math.floor(width * dpr);
      canvas.height = Math.floor(height * dpr);
      canvas.style.width = width + "px";
      canvas.style.height = height + "px";
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      buildNodes();
    }

    function buildNodes() {
      nodes = [];
      var spacing = 80;
      var cols = Math.ceil(width / spacing) + 2;
      var rows = Math.ceil(height / spacing) + 2;
      for (var r = -1; r < rows; r++) {
        for (var c = -1; c < cols; c++) {
          var x = c * spacing + (r % 2 === 0 ? 0 : spacing / 2);
          var y = r * (spacing * 0.866);
          nodes.push({
            x: x,
            y: y,
            ox: x,
            oy: y,
            phase: Math.random() * Math.PI * 2,
            speed: 0.4 + Math.random() * 0.5,
          });
        }
      }
    }

    function draw() {
      t += 0.006;
      ctx.clearRect(0, 0, width, height);

      // Soft connecting lines
      ctx.lineWidth = 1;
      for (var i = 0; i < nodes.length; i++) {
        var n = nodes[i];
        var sway = Math.sin(t * n.speed + n.phase) * 6;
        n.x = n.ox + sway;
        n.y = n.oy + Math.cos(t * n.speed * 0.7 + n.phase) * 4;
      }

      // Lines between near neighbours (within ~spacing+10)
      ctx.strokeStyle = "rgba(184, 150, 79, 0.10)";
      for (var i = 0; i < nodes.length; i++) {
        for (var j = i + 1; j < nodes.length; j++) {
          var a = nodes[i], b = nodes[j];
          var dx = a.x - b.x, dy = a.y - b.y;
          var d2 = dx * dx + dy * dy;
          if (d2 < 95 * 95) {
            ctx.beginPath();
            ctx.moveTo(a.x, a.y);
            ctx.lineTo(b.x, b.y);
            ctx.stroke();
          }
        }
      }

      // Nodes (subtle pulse)
      for (var i = 0; i < nodes.length; i++) {
        var n = nodes[i];
        var pulse = 0.5 + 0.5 * Math.sin(t * 1.3 + n.phase);
        var radius = 1.2 + pulse * 1.6;
        ctx.fillStyle = "rgba(214, 181, 107, " + (0.25 + pulse * 0.45) + ")";
        ctx.beginPath();
        ctx.arc(n.x, n.y, radius, 0, Math.PI * 2);
        ctx.fill();
      }

      raf = requestAnimationFrame(draw);
    }

    resize();
    window.addEventListener("resize", function () {
      cancelAnimationFrame(raf);
      resize();
      raf = requestAnimationFrame(draw);
    }, { passive: true });
    raf = requestAnimationFrame(draw);
  }

  /* ---------- Reveal-on-scroll ---------- */
  function startReveal() {
    var els = document.querySelectorAll(".fk-landing .fk-reveal, .fk-landing .fk-reveal-stagger");
    if (!els.length) return;
    if (!("IntersectionObserver" in window)) {
      els.forEach(function (el) { el.classList.add("is-visible"); });
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add("is-visible");
          io.unobserve(entry.target);
        }
      });
    }, { threshold: 0.12, rootMargin: "0px 0px -40px 0px" });
    els.forEach(function (el) { io.observe(el); });
  }

  /* ---------- Count-up stats ---------- */
  function startCounters() {
    var nums = document.querySelectorAll(".fk-landing [data-countup]");
    if (!nums.length) return;
    if (!("IntersectionObserver" in window) || prefersReducedMotion()) {
      nums.forEach(function (el) { el.textContent = el.getAttribute("data-countup"); });
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        var el = entry.target;
        io.unobserve(el);
        var target = parseFloat(el.getAttribute("data-countup"));
        var suffix = el.getAttribute("data-suffix") || "";
        var prefix = el.getAttribute("data-prefix") || "";
        var decimals = parseInt(el.getAttribute("data-decimals") || "0", 10);
        var duration = 1400;
        var start = performance.now();
        function tick(now) {
          var p = Math.min(1, (now - start) / duration);
          var eased = 1 - Math.pow(1 - p, 3);
          var v = target * eased;
          el.textContent = prefix + v.toFixed(decimals).replace(/\B(?=(\d{3})+(?!\d))/g, ",") + suffix;
          if (p < 1) requestAnimationFrame(tick);
          else el.textContent = prefix + target.toFixed(decimals).replace(/\B(?=(\d{3})+(?!\d))/g, ",") + suffix;
        }
        requestAnimationFrame(tick);
      });
    }, { threshold: 0.4 });
    nums.forEach(function (el) { io.observe(el); });
  }

  /* ---------- Tabs ---------- */
  function startTabs() {
    var tabsRoots = document.querySelectorAll(".fk-landing .fk-tabs");
    tabsRoots.forEach(function (root) {
      var tabs = root.querySelectorAll(".fk-tab");
      var panels = root.querySelectorAll(".fk-tabs__panel");
      function activate(idx) {
        tabs.forEach(function (t, i) {
          t.setAttribute("aria-selected", i === idx ? "true" : "false");
          t.setAttribute("tabindex", i === idx ? "0" : "-1");
        });
        panels.forEach(function (p, i) {
          p.setAttribute("data-active", i === idx ? "true" : "false");
        });
      }
      tabs.forEach(function (t, i) {
        t.addEventListener("click", function () { activate(i); });
        t.addEventListener("keydown", function (e) {
          if (e.key === "ArrowDown" || e.key === "ArrowRight") {
            e.preventDefault();
            var next = (i + 1) % tabs.length;
            tabs[next].focus(); activate(next);
          } else if (e.key === "ArrowUp" || e.key === "ArrowLeft") {
            e.preventDefault();
            var prev = (i - 1 + tabs.length) % tabs.length;
            tabs[prev].focus(); activate(prev);
          }
        });
      });
    });
  }

  /* ---------- Copy-to-clipboard ---------- */
  function startCopyButtons() {
    var btns = document.querySelectorAll(".fk-landing .fk-code__copy");
    btns.forEach(function (btn) {
      btn.addEventListener("click", function () {
        var pre = btn.closest(".fk-code").querySelector("pre");
        if (!pre) return;
        var text = pre.innerText;
        var done = function () {
          var orig = btn.textContent;
          btn.textContent = "Copied";
          btn.setAttribute("data-copied", "true");
          setTimeout(function () {
            btn.textContent = orig || "Copy";
            btn.removeAttribute("data-copied");
          }, 1600);
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(done, function () {
            fallbackCopy(text); done();
          });
        } else {
          fallbackCopy(text); done();
        }
      });
    });
  }

  function fallbackCopy(text) {
    var ta = document.createElement("textarea");
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand("copy"); } catch (e) {}
    document.body.removeChild(ta);
  }

  /* ---------- Card mouse-light tracking ---------- */
  function startCardLights() {
    var cards = document.querySelectorAll(".fk-landing .fk-card");
    cards.forEach(function (card) {
      card.addEventListener("mousemove", function (e) {
        var rect = card.getBoundingClientRect();
        card.style.setProperty("--mx", (e.clientX - rect.left) + "px");
        card.style.setProperty("--my", (e.clientY - rect.top) + "px");
      }, { passive: true });
    });
  }

  /* ---------- boot ---------- */
  ready(function () {
    if (!document.querySelector(".fk-landing")) return;
    startHexCanvas();
    startReveal();
    startCounters();
    startTabs();
    startCopyButtons();
    startCardLights();
  });

  // MkDocs Material uses instant navigation; re-init when content swaps.
  if (window.document$ && typeof window.document$.subscribe === "function") {
    window.document$.subscribe(function () {
      if (!document.querySelector(".fk-landing")) return;
      startHexCanvas();
      startReveal();
      startCounters();
      startTabs();
      startCopyButtons();
      startCardLights();
    });
  }
})();
