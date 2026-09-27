(function(){
  'use strict';
  if (window.__almasRunFovFx) return;
  window.__almasRunFovFx = true;

  const BASE_FOV = 72;
  const RUN_FOV = 82;
  const CROSSHAIR_RUN_SCALE = 1.45;
  let blend = 0;
  let last = performance.now();

  function frame(now){
    const dt = Math.min(0.1, Math.max(0, (now - last) / 1000));
    last = now;

    let running = false;
    try { running = !!localRunning; } catch (_) {}

    const target = running ? 1 : 0;
    const response = running ? 8.5 : 10.5;
    blend += (target - blend) * (1 - Math.exp(-response * dt));

    try {
      const nextFov = BASE_FOV + (RUN_FOV - BASE_FOV) * blend;
      if (camera && Math.abs(camera.fov - nextFov) > 0.01) {
        camera.fov = nextFov;
        camera.updateProjectionMatrix();
      }
    } catch (_) {}

    const crosshair = document.getElementById('crosshair');
    if (crosshair) {
      const scale = 1 + (CROSSHAIR_RUN_SCALE - 1) * blend;
      crosshair.style.transform = 'translate(-50%,-50%) scale(' + scale.toFixed(3) + ')';
    }

    requestAnimationFrame(frame);
  }

  requestAnimationFrame(frame);
})();
