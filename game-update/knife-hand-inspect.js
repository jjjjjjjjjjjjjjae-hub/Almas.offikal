(function(){
  'use strict';
  if (window.__almasKnifeHandInspect) return;
  window.__almasKnifeHandInspect = true;

  const INSPECT_AFTER_IDLE = 4.5;
  const INSPECT_DURATION = 2.35;
  const NEXT_INSPECT_MIN = 9.0;
  const NEXT_INSPECT_MAX = 13.0;

  function smooth01(t){
    t = Math.max(0, Math.min(1, t));
    return t * t * (3 - 2 * t);
  }

  function install(){
    let fv;
    try { fv = forearmView; } catch (_) { fv = null; }
    if (!fv || !fv.update || fv.__knifeInspectPatched) {
      requestAnimationFrame(install);
      return;
    }

    fv.__knifeInspectPatched = true;
    const originalUpdate = fv.update.bind(fv);
    const knife = fv.knifeMesh;
    if (!knife) return;

    const baseKnifePosition = knife.position.clone();
    const baseKnifeQuaternion = knife.quaternion.clone();
    const spinAxis = new THREE.Vector3(0, 0, 1);
    const spinQ = new THREE.Quaternion();

    let idleTime = 0;
    let inspectTime = -1;
    let nextInspect = INSPECT_AFTER_IDLE;

    function resetKnife(){
      knife.position.copy(baseKnifePosition);
      knife.quaternion.copy(baseKnifeQuaternion);
    }

    fv.update = function(dt, running, moving){
      originalUpdate(dt, running, moving);

      const knifeSelected = this.weaponSystem && this.weaponSystem.active === 'knife';
      const attacking = this.knifeCombat && this.knifeCombat.elapsed >= 0;

      if (!knifeSelected || running || moving || attacking || !this.model.visible) {
        idleTime = 0;
        inspectTime = -1;
        resetKnife();
        return;
      }

      // Very small idle breathing/sway so the hands are not perfectly frozen.
      idleTime += dt;
      const breath = Math.sin(this.time * 1.45);
      const micro = Math.sin(this.time * 0.83 + 0.7);
      this.motion.position.y += breath * 0.0028;
      this.motion.position.x += micro * 0.0018;
      this.motion.rotation.x += breath * 0.0035;
      this.motion.rotation.z += micro * 0.0025;

      if (inspectTime < 0 && idleTime >= nextInspect) inspectTime = 0;

      if (inspectTime >= 0) {
        inspectTime += dt;
        const p = Math.min(1, inspectTime / INSPECT_DURATION);
        const eased = smooth01(p);
        const flourish = Math.sin(Math.PI * p);

        // Reuse the previously-unused authored hand-motion section from the current GLB.
        // This keeps both hands moving naturally while the knife flourish is overlaid.
        if (this.mixer && this.action) {
          const handTime = THREE.MathUtils.lerp(1.36, 2.88, eased);
          this.mixer.setTime(handTime);
          this.model.updateMatrixWorld(true);
        }

        // Knife inspect/twirl: two controlled rotations with a small lift and return.
        resetKnife();
        spinQ.setFromAxisAngle(spinAxis, Math.PI * 4 * eased);
        knife.quaternion.multiply(spinQ);
        knife.position.y += 0.020 * flourish;
        knife.position.x += 0.012 * Math.sin(Math.PI * 2 * p) * flourish;

        // Bring the whole viewmodel slightly toward the centre during the flourish.
        this.motion.position.y += 0.025 * flourish;
        this.motion.position.z += 0.018 * flourish;
        this.motion.rotation.y += 0.045 * Math.sin(Math.PI * 2 * p) * flourish;

        if (p >= 1) {
          inspectTime = -1;
          idleTime = 0;
          nextInspect = NEXT_INSPECT_MIN + Math.random() * (NEXT_INSPECT_MAX - NEXT_INSPECT_MIN);
          resetKnife();
        }
      } else {
        resetKnife();
      }
    };

    if (window.gameDiagnostics) {
      Object.assign(window.gameDiagnostics, {
        knifeIdleHandSway: true,
        knifeInspectTwirl: true,
        knifeInspectSpinTurns: 2,
        knifeInspectSourceReference: 'KnifeDefault_Inspect_W',
        knifeInspectRetargeted: true
      });
    }
  }

  install();
})();
