(function(){
  'use strict';
  if (window.__almasCircularMinimap) return;
  window.__almasCircularMinimap = true;

  const SIZE = 256;
  const CSS_SIZE = 'clamp(112px, 15.5vw, 146px)';

  function waitForGame(){
    let sc, rnd, mapObj;
    try { sc = scene; rnd = renderer; mapObj = worldMap; } catch (_) {}
    if (!window.gameDiagnostics || !window.gameDiagnostics.ready || !sc || !rnd || !mapObj) {
      requestAnimationFrame(waitForGame);
      return;
    }
    install(sc, rnd, mapObj);
  }

  function install(sc, rnd, mapObj){
    if (document.getElementById('almasMiniMap')) return;

    const wrap = document.createElement('div');
    wrap.id = 'almasMiniMap';
    Object.assign(wrap.style, {
      position: 'fixed',
      left: 'max(12px, env(safe-area-inset-left))',
      top: 'max(12px, env(safe-area-inset-top))',
      width: CSS_SIZE,
      aspectRatio: '1 / 1',
      borderRadius: '50%',
      overflow: 'hidden',
      pointerEvents: 'none',
      zIndex: '14',
      border: '2px solid rgba(225,235,228,.68)',
      boxShadow: '0 4px 14px rgba(0,0,0,.34), inset 0 0 0 1px rgba(255,255,255,.12)',
      background: 'rgba(37,47,43,.80)',
      backdropFilter: 'blur(2px)'
    });

    const canvas = document.createElement('canvas');
    canvas.width = SIZE;
    canvas.height = SIZE;
    canvas.style.width = '100%';
    canvas.style.height = '100%';
    canvas.style.display = 'block';
    wrap.appendChild(canvas);
    document.body.appendChild(wrap);

    const ctx = canvas.getContext('2d');
    const bg = document.createElement('canvas');
    bg.width = SIZE;
    bg.height = SIZE;
    const bgCtx = bg.getContext('2d');

    const bounds = new THREE.Box3().setFromObject(mapObj);
    const center = bounds.getCenter(new THREE.Vector3());
    const mapSize = bounds.getSize(new THREE.Vector3());
    const half = Math.max(mapSize.x, mapSize.z) * 0.52 || 1;

    function drawFallback(){
      bgCtx.clearRect(0,0,SIZE,SIZE);
      bgCtx.fillStyle = '#31403a';
      bgCtx.fillRect(0,0,SIZE,SIZE);
      bgCtx.strokeStyle = 'rgba(220,230,224,.12)';
      bgCtx.lineWidth = 1;
      for(let i=1;i<6;i++){
        const p = i * SIZE / 6;
        bgCtx.beginPath(); bgCtx.moveTo(p,0); bgCtx.lineTo(p,SIZE); bgCtx.stroke();
        bgCtx.beginPath(); bgCtx.moveTo(0,p); bgCtx.lineTo(SIZE,p); bgCtx.stroke();
      }
    }

    function renderTopView(){
      drawFallback();
      let characterObj = null;
      let opponentRoot = null;
      try { characterObj = character; } catch (_) {}
      try {
        const opp = window.getOpponentAvatar && window.getOpponentAvatar();
        opponentRoot = opp && opp.root;
      } catch (_) {}

      const oldCharVisible = characterObj ? characterObj.visible : null;
      const oldOppVisible = opponentRoot ? opponentRoot.visible : null;
      const oldFog = sc.fog;
      const oldBackground = sc.background;
      const oldTarget = rnd.getRenderTarget();
      const oldAutoClear = rnd.autoClear;
      const oldClear = rnd.getClearColor(new THREE.Color()).clone();
      const oldAlpha = rnd.getClearAlpha();

      const target = new THREE.WebGLRenderTarget(SIZE, SIZE, {
        depthBuffer: true,
        stencilBuffer: false
      });
      const cam = new THREE.OrthographicCamera(-half, half, half, -half, 0.1, Math.max(1000, mapSize.y + half * 8));
      cam.position.set(center.x, bounds.max.y + Math.max(120, half * 2.5), center.z);
      cam.up.set(0,0,-1);
      cam.lookAt(center.x, bounds.min.y, center.z);
      cam.updateProjectionMatrix();

      try {
        if (characterObj) characterObj.visible = false;
        if (opponentRoot) opponentRoot.visible = false;
        sc.fog = null;
        sc.background = new THREE.Color(0x33443c);
        rnd.autoClear = true;
        rnd.setRenderTarget(target);
        rnd.setClearColor(0x33443c, 1);
        rnd.clear(true, true, true);
        rnd.render(sc, cam);

        const pixels = new Uint8Array(SIZE * SIZE * 4);
        rnd.readRenderTargetPixels(target, 0, 0, SIZE, SIZE, pixels);
        const img = bgCtx.createImageData(SIZE, SIZE);
        for (let y=0; y<SIZE; y++) {
          const srcY = SIZE - 1 - y;
          const srcOff = srcY * SIZE * 4;
          const dstOff = y * SIZE * 4;
          img.data.set(pixels.subarray(srcOff, srcOff + SIZE * 4), dstOff);
        }
        bgCtx.putImageData(img, 0, 0);
      } catch (err) {
        console.warn('[ALMAS minimap] top view fallback', err);
        drawFallback();
      } finally {
        if (characterObj) characterObj.visible = oldCharVisible;
        if (opponentRoot) opponentRoot.visible = oldOppVisible;
        sc.fog = oldFog;
        sc.background = oldBackground;
        rnd.setRenderTarget(oldTarget);
        rnd.autoClear = oldAutoClear;
        rnd.setClearColor(oldClear, oldAlpha);
        target.dispose();
      }
    }

    function worldToMap(x,z){
      return {
        x: SIZE * (0.5 + (x - center.x) / (2 * half)),
        y: SIZE * (0.5 + (z - center.z) / (2 * half))
      };
    }

    function drawArrow(x,y,yaw){
      ctx.save();
      ctx.translate(x,y);
      ctx.rotate(-(yaw || 0));
      ctx.beginPath();
      ctx.moveTo(0,-11);
      ctx.lineTo(7,8);
      ctx.lineTo(0,5);
      ctx.lineTo(-7,8);
      ctx.closePath();
      ctx.fillStyle = '#f6fbf8';
      ctx.shadowColor = 'rgba(0,0,0,.65)';
      ctx.shadowBlur = 4;
      ctx.fill();
      ctx.restore();
    }

    function drawOpponent(x,y){
      ctx.save();
      ctx.beginPath();
      ctx.arc(x,y,6.5,0,Math.PI*2);
      ctx.fillStyle = '#ff5a55';
      ctx.shadowColor = 'rgba(0,0,0,.65)';
      ctx.shadowBlur = 4;
      ctx.fill();
      ctx.lineWidth = 2;
      ctx.strokeStyle = 'rgba(255,255,255,.88)';
      ctx.stroke();
      ctx.restore();
    }

    renderTopView();

    let lastDraw = 0;
    function frame(now){
      requestAnimationFrame(frame);
      if (now - lastDraw < 50) return;
      lastDraw = now;

      ctx.clearRect(0,0,SIZE,SIZE);
      ctx.save();
      ctx.beginPath();
      ctx.arc(SIZE/2,SIZE/2,SIZE/2-2,0,Math.PI*2);
      ctx.clip();
      ctx.drawImage(bg,0,0,SIZE,SIZE);
      ctx.fillStyle = 'rgba(18,26,23,.16)';
      ctx.fillRect(0,0,SIZE,SIZE);

      let state = null;
      try { state = window.getLocalPlayerState && window.getLocalPlayerState(); } catch (_) {}
      if (state && Number.isFinite(state.x) && Number.isFinite(state.z)) {
        const p = worldToMap(state.x,state.z);
        drawArrow(p.x,p.y,state.yaw || 0);
      }

      try {
        const opp = window.getOpponentAvatar && window.getOpponentAvatar();
        if (opp && opp.root && opp.root.visible) {
          const p = worldToMap(opp.root.position.x, opp.root.position.z);
          drawOpponent(p.x,p.y);
        }
      } catch (_) {}
      ctx.restore();

      ctx.save();
      ctx.beginPath();
      ctx.arc(SIZE/2,SIZE/2,SIZE/2-4,0,Math.PI*2);
      ctx.strokeStyle = 'rgba(230,238,233,.42)';
      ctx.lineWidth = 2;
      ctx.stroke();
      ctx.restore();
    }
    requestAnimationFrame(frame);

    Object.assign(window.gameDiagnostics, {
      circularMiniMap: true,
      miniMapTopLeft: true,
      miniMapShowsOpponent: true,
      miniMapUsesRealMapTopView: true
    });
  }

  waitForGame();
})();
