(function(){
  'use strict';
  const V=()=>new THREE.Vector3(),Q=()=>new THREE.Quaternion();
  const required=['AK_Idle','AK_Draw','AK_Walk','AK_Run','AK_Shot','AK_Reload','AK_Reload_var2','AK_Reload_full'];
  window.almasValidateAuthoredWeaponAnimation=function(data,model){
    const tuple=(a,n)=>Array.isArray(a)&&a.length===n&&a.every(Number.isFinite);
    const quaternion=(a)=>tuple(a,4)&&Math.abs(a.reduce((sum,x)=>sum+x*x,0)-1)<.002;
    const expected=['R_point1_032','R_point2_033','R_point3_034','R_middle1_036','R_middle2_037','R_middle3_038','R_ring1_041','R_ring2_042','R_ring3_043','R_pink1_045','R_pink2_046','R_pink3_047','R_thumb1_028','R_thumb2_029','R_thumb3_030','L_point1_00','L_point2_09','L_point3_010','L_middle1_012','L_middle2_013','L_middle3_014','L_ring1_017','L_ring2_018','L_ring3_019','L_pink1_021','L_pink2_022','L_pink3_023','L_thumb1_05','L_thumb2_06','L_thumb3_07'];
    const qs=[9,22,29,33,37,41,45,...expected.map((_,i)=>49+i*4)];
    const validFrame=a=>tuple(a,169)&&qs.every(o=>quaternion(a.slice(o,o+4)));
    if(data?.formatVersion!==1||data.stride!==169||!validFrame(data.reference)||typeof data.source?.repository!=='string')throw Error('Authored weapon format invalid');
    if(data.target?.fingerBones?.length!==30||new Set(data.target.fingerBones).size!==30||data.target.fingerBones.some((n,i)=>n!==expected[i]))throw Error('Authored finger mapping invalid');
    for(const name of expected){const rest=data.target.fingerRest?.[name];if(!tuple(rest?.position,3)||!tuple(rest?.scale,3)||rest.scale.some(s=>s<=0)||!quaternion(rest?.quaternion))throw Error('Authored finger rest invalid');}
    if(!data.target.palms||Object.keys(data.target.palms).length!==2||!['palm_2_040','palm_016'].every(name=>quaternion(data.target.palms[name]?.quaternion)))throw Error('Authored palm mapping invalid');
    for(const name of required){
      const clip=data.clips?.[name];
      if(!Number.isFinite(clip?.duration)||clip.duration<=0||!Array.isArray(clip.times)||clip.times.length<2||clip.frames?.length!==clip.times.length)throw Error('Authored clip missing: '+name);
      if(Math.abs(clip.times[0])>1e-8||Math.abs(clip.times[clip.times.length-1]-clip.duration)>1e-6)throw Error('Authored timeline invalid');
      for(let i=0;i<clip.times.length;i++)if(!Number.isFinite(clip.times[i])||(i&&clip.times[i]<=clip.times[i-1])||!validFrame(clip.frames[i]))throw Error('Authored clip damaged: '+name);
    }
    for(const name of [...data.target.fingerBones,...Object.keys(data.target.palms)])if(!model.getObjectByName(name))throw Error('Authored target bone missing: '+name);
    return true;
  };
  window.almasCreateAuthoredWeaponAnimation=function(options){
    const {fv,ws,drawData:data,bones,solveArm,rightGripLocal,leftGripLocal}=options;
    const ref=data.reference,gun=ws.viewCache.rifle;
    const gunBaseP=gun.position.clone().add(V().set(-.06,-.065,-.23));
    const alignment=Q().setFromEuler(new THREE.Euler(.10,.08,0,'YXZ'));
    const gunBaseQ=gun.quaternion.clone();
    const refGunQ=Q().fromArray(ref,29),inverseRefGunQ=refGunQ.clone().invert();
    const refGunP=V().fromArray(ref,26),motionScale=.63;
    const fingers=data.target.fingerBones.map(name=>fv.model.getObjectByName(name));
    const palms=Object.entries(data.target.palms).map(([name,pose])=>({bone:fv.model.getObjectByName(name),q:Q().fromArray(pose.quaternion)}));
    const frame=new Array(data.stride),previous=new Array(data.stride),qa=Q(),qb=Q();
    const qOffsets=[9,22,29,33,37,41,45,...fingers.map((_,i)=>49+i*4)];
    const relativeRef=[6,19].map(o=>V().fromArray(ref,o).sub(refGunP).applyQuaternion(inverseRefGunQ));
    const wristRelativeRef=[9,22].map(o=>inverseRefGunQ.clone().multiply(Q().fromArray(ref,o)).normalize());
    const targets=[V(),V()],hints=[V(),V()],rotations=[Q(),Q()],shoulders=[V(),V()];
    const authoredNeutral=Q();
    const leftGripCorrection=Q().fromArray(ref,22).invert().multiply(Q().setFromAxisAngle(V().set(0,0,1),.50)).multiply(Q().setFromAxisAngle(V().set(1,0,0),.55)).multiply(Q().fromArray(ref,22)).normalize();
    const gloveScales=[bones[2].getWorldScale(V()),bones[5].getWorldScale(V())];
    const gloveWorld=new THREE.Matrix4(),inverseForearm=new THREE.Matrix4();
    const contacts=[V().set(-.184,-.055,-.01134),V().set(.035,.04542565,-.01134)];
    const offsets=[rightGripLocal.clone(),leftGripLocal.clone()];
    // Measure the original glove after applying the supplied rifle fist.
    // Its closed finger cavity differs from the original knife fist.
    for(const p of palms)p.bone.quaternion.copy(p.q);
    for(let i=0;i<fingers.length;i++){
      const bone=fingers[i],rest=data.target.fingerRest[bone.name];
      bone.position.fromArray(rest.position);bone.scale.fromArray(rest.scale);bone.quaternion.fromArray(ref,49+i*4);
    }
    fv.motion.updateWorldMatrix(true,true);
    const cavityNames=[['R_middle1_036','R_middle_039','R_ring1_041','R_ring_044','R_pink1_045','R_pink_048'],['L_middle1_012','L_middle_015','L_ring1_017','L_ring_020','L_pink1_021','L_pink_024']];
    for(let side=0;side<2;side++){
      offsets[side].set(0,0,0);
      for(const name of cavityNames[side])offsets[side].add(bones[side*3+2].worldToLocal(fv.model.getObjectByName(name).getWorldPosition(V())));
      offsets[side].multiplyScalar(1/cavityNames[side].length);
    }
    options.restore();
    let clock=0,shotAt=-Infinity,reloadName='',reloadDuration=0,reloadCount=0,clipName='',clipAt=0,blendAt=0,lastActive=ws.active;
    const state={clip:'AK_Idle',time:0,reloads:0,update};
    function sample(name,t,out){
      const clip=data.clips[name];t=THREE.MathUtils.clamp(t,0,clip.duration);
      let lo=0,hi=clip.times.length-1;
      while(hi-lo>1){const mid=(hi+lo)>>1;if(clip.times[mid]<=t)lo=mid;else hi=mid;}
      const a=clip.frames[lo],b=clip.frames[hi],w=(t-clip.times[lo])/Math.max(1e-8,clip.times[hi]-clip.times[lo]);
      for(let i=0;i<out.length;i++)out[i]=a[i]+(b[i]-a[i])*w;
      for(const o of qOffsets){qa.fromArray(a,o);qb.fromArray(b,o);qa.slerp(qb,w).toArray(out,o);}
    }
    const fire=ws.fire.bind(ws);
    const weaponUpdate=ws.update.bind(ws);
    ws.update=function(dt){clock+=Number.isFinite(dt)?Math.max(0,dt):0;return weaponUpdate(dt);};
    ws.fire=function(){
      const kind=this.active,ammo=this.ammo[kind],shots=Number(window.gameDiagnostics?.shotsFired)||0,beforeReload=this.reloadTime;
      const result=fire.apply(null,arguments);
      if(kind==='rifle'){
        if(this.ammo[kind]<ammo||(Number(window.gameDiagnostics?.shotsFired)||0)>shots)shotAt=clock;
        if(beforeReload<=0&&this.reloadTime>0){
          reloadName=required[5+(reloadCount++%3)];reloadDuration=data.clips[reloadName].duration;this.reloadTime=reloadDuration;state.reloads=reloadCount;
        }
      }
      return result;
    };
    ws.__equipmentSwitch?.subscribe(event=>{if(['start','cancel','selected'].includes(event.type)){reloadName='';shotAt=-Infinity;clipName='';}});
    function update(dt,running,moving){
      dt=Number.isFinite(dt)?Math.max(0,dt):0;
      if(ws.active!=='rifle'){lastActive=ws.active;reloadName='';clipName='';return false;}
      if(lastActive!=='rifle'){clipName='';shotAt=-Infinity;}lastActive='rifle';
      const switching=ws.__equipmentSwitch;
      let name,time;
      if(switching?.switching){name='AK_Draw';time=(switching.phase==='holster'?1-switching.progress:switching.progress)*data.clips.AK_Draw.duration;}
      else if(ws.reloadTime>0){
        if(!reloadName){reloadName='AK_Reload';reloadDuration=ws.reloadTime;}
        name=reloadName;time=(1-ws.reloadTime/Math.max(.001,reloadDuration))*data.clips[name].duration;
      }else if(clock-shotAt<data.clips.AK_Shot.duration){name='AK_Shot';time=clock-shotAt;reloadName='';}
      else{name=moving?(running?'AK_Run':'AK_Walk'):'AK_Idle';time=0;reloadName='';}
      if(name!==clipName){
        if(clipName)for(let i=0;i<frame.length;i++)previous[i]=frame[i];else for(let i=0;i<frame.length;i++)previous[i]=ref[i];
        clipName=name;clipAt=clock;blendAt=clock;
      }
      if(['AK_Idle','AK_Walk','AK_Run'].includes(name))time=(clock-clipAt)%data.clips[name].duration;
      sample(name,time,frame);
      // Short transitions preserve the authored finger/arm poses without snaps.
      const blend=THREE.MathUtils.clamp((clock-blendAt)/.10,0,1);
      if(blend<1&&!switching?.switching){
        const raw=frame.slice();
        for(let i=0;i<frame.length;i++)frame[i]=previous[i]+(frame[i]-previous[i])*blend;
        for(const o of qOffsets){qa.fromArray(previous,o);qb.fromArray(raw,o);qa.slerp(qb,blend).normalize().toArray(frame,o);}
      }
      fv.motion.position.set(0,0,0);fv.motion.quaternion.identity();
      fv.model.visible=true;fv.setKnifePartVisible(false);if(fv.localKnife)fv.localKnife.visible=false;
      for(const p of palms)p.bone.quaternion.copy(p.q);
      for(let i=0;i<fingers.length;i++){
        const bone=fingers[i],rest=data.target.fingerRest[bone.name];
        bone.position.fromArray(rest.position);bone.scale.fromArray(rest.scale);bone.quaternion.fromArray(frame,49+i*4).normalize();
      }
      const sourceDelta=Q().fromArray(frame,29).multiply(inverseRefGunQ).normalize();
      const motionWeight=name==='AK_Run'?.16:name==='AK_Walk'?.65:1;
      const delta=Q().slerp(sourceDelta,motionWeight).normalize();
      const poseCorrection=delta.clone().multiply(sourceDelta.clone().invert());
      gun.position.copy(gunBaseP).add(V().fromArray(frame,26).sub(refGunP).multiplyScalar(motionScale*motionWeight).applyQuaternion(alignment));
      gun.quaternion.copy(alignment).multiply(delta).multiply(gunBaseQ);
      if(name.startsWith('AK_Reload')){
        const duration=data.clips[name].duration;
        const framing=THREE.MathUtils.clamp(Math.min(time/.10,(duration-time)/.15),0,1);
        gun.position.y-=.185*framing;
      }
      if(switching?.switching){
        const p=switching.phase==='holster'?1-switching.progress:switching.progress;
        gun.position.y-=.62*(1-p*p*(3-2*p));
      }
      fv.motion.updateWorldMatrix(true,true);
      const rootQ=fv.motion.getWorldQuaternion(Q()),sourceGunQ=Q().fromArray(frame,29),sourceGunP=V().fromArray(frame,26);
      for(let side=0;side<2;side++){
        const i=side*3,o=side?19:6,wrist=bones[i+2];
        const sourceWrist=Q().fromArray(frame,side?22:9);
        if(motionWeight<1){
          const relativeWrist=sourceGunQ.clone().invert().multiply(sourceWrist).normalize();
          sourceWrist.copy(sourceGunQ).multiply(wristRelativeRef[side].clone().slerp(relativeWrist,motionWeight));
        }
        if(side)sourceWrist.multiply(leftGripCorrection);
        rotations[side].copy(rootQ).multiply(alignment).multiply(poseCorrection).multiply(sourceWrist).normalize();
        const scale=gloveScales[side];
        const contact=gun.localToWorld(contacts[side].clone());
        const path=V().fromArray(frame,o).sub(sourceGunP).applyQuaternion(sourceGunQ.clone().invert()).sub(relativeRef[side]).applyQuaternion(sourceGunQ).multiplyScalar(motionScale*motionWeight).applyQuaternion(poseCorrection).applyQuaternion(alignment).applyQuaternion(rootQ);
        targets[side].copy(contact).sub(offsets[side].clone().multiply(scale).applyQuaternion(rotations[side])).add(path);
        hints[side].copy(targets[side]).add(V().fromArray(frame,side?16:3).sub(V().fromArray(frame,o)).applyQuaternion(poseCorrection).applyQuaternion(alignment).applyQuaternion(rootQ));
        const forearmLength=bones[i+1].getWorldPosition(V()).distanceTo(wrist.getWorldPosition(V()))*.50;
        const straightHint=targets[side].clone().addScaledVector(wrist.position.clone().normalize().applyQuaternion(rotations[side]),-forearmLength);
        hints[side].copy(straightHint);
        // Keep shoulder ends below the view, with a shorter forearm and an
        // elbow direction biased toward a relaxed, straighter wrist.
        const upperLength=bones[i].getWorldPosition(V()).distanceTo(bones[i+1].getWorldPosition(V()));
        shoulders[side].copy(straightHint).add(V().set(side?-.03:.03,-.25,.18).normalize().multiplyScalar(upperLength).applyQuaternion(rootQ));
        bones[i].position.copy(bones[i].parent.worldToLocal(shoulders[side]));
        bones[i+1].scale.z*=.50;
        bones[i].updateMatrixWorld(true);
        const previousNeutral=wrist.userData.akGripNeutralInverse;
        wrist.userData.akGripNeutralInverse=authoredNeutral;
        solveArm(bones[i],bones[i+1],wrist,targets[side],hints[side],rotations[side]);
        wrist.userData.akGripNeutralInverse=previousNeutral;
        // Shorten the forearm while keeping the original glove's complete
        // world transform. An inverse parent matrix prevents its fingers from
        // inheriting the forearm compression or shear during wrist rotation.
        gloveWorld.compose(targets[side],rotations[side],scale);
        inverseForearm.copy(wrist.parent.matrixWorld).invert();
        wrist.matrix.copy(inverseForearm).multiply(gloveWorld);
        wrist.matrixAutoUpdate=false;
        wrist.updateMatrixWorld(true);
      }
      fv.model.updateMatrixWorld(true);fv.model.traverse(o=>{if(o.isSkinnedMesh)o.skeleton.update();});
      fv.motion.updateWorldMatrix(true,true);
      state.clip=name;state.time=time;
      Object.assign(window.gameDiagnostics,{authoredWeaponClip:name,authoredWeaponTime:time,authoredWeaponSource:data.source.repository,authoredWeaponAnimationCount:required.length,authoredWeaponAnimationsOnly:true});
      return true;
    }
    return state;
  };
})();
