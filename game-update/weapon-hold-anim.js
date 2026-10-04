(function(){
  'use strict';
  if (window.__almasWeaponHoldAnimV2) return;
  window.__almasWeaponHoldAnimV2 = true;
  window.__almasWeaponHoldAnim = true;

  const E = new THREE.Euler();
  const ID = new THREE.Quaternion();

  function activeName(ws){
    const a = ws && ws.active;
    return typeof a === 'string' ? a.toLowerCase() : (a && a.name ? String(a.name).toLowerCase() : '');
  }
  function isGun(n){ return n === 'rifle' || n === 'pistol' || n === 'sniper'; }
  function clamp01(v){ return Math.max(0, Math.min(1, v)); }
  function dampAlpha(dt, speed){
    dt = Number.isFinite(dt) ? Math.max(0, Math.min(0.05, dt)) : 0.016;
    return clamp01(1 - Math.exp(-speed * dt));
  }

  function findBone(root, names){
    if (!root || !root.traverse) return null;
    let out = null;
    root.traverse(function(o){
      if (out) return;
      const n = (o.name || '').toLowerCase().replace(/[^a-z0-9]/g, '');
      if (names.some(function(k){ return n.indexOf(k) !== -1; })) out = o;
    });
    return out;
  }

  function makeBoneState(bone){
    if (!bone) return null;
    return { bone:bone, current:new THREE.Quaternion(), target:new THREE.Quaternion(), inverse:new THREE.Quaternion() };
  }
  function removePreviousOffset(state){
    if (!state) return;
    state.inverse.copy(state.current).invert();
    state.bone.quaternion.multiply(state.inverse);
  }
  function setTargetEuler(state,x,y,z){
    if (!state) return;
    E.set(x,y,z,'XYZ');
    state.target.setFromEuler(E);
  }
  function smoothApply(state,alpha){
    if (!state) return;
    state.current.slerp(state.target,alpha).normalize();
    state.bone.quaternion.multiply(state.current);
  }
  function clearBoneState(state){
    if (!state) return;
    state.current.copy(ID);
    state.target.copy(ID);
  }

  function install(){
    let fv,ws;
    try { fv=forearmView; ws=weaponSystem; } catch (_) { fv=null; ws=null; }
    if (!fv || !ws || !fv.update || !fv.motion) { requestAnimationFrame(install); return; }
    if (fv.__weaponHoldAnimPatchedV2) return;
    fv.__weaponHoldAnimPatchedV2=true;

    const originalUpdate=fv.update.bind(fv);
    const bones={
      leftHand:makeBoneState(findBone(fv.model,['mixamoriglefthand','lefthand','handl','lhand'])),
      rightHand:makeBoneState(findBone(fv.model,['mixamorigrighthand','righthand','handr','rhand'])),
      leftForearm:makeBoneState(findBone(fv.model,['mixamorigleftforearm','leftforearm','forearml'])),
      rightForearm:makeBoneState(findBone(fv.model,['mixamorigrightforearm','rightforearm','forearmr']))
    };

    const root={px:0,py:0,pz:0,rx:0,ry:0,rz:0};
    let lastMotion=null;
    function removeRootOffset(motion){
      if(!motion)return;
      if(lastMotion && lastMotion!==motion) root.px=root.py=root.pz=root.rx=root.ry=root.rz=0;
      motion.position.x-=root.px; motion.position.y-=root.py; motion.position.z-=root.pz;
      motion.rotation.x-=root.rx; motion.rotation.y-=root.ry; motion.rotation.z-=root.rz;
      lastMotion=motion;
    }
    function blendRoot(t,a){
      root.px+=(t.px-root.px)*a; root.py+=(t.py-root.py)*a; root.pz+=(t.pz-root.pz)*a;
      root.rx+=(t.rx-root.rx)*a; root.ry+=(t.ry-root.ry)*a; root.rz+=(t.rz-root.rz)*a;
    }
    function applyRootOffset(motion){
      if(!motion)return;
      motion.position.x+=root.px; motion.position.y+=root.py; motion.position.z+=root.pz;
      motion.rotation.x+=root.rx; motion.rotation.y+=root.ry; motion.rotation.z+=root.rz;
    }

    fv.update=function(dt,running,moving){
      removeRootOffset(this.motion);
      Object.keys(bones).forEach(function(k){removePreviousOffset(bones[k]);});
      originalUpdate(dt,running,moving);

      const n=activeName(ws), motion=this.motion;
      if(!isGun(n)||!motion||!this.model||this.model.visible===false){
        root.px=root.py=root.pz=root.rx=root.ry=root.rz=0;
        Object.keys(bones).forEach(function(k){clearBoneState(bones[k]);});
        return;
      }

      const t=Number(this.time)||performance.now()*0.001;
      const runW=running?1:0, walkW=moving&&!running?1:0, moveW=Math.max(runW,walkW);
      const pace=running?10.6:6.4;
      const bob=moveW?Math.sin(t*pace):0;
      const side=moveW?Math.cos(t*pace*0.5):0;
      const breath=Math.sin(t*1.25);
      const settleAlpha=dampAlpha(dt,moving?14:10);
      const boneAlpha=dampAlpha(dt,moving?16:12);
      const target={px:0,py:0,pz:0,rx:0,ry:0,rz:0};

      if(n==='rifle'){
        target.px=0.012+side*0.0018*moveW;
        target.py=0.018-0.018*runW+bob*0.0034*walkW+bob*0.0058*runW+breath*0.0014;
        target.pz=0.010+Math.abs(bob)*0.0018*moveW;
        target.rx=-0.018+bob*0.009*walkW+bob*0.015*runW+breath*0.0015;
        target.ry=side*0.009*moveW;
        target.rz=-0.018+side*0.010*walkW+side*0.017*runW;
        setTargetEuler(bones.rightForearm,-0.035+bob*0.004*moveW,0.010,-0.020+side*0.004*moveW);
        setTargetEuler(bones.leftForearm,-0.090+bob*0.006*moveW,0.020,0.070+side*0.006*moveW);
        setTargetEuler(bones.rightHand,-0.020,0.018,-0.012+side*0.003*moveW);
        setTargetEuler(bones.leftHand,-0.055,-0.025,0.050+bob*0.004*moveW);
      }else if(n==='pistol'){
        target.px=0.004+side*0.0015*moveW;
        target.py=0.028-0.016*runW+bob*0.0038*moveW+breath*0.0015;
        target.pz=0.020;
        target.rx=-0.010+bob*0.010*moveW+breath*0.0014;
        target.ry=side*0.008*moveW;
        target.rz=side*(0.010*walkW+0.015*runW);
        setTargetEuler(bones.rightForearm,-0.020+bob*0.003*moveW,-0.015,-0.012);
        setTargetEuler(bones.leftForearm,-0.035+bob*0.004*moveW,0.060,0.085+side*0.004*moveW);
        setTargetEuler(bones.rightHand,-0.010,0.010,0.005);
        setTargetEuler(bones.leftHand,-0.020,0.045,0.070+bob*0.003*moveW);
      }else{
        target.px=0.006+side*0.0018*moveW;
        target.py=0.010-0.022*runW+bob*0.0038*walkW+bob*0.0062*runW+breath*0.0012;
        target.pz=0.004;
        target.rx=-0.028+bob*0.010*walkW+bob*0.017*runW+breath*0.0014;
        target.ry=side*0.008*moveW;
        target.rz=-0.012+side*0.012*walkW+side*0.019*runW;
        setTargetEuler(bones.rightForearm,-0.050+bob*0.004*moveW,0.000,-0.020);
        setTargetEuler(bones.leftForearm,-0.115+bob*0.006*moveW,0.025,0.095+side*0.005*moveW);
        setTargetEuler(bones.rightHand,-0.018,0.010,-0.010);
        setTargetEuler(bones.leftHand,-0.070,-0.015,0.065+bob*0.004*moveW);
      }

      blendRoot(target,settleAlpha);
      applyRootOffset(motion);
      Object.keys(bones).forEach(function(k){smoothApply(bones[k],boneAlpha);});
    };

    const originalWsUpdate=ws.update&&ws.update.bind(ws);
    if(originalWsUpdate&&!ws.__holdSyncPatchedV2){
      ws.__holdSyncPatchedV2=true;
      const gunStates=new WeakMap();
      ws.update=function(dt){
        const beforeName=activeName(this);
        const beforeGun=this.gunView||(this.viewCache&&this.viewCache[beforeName]);
        if(beforeGun){
          const s=gunStates.get(beforeGun);
          if(s){beforeGun.rotation.x-=s.rx; beforeGun.rotation.z-=s.rz;}
        }
        originalWsUpdate(dt);
        const n=activeName(this);
        const gun=this.gunView||(this.viewCache&&this.viewCache[n]);
        if(!gun||!isGun(n))return;
        let state=gunStates.get(gun);
        if(!state){state={rx:0,rz:0};gunStates.set(gun,state);}
        const t=performance.now()*0.001;
        const k=n==='pistol'?0.55:(n==='sniper'?0.78:0.68);
        const a=dampAlpha(dt,12);
        const tx=Math.sin(t*1.25)*0.0010*k;
        const tz=Math.sin(t*0.82+0.4)*0.0009*k;
        state.rx+=(tx-state.rx)*a;
        state.rz+=(tz-state.rz)*a;
        gun.rotation.x+=state.rx;
        gun.rotation.z+=state.rz;
      };
    }

    if(window.gameDiagnostics){
      Object.assign(window.gameDiagnostics,{
        weaponHoldAnimation:true,
        weaponHoldAnimationV2:true,
        nonCumulativeBoneRotation:true,
        nonCumulativeWeaponSway:true,
        smoothQuaternionSlerp:true,
        githubAnimationReference:'Ayush-Mohanty/FPS-Arms-3D',
        githubAnimationReferenceLicense:'MIT',
        akReference:'fps_ak-74m_animations'
      });
    }
  }
  install();
})();
