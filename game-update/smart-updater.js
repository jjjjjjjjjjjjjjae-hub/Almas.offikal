(function(){
  'use strict';
  if (window.__almasSmartUpdater) return;
  window.__almasSmartUpdater = true;

  const MANIFEST_URL = 'https://raw.githubusercontent.com/jjjjjjjjjjjjjjae-hub/Almas.offikal/main/game-update/smart-manifest.json';
  const DB_NAME = 'almas_game_updates';
  const DB_VERSION = 1;
  const STORE = 'files';
  const STATE_KEY = 'almas_smart_content_version';
  const READY_KEY = 'almas_smart_updater_ready';

  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const fmtBytes = n => {
    if (!Number.isFinite(n) || n <= 0) return '0 Б';
    const u=['Б','КБ','МБ','ГБ']; let i=0, v=n;
    while(v>=1024 && i<u.length-1){v/=1024;i++;}
    return (i===0?Math.round(v):v.toFixed(v>=10?1:2))+' '+u[i];
  };

  function normalizePath(url){
    if(!url) return '';
    let s=String(url).replace(/\\/g,'/');
    try{
      const u=new URL(s, location.href);
      s=u.pathname;
      const mark='/android_asset/';
      const idx=s.indexOf(mark);
      if(idx>=0) s=s.slice(idx+mark.length);
      else s=s.replace(/^\/+/, '');
    }catch(_){ s=s.replace(/^\.\//,'').replace(/^\/+/, ''); }
    return decodeURIComponent(s).split('?')[0].split('#')[0];
  }

  function openDb(){
    return new Promise((resolve,reject)=>{
      if(!('indexedDB' in window)) return reject(new Error('IndexedDB unavailable'));
      const req=indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded=()=>{
        const db=req.result;
        if(!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE,{keyPath:'path'});
      };
      req.onsuccess=()=>resolve(req.result);
      req.onerror=()=>reject(req.error || new Error('DB open failed'));
    });
  }

  function store(db, mode='readonly'){
    return db.transaction(STORE,mode).objectStore(STORE);
  }
  function getAll(db){
    return new Promise((resolve,reject)=>{
      const r=store(db).getAll();
      r.onsuccess=()=>resolve(r.result||[]);
      r.onerror=()=>reject(r.error);
    });
  }
  function put(db, rec){
    return new Promise((resolve,reject)=>{
      const r=store(db,'readwrite').put(rec);
      r.onsuccess=()=>resolve();
      r.onerror=()=>reject(r.error);
    });
  }

  async function fetchManifest(){
    const ctrl=new AbortController();
    const timer=setTimeout(()=>ctrl.abort(),7000);
    try{
      const r=await fetch(MANIFEST_URL+(MANIFEST_URL.includes('?')?'&':'?')+'_='+Date.now(),{cache:'no-store',signal:ctrl.signal});
      if(!r.ok) throw new Error('Manifest HTTP '+r.status);
      return await r.json();
    }finally{clearTimeout(timer);}
  }

  function ensureOverlay(){
    let root=document.getElementById('almasSmartUpdate');
    if(root) return root;
    root=document.createElement('div');
    root.id='almasSmartUpdate';
    root.innerHTML=`
      <div class="asu-card">
        <div class="asu-logo">ALMAS <b>GAME</b></div>
        <div class="asu-title">ЖАҢАРТУ</div>
        <div class="asu-version" id="asuVersion">Нұсқа тексерілуде…</div>
        <div class="asu-track"><div class="asu-bar" id="asuBar"></div></div>
        <div class="asu-row"><span id="asuStatus">Сервермен байланыс…</span><span id="asuPercent">0%</span></div>
        <div class="asu-detail" id="asuDetail"></div>
      </div>`;
    const style=document.createElement('style');
    style.id='almasSmartUpdateStyle';
    style.textContent=`
      #almasSmartUpdate{position:fixed;inset:0;z-index:2147483647;display:none;align-items:center;justify-content:center;background:radial-gradient(circle at 50% 25%,#25332f 0,#151c1a 45%,#090d0c 100%);font-family:Arial,sans-serif;color:#f3f7f5;pointer-events:all}
      #almasSmartUpdate .asu-card{width:min(86vw,560px);padding:30px 28px 26px;border-radius:24px;background:rgba(24,33,30,.94);border:1px solid rgba(255,255,255,.11);box-shadow:0 18px 60px rgba(0,0,0,.48)}
      #almasSmartUpdate .asu-logo{font-size:clamp(28px,6vw,46px);font-weight:900;letter-spacing:2px;text-align:center;margin-bottom:26px}#almasSmartUpdate .asu-logo b{color:#86d9a4}
      #almasSmartUpdate .asu-title{text-align:center;font-weight:800;font-size:18px;letter-spacing:2.5px;margin-bottom:8px}
      #almasSmartUpdate .asu-version{text-align:center;color:rgba(239,247,242,.68);font-size:13px;margin-bottom:24px}
      #almasSmartUpdate .asu-track{height:13px;border-radius:99px;background:rgba(255,255,255,.10);overflow:hidden;box-shadow:inset 0 1px 3px rgba(0,0,0,.45)}
      #almasSmartUpdate .asu-bar{height:100%;width:0%;border-radius:99px;background:linear-gradient(90deg,#70c58d,#b2efc4);transition:width .18s ease}
      #almasSmartUpdate .asu-row{display:flex;justify-content:space-between;gap:12px;margin-top:12px;font-size:14px;font-weight:700}
      #almasSmartUpdate .asu-detail{margin-top:8px;text-align:center;color:rgba(239,247,242,.58);font-size:12px;min-height:16px}
    `;
    document.head.appendChild(style);
    document.body.appendChild(root);
    return root;
  }

  function showOverlay(manifest, pendingCount){
    const root=ensureOverlay();
    root.style.display='flex';
    const ver=document.getElementById('asuVersion');
    if(ver) ver.textContent='Нұсқа '+(manifest.versionName || manifest.contentVersion || '');
    setProgress(0,'Жаңарту дайындалуда…','0 / '+pendingCount+' файл');
  }
  function hideOverlay(){
    const root=document.getElementById('almasSmartUpdate');
    if(root) root.style.display='none';
  }
  function setProgress(percent,status,detail){
    const bar=document.getElementById('asuBar');
    const pct=document.getElementById('asuPercent');
    const st=document.getElementById('asuStatus');
    const dt=document.getElementById('asuDetail');
    if(bar) bar.style.width=Math.max(0,Math.min(100,percent))+'%';
    if(pct) pct.textContent=Math.round(percent)+'%';
    if(st) st.textContent=status||'';
    if(dt) dt.textContent=detail||'';
  }

  function recIsCurrent(rec,item){
    if(!rec || !rec.blob) return false;
    if(item.revision != null && String(rec.revision||'')===String(item.revision)) return true;
    const legacy=Number(item.legacyVersion||0);
    if(!rec.revision && legacy>0 && Number(rec.version||0)>=legacy) return 'legacy';
    return false;
  }

  async function migrateLegacy(db,rec,item){
    const next=Object.assign({},rec,{revision:String(item.revision||''),smartVersion:Number(item.fileVersion||1)});
    await put(db,next);
    return next;
  }

  async function downloadFile(db,item,manifestVersion){
    const url=item.url;
    const r=await fetch(url+(url.includes('?')?'&':'?')+'_='+Date.now(),{cache:'no-store'});
    if(!r.ok) throw new Error(item.path+': HTTP '+r.status);
    const blob=await r.blob();
    await put(db,{
      path:normalizePath(item.path),
      blob,
      version:Number(manifestVersion)||0,
      smartVersion:Number(item.fileVersion||1),
      revision:String(item.revision||''),
      mime:blob.type||item.mime||''
    });
    return blob.size||0;
  }

  async function loadScriptFromDb(db,path){
    const all=await getAll(db);
    const rec=all.find(x=>x.path===path && x.blob);
    if(!rec) return false;
    const text=await rec.blob.text();
    const s=document.createElement('script');
    s.async=false;
    s.textContent=text+'\n//# sourceURL=almas-smart-update://'+path;
    document.body.appendChild(s);
    return true;
  }

  async function runExtras(db,manifest){
    const extras=Array.isArray(manifest.extraScripts)?manifest.extraScripts:[];
    for(const path of extras){
      if(path==='smart-updater.js') continue;
      try{ await loadScriptFromDb(db,path); }
      catch(e){ console.warn('[ALMAS smart updater] extra failed',path,e); }
    }
  }

  async function main(){
    let db=null, manifest=null;
    try{
      db=await openDb();
      manifest=await fetchManifest();
      const list=Array.isArray(manifest.files)?manifest.files:[];
      const current=new Map((await getAll(db)).filter(Boolean).map(r=>[r.path,r]));
      const pending=[];

      for(const raw of list){
        if(!raw || !raw.path || !raw.url) continue;
        const item=Object.assign({},raw,{path:normalizePath(raw.path)});
        const rec=current.get(item.path);
        const ok=recIsCurrent(rec,item);
        if(ok==='legacy'){
          const migrated=await migrateLegacy(db,rec,item);
          current.set(item.path,migrated);
        }else if(!ok){
          pending.push(item);
        }
      }

      const firstRun=localStorage.getItem(READY_KEY)!=='1';
      if(pending.length || firstRun){
        showOverlay(manifest,pending.length);
      }

      if(pending.length){
        const totalDeclared=pending.reduce((a,x)=>a+(Number(x.size)||0),0);
        let doneBytes=0;
        for(let i=0;i<pending.length;i++){
          const item=pending[i];
          const basePercent=(i/pending.length)*100;
          setProgress(basePercent,'Жүктелуде: '+(item.label||item.path),(i)+' / '+pending.length+' файл');
          const got=await downloadFile(db,item,manifest.contentVersion);
          doneBytes+=got;
          const percent=totalDeclared ? Math.min(99,(doneBytes/totalDeclared)*100) : ((i+1)/pending.length)*100;
          const detail=totalDeclared ? (fmtBytes(doneBytes)+' / '+fmtBytes(totalDeclared)) : ((i+1)+' / '+pending.length+' файл');
          setProgress(percent,'Жаңарту жүктелуде…',detail);
        }
        localStorage.setItem(STATE_KEY,String(manifest.contentVersion||0));
        localStorage.setItem(READY_KEY,'1');
        setProgress(100,'Жаңарту аяқталды','Ойын қайта іске қосылуда…');
        await sleep(450);
        location.reload();
        return;
      }

      localStorage.setItem(STATE_KEY,String(manifest.contentVersion||0));
      localStorage.setItem(READY_KEY,'1');
      if(firstRun){
        setProgress(100,'Жаңарту жүйесі дайын','Ойын ашылуда…');
        await sleep(420);
      }
      hideOverlay();
      await runExtras(db,manifest);

      if(window.gameDiagnostics){
        Object.assign(window.gameDiagnostics,{
          smartUpdater:true,
          smartUpdaterVersion:1,
          updateManifestVersion:Number(manifest.contentVersion)||0,
          updateOnlyChangedFiles:true,
          reinstallRestore:true
        });
      }
    }catch(e){
      console.error('[ALMAS smart updater]',e);
      try{
        ensureOverlay();
        document.getElementById('almasSmartUpdate').style.display='flex';
        setProgress(0,'Жаңарту серверіне қосылмады','Кэштегі нұсқа ашылады');
        await sleep(900);
        hideOverlay();
        if(db && manifest) await runExtras(db,manifest);
      }catch(_){ }
    }
  }

  main();
})();
