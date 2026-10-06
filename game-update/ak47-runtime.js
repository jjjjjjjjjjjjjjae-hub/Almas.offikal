(function(){
  'use strict';
  if(window.__almasAK47Runtime) return;
  window.__almasAK47Runtime=true;
  // AK-47: Valerij Dancenko/Bortensol, CC-BY-4.0, supplied Sketchfab GLB.
  // Draw curves: Cransh, CC-BY-4.0, pinned Ayush-Mohanty/FPS-Arms-3D.
  // The current glove skin is retained; IK adapts its different skeleton.
  const V=()=>new THREE.Vector3();
  const Q=()=>new THREE.Quaternion();
  const basis=new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0,1,0),Math.PI);
  const inverseBasis=basis.clone().invert();
  const scratch=Array.from({length:12},V);
  const qs=Array.from({length:6},Q);
  const smooth=x=>{x=THREE.MathUtils.clamp(x,0,1);return x*x*(3-2*x);};

  function json(url){return new Promise((resolve,reject)=>new THREE.FileLoader().setResponseType('json').load(url,resolve,undefined,reject));}
  function glb(url){return new Promise((resolve,reject)=>new THREE.GLTFLoader().load(url,resolve,undefined,reject));}
  const assets=Promise.all([glb('models/ak47.glb'),json('models/weapon-draw.json')]);
  assets.catch(e=>{console.error('[ALMAS AK-47]',e);if(window.gameDiagnostics)window.gameDiagnostics.ak47Error=String(e.message||e);});

  function wait(){
    let fv,ws;try{fv=forearmView;ws=weaponSystem;}catch(_){}
    if(!fv||!fv.model||!fv.mixer||!ws||!ws.camera){requestAnimationFrame(wait);return;}
    assets.then(([asset,draw])=>install(fv,ws,asset,draw)).catch(e=>{
      console.error('[ALMAS AK-47 install]',e);
      if(window.gameDiagnostics)window.gameDiagnostics.ak47Error=String(e.message||e);
    });
  }
  function solveArm(upper,lower,wrist,target,hint,wristRotation){
    const s=upper.getWorldPosition(scratch[0]);
    const e=lower.getWorldPosition(scratch[1]);
    const w=wrist.getWorldPosition(scratch[2]);
    const l1=s.distanceTo(e),l2=e.distanceTo(w);
    if(l1<1e-5||l2<1e-5)return;
    const axis=scratch[3].subVectors(target,s);
    const distance=THREE.MathUtils.clamp(axis.length(),Math.abs(l1-l2)+1e-4,l1+l2-1e-4);axis.normalize();
    const bend=scratch[4].subVectors(hint,s);bend.addScaledVector(axis,-bend.dot(axis));
    if(bend.lengthSq()<1e-8)bend.set(0,-1,0).addScaledVector(axis,axis.y);
    bend.normalize();
    const along=(l1*l1-l2*l2+distance*distance)/(2*distance);
    const high=Math.sqrt(Math.max(0,l1*l1-along*along));
    const elbow=scratch[5].copy(s).addScaledVector(axis,along).addScaledVector(bend,high);
    const from=scratch[6].subVectors(e,s).normalize(),to=scratch[7].subVectors(elbow,s).normalize();
    qs[0].setFromUnitVectors(from,to).multiply(upper.getWorldQuaternion(qs[1]));
    upper.quaternion.copy(upper.parent.getWorldQuaternion(qs[2]).invert().multiply(qs[0]));
    upper.updateMatrixWorld(true);
    lower.getWorldPosition(e);wrist.getWorldPosition(w);
    from.subVectors(w,e).normalize();to.subVectors(target,e).normalize();
    qs[0].setFromUnitVectors(from,to).multiply(lower.getWorldQuaternion(qs[1]));
    lower.quaternion.copy(lower.parent.getWorldQuaternion(qs[2]).invert().multiply(qs[0]));
    lower.updateMatrixWorld(true);
    if(wristRotation)wrist.quaternion.copy(wrist.parent.getWorldQuaternion(qs[2]).invert().multiply(wristRotation));
    wrist.updateMatrixWorld(true);
  }

  function install(fv,ws,asset,draw){
    if(ws.__ak47Installed)return;
    const names=['R_arm_025','R_elbow_026','R_wrist_027','L_arm_02','L_elbow_03','L_wrist_04'];
    const bones=names.map(n=>fv.model.getObjectByName(n));
    if(bones.some(x=>!x))throw Error('AK-47 glove rig missing an arm bone');
    ws.__ak47Installed=true;
    fv.mixer.setTime(0);fv.model.updateMatrixWorld(true);
    const saved=[];fv.model.traverse(o=>{if(o.isBone)saved.push({bone:o,p:o.position.clone(),q:o.quaternion.clone(),s:o.scale.clone()});});
    const restore=()=>{for(const x of saved){x.bone.position.copy(x.p);x.bone.quaternion.copy(x.q);x.bone.scale.copy(x.s);}};

    const rifle=new THREE.Group();rifle.name='AK47_Sketchfab_view';
    const model=asset.scene;model.updateMatrixWorld(true);
    const bounds=new THREE.Box3().setFromObject(model),center=bounds.getCenter(V());
    model.position.sub(center);rifle.add(model);
    rifle.rotation.y=Math.PI/2;rifle.position.set(.23,-.225,-.64);
    rifle.visible=ws.active==='rifle';
    model.traverse(o=>{if(!o.isMesh)return;o.frustumCulled=false;o.castShadow=false;o.receiveShadow=false;
      const mats=Array.isArray(o.material)?o.material:[o.material];
      for(const m of mats){if(m.map)m.map.encoding=THREE.sRGBEncoding;m.needsUpdate=true;}
    });
    const old=ws.viewCache.rifle;if(old&&old.parent)old.parent.remove(old);
    fv.motion.add(rifle);ws.viewCache.rifle=rifle;if(ws.active==='rifle')ws.gunView=rifle;
    for(const kind of ['pistol','sniper']){
      const view=ws.viewCache[kind];if(view&&view.parent!==fv.motion){view.parent?.remove(view);fv.motion.add(view);}
    }
    const rightTarget=V(),leftTarget=V(),rightHint=V(),leftHint=V(),rightRotation=Q(),leftRotation=Q();
    const handOffsetR=V(),handOffsetL=V();
    const rightGripLocal=V(),leftGripLocal=V(),rightHandleAxisLocal=V();
    const rightHandleAxis=V(),desiredGripAxis=V(),leftHandleAxis=V(),leftPalmNormal=V();
    const worldScaleR=V(),worldScaleL=V(),gripTurn=Q(),leftGripBasisInverse=Q();
    const gripBasis=new THREE.Matrix4();
    const leftFingerTurns=[];
    for(const [segment,angle] of [[1,-.25],[2,-.65],[3,-.30]]){
      for(const finger of ['point','middle','ring','pink']){
        let bone=null;
        fv.model.traverse(o=>{if(!bone&&new RegExp('^L_'+finger+segment+'_').test(o.name))bone=o;});
        if(bone)leftFingerTurns.push({bone,angle});
      }
    }
    const curlLeft=()=>{for(const x of leftFingerTurns)x.bone.rotateX(x.angle);};
    function handCenter(wrist,names){
      const point=V();let count=0;
      for(const name of names){const bone=fv.model.getObjectByName(name);if(bone){point.add(bone.getWorldPosition(V()));count++;}}
      if(!count)throw Error('Glove grip landmarks missing');
      return wrist.worldToLocal(point.multiplyScalar(1/count));
    }
    // Measure the existing closed right glove against the actual knife handle.
    // The lowest 40% of the knife's +Y extent contains its handle and guard.
    const knife=fv.knifeMesh;
    if(!knife?.geometry)throw Error('Knife handle geometry missing for glove grip');
    knife.geometry.computeBoundingBox();
    const knifeBounds=knife.geometry.boundingBox;
    const rightCavity=handCenter(bones[2],['R_middle1_036','R_middle_039','R_ring1_041','R_ring_044','R_pink1_045','R_pink_048']);
    const cavityInKnife=knife.worldToLocal(bones[2].localToWorld(rightCavity.clone()));
    const handlePoint=V().set((knifeBounds.min.x+knifeBounds.max.x)/2,
      THREE.MathUtils.clamp(cavityInKnife.y,knifeBounds.min.y,THREE.MathUtils.lerp(knifeBounds.min.y,knifeBounds.max.y,.40)),
      (knifeBounds.min.z+knifeBounds.max.z)/2);
    rightGripLocal.copy(bones[2].worldToLocal(knife.localToWorld(handlePoint)));
    bones[2].getWorldQuaternion(rightRotation);
    rightHandleAxisLocal.set(0,1,0).transformDirection(knife.matrixWorld).applyQuaternion(rightRotation.clone().invert()).normalize();

    // Close the real left fingers before measuring their contact cavity. Use
    // the knuckle span for the foregrip axis and the wrist-to-knuckle direction
    // to identify which side of the palm should face up under a long firearm.
    curlLeft();fv.model.updateMatrixWorld(true);
    leftGripLocal.copy(handCenter(bones[5],['L_middle1_012','L_middle_015','L_ring1_017','L_ring_020','L_pink1_021','L_pink_024']));
    const indexLocal=bones[5].worldToLocal(fv.model.getObjectByName('L_point1_00').getWorldPosition(V()));
    const pinkLocal=bones[5].worldToLocal(fv.model.getObjectByName('L_pink1_021').getWorldPosition(V()));
    const middleLocal=bones[5].worldToLocal(fv.model.getObjectByName('L_middle1_012').getWorldPosition(V()));
    const localAxis=indexLocal.clone().sub(pinkLocal).normalize();
    const localNormal=middleLocal.clone().cross(localAxis).normalize();
    const localThird=localNormal.clone().cross(localAxis).normalize();
    gripBasis.makeBasis(localNormal,localAxis,localThird);
    leftGripBasisInverse.setFromRotationMatrix(gripBasis).invert();
    restore();fv.model.updateMatrixWorld(true);
    const transitionAnchor=V(),rotatedAnchor=V(),deltaPosition=V(),deltaQuaternion=Q();
    const sampleQ0=Q(),sampleQ1=Q();
    const sourceSamples=draw.samples;
    if(!Array.isArray(sourceSamples)||sourceSamples.length<2)throw Error('AK draw curve invalid');

    function sampleMotion(progress){
      const t=THREE.MathUtils.clamp(progress,0,1)*draw.duration;
      let hi=1;while(hi<sourceSamples.length-1&&sourceSamples[hi].time<t)hi++;
      const a=sourceSamples[hi-1],b=sourceSamples[hi];
      const k=THREE.MathUtils.clamp((t-a.time)/Math.max(1e-6,b.time-a.time),0,1);
      deltaPosition.fromArray(a.positionDelta).lerp(scratch[8].fromArray(b.positionDelta),k).applyQuaternion(basis);
      sampleQ0.fromArray(a.rotationDelta);sampleQ1.fromArray(b.rotationDelta);
      deltaQuaternion.copy(basis).multiply(sampleQ0.slerp(sampleQ1,k)).multiply(inverseBasis).normalize();
    }
    function applyTransition(){
      const state=ws.__equipmentSwitch;
      if(!state||!state.switching)return;
      const p=state.phase==='holster'?1-state.progress:state.progress;
      sampleMotion(p);
      // Source draw is adapted toward the belt; the equipment swap stays below view.
      const hidden=1-smooth(p);
      deltaPosition.y-=.78*hidden;
      deltaPosition.x+=.08*hidden;
      // The source curve tracks a hand, so rotate about its final wrist anchor.
      // Rotating about the camera would add an unwanted arc toward the near plane.
      fv.motion.updateMatrixWorld(true);
      bones[2].getWorldPosition(transitionAnchor);
      fv.motion.worldToLocal(transitionAnchor);
      transitionAnchor.applyQuaternion(fv.motion.quaternion);
      rotatedAnchor.copy(transitionAnchor).applyQuaternion(deltaQuaternion);
      deltaPosition.add(transitionAnchor).sub(rotatedAnchor);
      fv.motion.position.add(deltaPosition);
      fv.motion.quaternion.premultiply(deltaQuaternion);
    }
    function gunPose(kind,view){
      // All firearm slots share the authored glove material and two-bone arm chains.
      fv.model.visible=true;fv.setKnifePartVisible(false);if(fv.localKnife)fv.localKnife.visible=false;
      fv.mixer.setTime(0);curlLeft();fv.motion.updateWorldMatrix(true,true);
      if(kind==='rifle'){
        rightTarget.set(-.155,-.035,0);leftTarget.set(.10,.018,0);
        desiredGripAxis.set(.24,.97,0);
        leftHandleAxis.set(1,0,0);
      }else if(kind==='pistol'){
        rightTarget.set(0,-.15,-.07);leftTarget.set(-.05,-.12,-.13);
        desiredGripAxis.set(0,Math.cos(-.20),Math.sin(-.20));
        leftHandleAxis.copy(desiredGripAxis);
      }else{
        // Grasp the rear of the wooden fore-end; its midpoint is beyond this
        // authored left arm's reach at the shotgun's existing view position.
        rightTarget.set(0,-.12,.13);leftTarget.set(0,-.05,-.34);
        desiredGripAxis.set(0,Math.cos(-.15),Math.sin(-.15));
        leftHandleAxis.set(0,0,-1);
      }
      view.localToWorld(rightTarget);view.localToWorld(leftTarget);
      desiredGripAxis.transformDirection(view.matrixWorld);
      leftHandleAxis.transformDirection(view.matrixWorld);
      fv.motion.localToWorld(rightHint.set(.44,-.56,-.20));fv.motion.localToWorld(leftHint.set(-.40,-.50,-.50));
      bones[2].getWorldQuaternion(rightRotation);bones[5].getWorldQuaternion(leftRotation);
      // Turn the closed knife fist onto the firearm grip without changing its
      // finger curl, then subtract its measured socket to solve the wrist.
      rightHandleAxis.copy(rightHandleAxisLocal).applyQuaternion(rightRotation).normalize();
      gripTurn.setFromUnitVectors(rightHandleAxis,desiredGripAxis);
      rightRotation.premultiply(gripTurn).normalize();
      // Support palms face up under long guns, and inward from the left for a
      // two-hand pistol hold. The measured knuckle span follows the weapon.
      leftPalmNormal.set(kind==='pistol'?1:0,kind==='pistol'?0:1,0).transformDirection(fv.motion.matrixWorld);
      leftPalmNormal.addScaledVector(leftHandleAxis,-leftPalmNormal.dot(leftHandleAxis)).normalize();
      const worldThird=handOffsetL.crossVectors(leftPalmNormal,leftHandleAxis).normalize();
      gripBasis.makeBasis(leftPalmNormal,leftHandleAxis,worldThird);
      leftRotation.setFromRotationMatrix(gripBasis).multiply(leftGripBasisInverse).normalize();
      bones[2].getWorldScale(worldScaleR);bones[5].getWorldScale(worldScaleL);
      handOffsetR.copy(rightGripLocal).multiply(worldScaleR).applyQuaternion(rightRotation);
      handOffsetL.copy(leftGripLocal).multiply(worldScaleL).applyQuaternion(leftRotation);
      rightTarget.sub(handOffsetR);leftTarget.sub(handOffsetL);
      solveArm(bones[0],bones[1],bones[2],rightTarget,rightHint,rightRotation);
      solveArm(bones[3],bones[4],bones[5],leftTarget,leftHint,leftRotation);
      fv.model.updateMatrixWorld(true);
      fv.model.traverse(o=>{if(o.isSkinnedMesh)o.skeleton.update();});
    }

    const baseUpdate=fv.update.bind(fv);
    fv.update=function(dt,running,moving){
      restore();baseUpdate(dt,running,moving);
      const kind=ws.active,view=ws.viewCache[kind];
      if(kind!=='knife'&&view){
        const sprint=this.sprintBlend||0,move=this.moveBlend||0,t=this.time;
        this.motion.position.set(Math.sin(t*7)*.004*move,-Math.abs(Math.cos(t*7))*.005*move-.025*sprint,-.025*sprint);
        this.motion.rotation.set(-.025*sprint,0,-.012*sprint);
        const shots=Number(window.gameDiagnostics.shotsFired)||0;
        if(shots!==this.__akLastShots){this.__akRecoil=.024;this.__akLastShots=shots;}
        this.__akRecoil=(this.__akRecoil||0)*Math.exp(-18*Math.max(0,dt));
        this.motion.position.z+=this.__akRecoil||0;
        gunPose(kind,view);
      }
      applyTransition();
      updateBelts();
      if(window.gameDiagnostics){window.gameDiagnostics.ak47GloveHands=kind==='rifle';window.gameDiagnostics.weaponDrawCurveApplied=!!ws.__equipmentSwitch?.switching;}
    };

    const originalHud=ws.refreshHud.bind(ws);
    ws.refreshHud=function(){originalHud();if(this.active==='rifle'){
      if(this.ammoNameEl)this.ammoNameEl.textContent='AK-47';if(this.label)this.label.textContent='AK-47';
    }};
    const slot=document.querySelector('[data-weapon="rifle"] text');if(slot)slot.textContent='AK-47';
    ws.refreshHud();
    const belts=new WeakMap();
    function attachBelt(combat,body){
      if(!combat?.knife||!body)return null;if(belts.has(body))return belts.get(body);
      let pelvis=null;body.traverse(o=>{if(!pelvis&&/^pelvis_|^hips|^mixamorighips/i.test(o.name))pelvis=o;});
      if(!pelvis)return null;
      const knife=combat.knife.clone(true);knife.name='stowed_knife_on_belt';
      const s=pelvis.getWorldScale(V());knife.position.set(.20/Math.abs(s.x),-.05/Math.abs(s.y),.08/Math.abs(s.z));
      knife.rotation.set(0,0,Math.PI/2);knife.visible=false;pelvis.add(knife);belts.set(body,knife);return knife;
    }
    function updateBelts(){
      let localBody,remote;try{localBody=playerModel;remote=opponentAvatar;}catch(_){}
      const local=attachBelt(ws.knifeCombat,localBody);if(local)local.visible=ws.active!=='knife';
      if(remote){const belt=attachBelt(remote.knifeCombat,remote.model);if(belt)belt.visible=remote.weapon!=='knife';}
      if(window.gameDiagnostics)window.gameDiagnostics.knifeBeltStow=!!local;
    }
    // Provide inspectable provenance without adding controls to the combat HUD.
    window.almasWeaponCredits={model:{author:'Valerij Dancenko (Bortensol)',license:'CC-BY-4.0',url:'https://sketchfab.com/3d-models/ak-47-game-ready-model-cdef9a881faa41dba65270872c205844'},animation:draw.source};
    Object.assign(window.gameDiagnostics,{ak47Loaded:true,ak47Triangles:12512,ak47Source:'supplied Sketchfab GLB',ak47AuthoredGloveRig:true,allGunsUseGloveHands:true,weaponSwitchAnimationSource:'Cransh / GitHub FPS-Arms-3D',weaponSwitchAnimationLicense:'CC-BY-4.0',rightGloveGripSocket:rightGripLocal.toArray(),leftGloveGripSocket:leftGripLocal.toArray(),firearmGripUsesMeasuredGloveSockets:true});
  }
  wait();
})();
