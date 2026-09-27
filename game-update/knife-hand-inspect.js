(function(){
  'use strict';
  if (window.__almasKnifeHandInspect) return;
  window.__almasKnifeHandInspect = true;

  const KNIFE_EQUIP_DURATION = 0.92;
  const KNIFE_INSPECT_DURATION = 2.75;
  const GUN_INSPECT_DURATION = 3.15;
  const FIRST_INSPECT_DELAY = 4.6;
  const NEXT_INSPECT_MIN = 9.5;
  const NEXT_INSPECT_MAX = 13.5;

  let lastInputAt = performance.now();
  document.addEventListener('pointerdown', function(){ lastInputAt = performance.now(); }, {capture:true, passive:true});
  document.addEventListener('keydown', function(){ lastInputAt = performance.now(); }, {capture:true, passive:true});

  function clamp01(t){ return Math.max(0, Math.min(1, t)); }
  function smooth01(t){
    t = clamp01(t);
    return t * t * (3 - 2 * t);
  }
  function smoother01(t){
    t = clamp01(t);
    return t * t * t * (t * (t * 6 - 15) + 10);
  }
  function activeName(self){
    const ws = self && self.weaponSystem;
    const a = ws && ws.active;
    if (typeof a === 'string') return a.toLowerCase();
    if (a && typeof a.name === 'string') return a.name.toLowerCase();
    return a == null ? '' : String(a).toLowerCase();
  }
  function isKnife(name){ return /knife|bayonet|blade/.test(name || ''); }
  function isEmpty(name){ return !name || /none|empty|unarmed|fist/.test(name); }
  function isBusy(self){
    const ws = self && self.weaponSystem;
    const kc = self && self.knifeCombat;
    return !!(
      (kc && kc.elapsed >= 0) ||
      (ws && (ws.firing || ws.isFiring || ws.shooting || ws.isShooting || ws.reloading || ws.isReloading))
    );
  }

  function findBone(root, patterns){
    if (!root || !root.traverse) return null;
    let hit = null;
    root.traverse(function(o){
      if (hit) return;
      const n = (o.name || '').toLowerCase().replace(/[^a-z0-9]/g, '');
      if (patterns.some(function(p){ return n.indexOf(p) !== -1; })) hit = o;
    });
    return hit;
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
    const knife = fv.knifeMesh || null;
    const rightHand = findBone(fv.model, ['mixamorigrighthand','righthand','handr','rhand']);

    const worldPos = new THREE.Vector3();
    const handPos = new THREE.Vector3();
    const axisWorld = new THREE.Vector3();
    const parentQuat = new THREE.Quaternion();
    const parentInvQuat = new THREE.Quaternion();
    const worldQuat = new THREE.Quaternion();
    const spinQuat = new THREE.Quaternion();
    const newWorldQuat = new THREE.Quaternion();

    function spinKnifeAroundGrip(angle){
      if (!knife || !knife.parent || !rightHand || !isFinite(angle) || Math.abs(angle) < 1e-5) return false;

      knife.parent.updateMatrixWorld(true);
      rightHand.updateWorldMatrix(true, false);
      knife.updateWorldMatrix(true, false);

      rightHand.getWorldPosition(handPos);
      knife.getWorldPosition(worldPos);
      knife.getWorldQuaternion(worldQuat);

      // Rotate around the hand/grip position, not around the blade centre.
      // This is the important fix for the old animation entering the hand.
      try {
        if (typeof camera !== 'undefined' && camera && camera.getWorldDirection) {
          camera.getWorldDirection(axisWorld).normalize();
        } else {
          axisWorld.set(0, 0, -1);
        }
      } catch (_) {
        axisWorld.set(0, 0, -1);
      }

      worldPos.sub(handPos).applyAxisAngle(axisWorld, angle).add(handPos);
      spinQuat.setFromAxisAngle(axisWorld, angle);
      newWorldQuat.copy(spinQuat).multiply(worldQuat);

      knife.position.copy(worldPos);
      knife.parent.worldToLocal(knife.position);
      knife.parent.getWorldQuaternion(parentQuat);
      parentInvQuat.copy(parentQuat).invert();
      knife.quaternion.copy(parentInvQuat).multiply(newWorldQuat);
      knife.updateMatrix();
      return true;
    }

    let idleTime = 0;
    let inspectTime = -1;
    let inspectKind = '';
    let equipTime = -1;
    let nextInspect = FIRST_INSPECT_DELAY;
    let lastWeapon = activeName(fv);

    fv.update = function(dt, running, moving){
      originalUpdate(dt, running, moving);

      const weapon = activeName(this);
      const knifeSelected = isKnife(weapon);
      const empty = isEmpty(weapon);
      const busy = isBusy(this);
      const modelVisible = !this.model || this.model.visible !== false;
      const motion = this.motion;

      if (weapon !== lastWeapon) {
        idleTime = 0;
        inspectTime = -1;
        inspectKind = '';
        nextInspect = FIRST_INSPECT_DELAY;
        if (knifeSelected && !isKnife(lastWeapon)) equipTime = 0;
        else equipTime = -1;
        lastWeapon = weapon;
      }

      if (!motion || empty || !modelVisible) {
        idleTime = 0;
        inspectTime = -1;
        equipTime = -1;
        return;
      }

      // Small natural hand breathing/sway for every held weapon.
      const t = Number(this.time) || performance.now() * 0.001;
      const breath = Math.sin(t * 1.45);
      const micro = Math.sin(t * 0.82 + 0.7);
      motion.position.y += breath * 0.0026;
      motion.position.x += micro * 0.0015;
      motion.rotation.x += breath * 0.0032;
      motion.rotation.z += micro * 0.0022;

      if (running || moving || busy) {
        idleTime = 0;
        inspectTime = -1;
        inspectKind = '';
        if (busy) equipTime = -1;
        return;
      }

      // Knife draw/equip: one clean grip-centred turn, with the hands moving with it.
      if (knifeSelected && equipTime >= 0) {
        equipTime += dt;
        const p = clamp01(equipTime / KNIFE_EQUIP_DURATION);
        const settle = Math.sin(Math.PI * p);
        const spinP = smoother01(clamp01(p / 0.82));

        motion.position.x -= 0.014 * settle;
        motion.position.y += 0.024 * settle;
        motion.position.z += 0.015 * settle;
        motion.rotation.x -= 0.055 * settle;
        motion.rotation.z -= 0.085 * settle;

        spinKnifeAroundGrip(-Math.PI * 2 * spinP);

        if (p >= 1) {
          equipTime = -1;
          idleTime = 0;
          nextInspect = FIRST_INSPECT_DELAY;
        }
        return;
      }

      idleTime += dt;
      const quietFor = performance.now() - lastInputAt;
      if (inspectTime < 0 && idleTime >= nextInspect && quietFor > 1200) {
        inspectTime = 0;
        inspectKind = knifeSelected ? 'knife' : 'gun';
      }

      if (inspectTime < 0) return;

      inspectTime += dt;
      const duration = inspectKind === 'knife' ? KNIFE_INSPECT_DURATION : GUN_INSPECT_DURATION;
      const p = clamp01(inspectTime / duration);
      const look = Math.sin(Math.PI * p);
      const side = Math.sin(Math.PI * 2 * p) * look;

      if (inspectKind === 'knife') {
        // Bayonet/knife-style inspect. Whole hands move first; the knife turn is centred on the grip.
        motion.position.x += 0.018 * side;
        motion.position.y += 0.030 * look;
        motion.position.z += 0.022 * look;
        motion.rotation.x -= 0.060 * look;
        motion.rotation.y += 0.115 * side;
        motion.rotation.z -= 0.095 * look - 0.045 * side;

        const twirlP = smoother01((p - 0.24) / 0.52);
        spinKnifeAroundGrip(Math.PI * 2 * twirlP);
      } else {
        // Rifle/pistol inspect: turn the complete hands+weapon viewmodel together,
        // so the weapon never separates from the grip while the player looks over both sides.
        motion.position.x -= 0.040 * side;
        motion.position.y += 0.026 * look;
        motion.position.z += 0.043 * look;
        motion.rotation.x += 0.105 * look;
        motion.rotation.y += 0.285 * side;
        motion.rotation.z -= 0.145 * look;
        motion.rotation.z += 0.070 * side;
      }

      if (p >= 1) {
        inspectTime = -1;
        inspectKind = '';
        idleTime = 0;
        nextInspect = NEXT_INSPECT_MIN + Math.random() * (NEXT_INSPECT_MAX - NEXT_INSPECT_MIN);
      }
    };

    if (window.gameDiagnostics) {
      Object.assign(window.gameDiagnostics, {
        realisticHandSway: true,
        knifeEquipGripSpin: true,
        knifeInspectGripSafe: true,
        weaponInspectTurn: true,
        knifeInspectSourceReference: 'Bayonet_Inspect_W / KnifeDefault_Inspect_W',
        firearmInspectSourceReference: 'M4_inspect_W',
        inspectRetargetedToCurrentHands: true
      });
    }
  }

  install();
})();
