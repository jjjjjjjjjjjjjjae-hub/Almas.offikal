(function(){
  'use strict';
  if (window.__almasFastTouchLook) return;
  window.__almasFastTouchLook = true;

  // Extra camera delta added after the game's own touch-look handler.
  // Result is roughly 45-55% faster without changing joystick movement.
  const EXTRA_YAW = 0.00145;
  const EXTRA_PITCH = 0.00120;
  const LOOK_START_X = 0.42;
  const PITCH_LIMIT = 1.48;

  let pointerId = null;
  let lastX = 0;
  let lastY = 0;
  const euler = new THREE.Euler(0, 0, 0, 'YXZ');

  function getCamera(){
    try {
      if (typeof camera !== 'undefined' && camera && camera.isCamera) return camera;
    } catch (_) {}
    return window.camera && window.camera.isCamera ? window.camera : null;
  }

  function isUiTarget(target){
    if (!target || !target.closest) return false;
    return !!target.closest('button,input,select,textarea,a,[role="button"],[data-ui],.joystick,.joystick-zone,.hud-button,.control-button,.weapon-slot');
  }

  function applyLook(dx, dy){
    const cam = getCamera();
    if (!cam || !cam.quaternion) return;

    euler.setFromQuaternion(cam.quaternion, 'YXZ');
    euler.y -= dx * EXTRA_YAW;
    euler.x -= dy * EXTRA_PITCH;
    euler.x = Math.max(-PITCH_LIMIT, Math.min(PITCH_LIMIT, euler.x));
    euler.z = 0;
    cam.quaternion.setFromEuler(euler);
  }

  document.addEventListener('pointerdown', function(e){
    if (pointerId !== null) return;
    if (e.pointerType !== 'touch' && e.pointerType !== 'pen') return;
    if (e.clientX < innerWidth * LOOK_START_X) return;
    if (isUiTarget(e.target)) return;

    pointerId = e.pointerId;
    lastX = e.clientX;
    lastY = e.clientY;
  }, {passive:true});

  document.addEventListener('pointermove', function(e){
    if (e.pointerId !== pointerId) return;
    const dx = e.clientX - lastX;
    const dy = e.clientY - lastY;
    lastX = e.clientX;
    lastY = e.clientY;

    if (Math.abs(dx) + Math.abs(dy) < 0.01) return;
    queueMicrotask(function(){ applyLook(dx, dy); });
  }, {passive:true});

  function release(e){
    if (e.pointerId === pointerId) pointerId = null;
  }
  document.addEventListener('pointerup', release, {passive:true});
  document.addEventListener('pointercancel', release, {passive:true});

  if (window.gameDiagnostics) {
    Object.assign(window.gameDiagnostics, {
      touchLookFaster: true,
      touchLookExtraYaw: EXTRA_YAW,
      touchLookExtraPitch: EXTRA_PITCH,
      touchLookRightSideOnly: true
    });
  }
})();
