(function(){
  'use strict';
  if (window.__almasWeaponsPack) return;
  window.__almasWeaponsPack = true;

  function wait(){
    let ws;
    try { ws = weaponSystem; } catch (_) { ws = null; }
    if (!ws || !window.THREE || !ws.viewCache || !ws.camera) {
      requestAnimationFrame(wait);
      return;
    }
    install(ws);
  }

  function makeShotgun(){
    const g = new THREE.Group();
    g.name = 'double_barrel_shotgun_view';

    const steel = new THREE.MeshStandardMaterial({color:0x242a2e, metalness:0.62, roughness:0.34});
    const black = new THREE.MeshStandardMaterial({color:0x0d1012, metalness:0.25, roughness:0.62});
    const wood = new THREE.MeshStandardMaterial({color:0x6a3d21, metalness:0.04, roughness:0.78});

    function box(x,y,z,w,h,d,mat,rx=0,ry=0,rz=0){
      const m = new THREE.Mesh(new THREE.BoxGeometry(w,h,d),mat);
      m.position.set(x,y,z); m.rotation.set(rx,ry,rz); g.add(m); return m;
    }
    function tube(x,y,z,r,len,mat){
      const m = new THREE.Mesh(new THREE.CylinderGeometry(r,r,len,12),mat);
      m.rotation.x = Math.PI/2; m.position.set(x,y,z); g.add(m); return m;
    }

    // Twin barrels.
    tube(-0.048,0.025,-0.56,0.031,0.78,steel);
    tube( 0.048,0.025,-0.56,0.031,0.78,steel);
    tube(-0.048,0.025,-0.965,0.036,0.035,black);
    tube( 0.048,0.025,-0.965,0.036,0.035,black);

    // Receiver, fore-end and wooden stock.
    box(0,0.005,-0.16,0.19,0.15,0.28,steel);
    box(0,-0.045,-0.47,0.15,0.10,0.30,wood);
    box(0,-0.115,0.10,0.145,0.19,0.32,wood,-0.15,0,0);
    box(0,-0.17,0.34,0.12,0.18,0.28,wood,-0.22,0,0);
    box(0,-0.08,-0.11,0.055,0.10,0.10,black,0.18,0,0);

    g.traverse(function(o){ if(o.isMesh){ o.castShadow=false; o.frustumCulled=false; }});
    g.position.set(.28,-.25,-.50);
    g.rotation.y = -.14;
    g.scale.setScalar(.63);
    g.visible = false;
    return g;
  }

  function install(ws){
    if (ws.__almasWeaponsPackInstalled) return;
    ws.__almasWeaponsPackInstalled = true;

    // Existing three gun slots become exactly: automatic, pistol, double barrel.
    ws.owned.add('rifle');
    ws.owned.add('pistol');
    ws.owned.add('sniper'); // internal alias kept for compatibility; shown as shotgun.
    ws.ammo.rifle = Math.min(30, Number(ws.ammo.rifle)||30);
    ws.ammo.pistol = Math.min(12, Number(ws.ammo.pistol)||12);
    ws.ammo.sniper = Math.min(2, Number(ws.ammo.sniper)||2);

    // Replace the old sniper viewmodel with a real double-barrel shape.
    const old = ws.viewCache.sniper;
    if (old && old.parent) old.parent.remove(old);
    const shotgun = makeShotgun();
    ws.camera.add(shotgun);
    ws.viewCache.sniper = shotgun;
    if (ws.active === 'sniper') { shotgun.visible = true; ws.gunView = shotgun; }

    // Rename the left weapon-wheel slot and give it a double-barrel glyph.
    const sniperSlot = document.querySelector('.slot-sniper,[data-weapon="sniper"]');
    if (sniperSlot) {
      const text = sniperSlot.querySelector('text');
      if (text) text.textContent = 'ДРОБОВИК';
      const path = sniperSlot.querySelector('.wheel-glyph path');
      if (path) path.setAttribute('d','M74 222h248l0 28H74z M74 270h248l0 28H74z M319 212h35v96h-35z M354 225l84 40-14 31-78-37z M128 300l-45 91 43 21 54-108z M190 291h80v48h-80z');
    }

    const originalRefresh = ws.refreshHud.bind(ws);
    ws.refreshHud = function(){
      originalRefresh();
      if (this.active === 'sniper') {
        if (this.label) this.label.textContent = 'Екі ұңғылы дробовик';
        if (this.ammoNameEl) this.ammoNameEl.textContent = 'Дробовик';
        if (this.ammoCountEl && this.reloadTime <= 0) this.ammoCountEl.textContent = String(Math.min(2, this.ammo.sniper || 0));
      }
      if (this.active === 'rifle' && this.ammoNameEl) this.ammoNameEl.textContent = 'Автомат';
      if (this.active === 'pistol' && this.ammoNameEl) this.ammoNameEl.textContent = 'Тапанша';
    };

    const originalFire = ws.fire.bind(ws);
    ws.fire = function(){
      if (this.active !== 'sniper') return originalFire();
      if (!window.gameDiagnostics.ready || this.reloadTime>0 || this.cooldown>0) return;
      if ((this.ammo.sniper||0) <= 0) {
        this.reloadTime = 1.75;
        this.refreshHud();
        return;
      }

      this.ammo.sniper--;
      this.cooldown = 0.46;

      // Double-barrel shotgun: 8 pellets with a small spread.
      let bestHit = null;
      for (let i=0;i<8;i++) {
        const sx = (Math.random()-0.5) * 0.075;
        const sy = (Math.random()-0.5) * 0.075;
        this.ray.setFromCamera(new THREE.Vector2(sx,sy),this.camera);
        this.ray.far = 72;
        const hit = this.ray.intersectObjects(this.solids,false)[0];
        if (hit && (!bestHit || hit.distance < bestHit.distance)) bestHit = hit;
      }

      if (bestHit) {
        const n = bestHit.face.normal.clone().transformDirection(bestHit.object.matrixWorld);
        this.impact.position.copy(bestHit.point).addScaledVector(n,.008);
        this.impact.quaternion.setFromUnitVectors(new THREE.Vector3(0,0,1),n);
        this.impact.material.opacity=.9;
        this.impact.visible=true;
        this.impactTime=.18;
      }

      window.gameDiagnostics.shotsFired=(window.gameDiagnostics.shotsFired||0)+1;
      window.gameDiagnostics.lastWeaponShot='double-barrel-shotgun';
      window.gameDiagnostics.lastShotgunPellets=8;
      if (this.ammo.sniper <= 0) this.reloadTime = 1.75;
      this.refreshHud();
    };

    const originalUpdate = ws.update.bind(ws);
    ws.update = function(dt){
      const wasReloading = this.active==='sniper' && this.reloadTime>0;
      originalUpdate(dt);
      if (this.active==='sniper') {
        // Original sniper data reloads to five. Clamp the internal compatible slot to two shells.
        if (this.ammo.sniper > 2) this.ammo.sniper = 2;
        if (wasReloading && this.reloadTime===0) this.refreshHud();
      }
    };

    ws.refreshHud();

    Object.assign(window.gameDiagnostics, {
      weaponsPackAdded: true,
      weaponAutomaticRifle: true,
      weaponPistol: true,
      weaponDoubleBarrelShotgun: true,
      shotgunInternalSlot: 'sniper',
      shotgunMagazine: 2,
      shotgunPellets: 8
    });
  }

  wait();
})();
