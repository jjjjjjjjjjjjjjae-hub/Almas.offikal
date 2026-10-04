(function(){
  'use strict';
  if (window.__almasKnifeHandStableV3) return;
  window.__almasKnifeHandStableV3 = true;
  window.__almasKnifeHandInspect = true;

  function activeName(self){
    const ws = self && self.weaponSystem;
    const a = ws && ws.active;
    if (typeof a === 'string') return a.toLowerCase();
    if (a && typeof a.name === 'string') return a.name.toLowerCase();
    return a == null ? '' : String(a).toLowerCase();
  }
  function isKnife(name){ return /knife|bayonet|blade/.test(name || ''); }
  function dampAlpha(dt, speed){
    dt = Number.isFinite(dt) ? Math.max(0, Math.min(0.05, dt)) : 0.016;
    return Math.max(0, Math.min(1, 1 - Math.exp(-speed * dt)));
  }

  function install(){
    let fv;
    try { fv = forearmView; } catch (_) { fv = null; }
    if (!fv || !fv.update || !fv.motion) {
      requestAnimationFrame(install);
      return;
    }
    if (fv.__knifeHandStableV3) return;
    fv.__knifeHandStableV3 = true;

    const originalUpdate = fv.update.bind(fv);

    // Only one small root offset is added after the game's own animation.
    // The previous frame offset is removed first, so nothing can accumulate.
    const cur = {x:0,y:0,z:0,rx:0,ry:0,rz:0};
    let lastMotion = null;

    function removeOffset(m){
      if (!m) return;
      if (lastMotion && lastMotion !== m) {
        cur.x=cur.y=cur.z=cur.rx=cur.ry=cur.rz=0;
      }
      m.position.x -= cur.x;
      m.position.y -= cur.y;
      m.position.z -= cur.z;
      m.rotation.x -= cur.rx;
      m.rotation.y -= cur.ry;
      m.rotation.z -= cur.rz;
      lastMotion = m;
    }

    function clearOffset(){
      cur.x=cur.y=cur.z=cur.rx=cur.ry=cur.rz=0;
    }

    fv.update = function(dt, running, moving){
      removeOffset(this.motion);
      originalUpdate(dt, running, moving);

      const motion = this.motion;
      const weapon = activeName(this);
      if (!motion || !isKnife(weapon) || (this.model && this.model.visible === false)) {
        clearOffset();
        return;
      }

      // No hand/forearm bone rotation is applied here.
      // This prevents the wrist/hand from twisting or spinning away over time.
      const t = Number(this.time) || performance.now() * 0.001;
      const runW = running ? 1 : 0;
      const walkW = moving && !running ? 1 : 0;
      const moveW = Math.max(runW, walkW);
      const pace = running ? 9.8 : 6.0;
      const bob = moveW ? Math.sin(t * pace) : 0;
      const side = moveW ? Math.cos(t * pace * 0.5) : 0;
      const breath = Math.sin(t * 1.2);

      // Stable FPS knife stance: very small smooth movement only.
      const target = {
        x: side * 0.0014 * moveW,
        y: breath * 0.0012 + bob * (running ? 0.0038 : 0.0025) * moveW,
        z: Math.abs(bob) * 0.0012 * moveW,
        rx: breath * 0.0015 + bob * (running ? 0.0065 : 0.0040) * moveW,
        ry: side * 0.0035 * moveW,
        rz: side * (running ? 0.0060 : 0.0040) * moveW
      };

      const a = dampAlpha(dt, moving ? 15 : 10);
      cur.x += (target.x-cur.x)*a;
      cur.y += (target.y-cur.y)*a;
      cur.z += (target.z-cur.z)*a;
      cur.rx += (target.rx-cur.rx)*a;
      cur.ry += (target.ry-cur.ry)*a;
      cur.rz += (target.rz-cur.rz)*a;

      motion.position.x += cur.x;
      motion.position.y += cur.y;
      motion.position.z += cur.z;
      motion.rotation.x += cur.rx;
      motion.rotation.y += cur.ry;
      motion.rotation.z += cur.rz;
    };

    if (window.gameDiagnostics) {
      Object.assign(window.gameDiagnostics, {
        knifeHandStableV3:true,
        knifeSpinDisabled:true,
        automaticKnifeInspectDisabled:true,
        knifeBoneRotationLocked:true,
        nonCumulativeKnifeMotion:true,
        handRotationBugFixed:true
      });
    }
  }

  install();
})();
