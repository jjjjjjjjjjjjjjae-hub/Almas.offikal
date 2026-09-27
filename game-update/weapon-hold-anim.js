(function(){
  'use strict';
  if (window.__almasWeaponHoldAnim) return;
  window.__almasWeaponHoldAnim = true;

  const Q = new THREE.Quaternion();
  const E = new THREE.Euler();

  function activeName(ws){
    const a = ws && ws.active;
    return typeof a === 'string' ? a.toLowerCase() : (a && a.name ? String(a.name).toLowerCase() : '');
  }
  function isGun(n){ return n === 'rifle' || n === 'pistol' || n === 'sniper'; }

  function findBone(root, names){
    if (!root || !root.traverse) return null;
    let out = null;
    root.traverse(function(o){
      if (out) return;
      const n=(o.name||'').toLowerCase().replace(/[^a-z0-9]/g,'');
      if (names.some(function(k){ return n.indexOf(k)!==-1; })) out=o;
    });
    return out;
  }

  function install(){
    let fv, ws;
    try { fv = forearmView; ws = weaponSystem; } catch (_) { fv=null; ws=null; }
    if (!fv || !ws || !fv.update || !fv.motion) {
      requestAnimationFrame(install);
      return;
    }
    if (fv.__weaponHoldAnimPatched) return;
    fv.__weaponHoldAnimPatched = true;

    const originalUpdate = fv.update.bind(fv);
    const leftHand = findBone(fv.model,['mixamoriglefthand','lefthand','handl','lhand']);
    const rightHand = findBone(fv.model,['mixamorigrighthand','righthand','handr','rhand']);
    const leftForearm = findBone(fv.model,['mixamorigleftforearm','leftforearm','forearml']);
    const rightForearm = findBone(fv.model,['mixamorigrightforearm','rightforearm','forearmr']);

    function addLocalRotation(bone,x,y,z,weight){
      if (!bone || !weight) return;
      E.set(x*weight,y*weight,z*weight,'XYZ');
      Q.setFromEuler(E);
      bone.quaternion.multiply(Q);
    }

    fv.update = function(dt,running,moving){
      originalUpdate(dt,running,moving);

      const n=activeName(ws);
      if (!isGun(n) || !this.model || this.model.visible===false) return;

      const t=Number(this.time)||performance.now()*0.001;
      const moveBob=moving ? Math.sin(t*(running?11.2:7.1)) : 0;
      const sideBob=moving ? Math.cos(t*(running?5.6:3.55)) : 0;
      const breath=Math.sin(t*1.35);
      const runW=running?1:0;
      const walkW=moving&&!running?1:0;

      if(n==='rifle'){
        // AK/assault-rifle two-hand shoulder hold, based on AKR12_Idle_W proportions.
        this.motion.position.x += 0.012;
        this.motion.position.y += 0.018 - 0.020*runW;
        this.motion.position.z += 0.010;
        this.motion.rotation.x += -0.018 + moveBob*0.010*walkW + moveBob*0.020*runW;
        this.motion.rotation.y += sideBob*0.012*(walkW+runW);
        this.motion.rotation.z += -0.018 + sideBob*0.014*walkW + sideBob*0.024*runW;
        addLocalRotation(rightForearm,-0.035,0.010,-0.020,1);
        addLocalRotation(leftForearm,-0.090,0.020,0.070,1);
        addLocalRotation(rightHand,-0.020,0.018,-0.012,1);
        addLocalRotation(leftHand,-0.055,-0.025,0.050,1);
      } else if(n==='pistol'){
        // Pistol: right hand on grip, left hand supporting, based on USP_Idle_W.
        this.motion.position.x += 0.004;
        this.motion.position.y += 0.028 - 0.018*runW;
        this.motion.position.z += 0.020;
        this.motion.rotation.x += -0.010 + moveBob*0.012*(walkW+runW);
        this.motion.rotation.y += sideBob*0.010*(walkW+runW);
        this.motion.rotation.z += sideBob*0.012*walkW + sideBob*0.020*runW;
        addLocalRotation(rightForearm,-0.020,-0.015,-0.012,1);
        addLocalRotation(leftForearm,-0.035,0.060,0.085,1);
        addLocalRotation(rightHand,-0.010,0.010,0.005,1);
        addLocalRotation(leftHand,-0.020,0.045,0.070,1);
      } else {
        // Double-barrel/shotgun: wide two-hand support, using SM1014 idle/inspect pose references.
        this.motion.position.x += 0.006;
        this.motion.position.y += 0.010 - 0.024*runW;
        this.motion.position.z += 0.004;
        this.motion.rotation.x += -0.028 + moveBob*0.011*walkW + moveBob*0.023*runW;
        this.motion.rotation.y += sideBob*0.010*(walkW+runW);
        this.motion.rotation.z += -0.012 + sideBob*0.016*walkW + sideBob*0.027*runW;
        addLocalRotation(rightForearm,-0.050,0.000,-0.020,1);
        addLocalRotation(leftForearm,-0.115,0.025,0.095,1);
        addLocalRotation(rightHand,-0.018,0.010,-0.010,1);
        addLocalRotation(leftHand,-0.070,-0.015,0.065,1);
      }

      // tiny breathing remains even when standing still; keeps the hold from looking frozen.
      this.motion.position.y += breath*0.0015;
      this.motion.rotation.x += breath*0.0018;
    };

    // Make weapon viewmodels follow the same root motion whenever practical.
    // We do not re-parent models here; this avoids breaking the existing weapon switch logic.
    const originalWsUpdate = ws.update && ws.update.bind(ws);
    if (originalWsUpdate && !ws.__holdSyncPatched) {
      ws.__holdSyncPatched=true;
      ws.update=function(dt){
        originalWsUpdate(dt);
        const n=activeName(this);
        const gun=this.gunView || (this.viewCache && this.viewCache[n]);
        if(!gun || !isGun(n)) return;
        const t=performance.now()*0.001;
        const k=n==='pistol'?0.55:(n==='sniper'?0.78:0.68);
        gun.rotation.x += Math.sin(t*1.35)*0.0012*k;
        gun.rotation.z += Math.sin(t*0.82+0.4)*0.0010*k;
      };
    }

    if(window.gameDiagnostics){
      Object.assign(window.gameDiagnostics,{
        weaponHoldAnimation:true,
        akTwoHandHold:true,
        pistolSupportHandHold:true,
        shotgunTwoHandHold:true,
        holdReferenceAK:'AKR12_idle_W',
        holdReferencePistol:'USP_Idle_W',
        holdReferenceShotgun:'SM1014_idle_F / SM1014_Inspect_W'
      });
    }
  }

  install();
})();
