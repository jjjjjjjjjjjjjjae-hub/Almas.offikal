(function(){
  'use strict';
  if (window.__almasSmartUpdaterV3) return;
  window.__almasSmartUpdaterV3 = true;
  window.__almasSmartUpdater = true;

  const MANIFEST_URL='https://raw.githubusercontent.com/jjjjjjjjjjjjjjae-hub/Almas.offikal/main/game-update/smart-manifest.json';
  const DB_NAME='almas_game_updates', DB_VERSION=1, STORE='files';
  const STATE_KEY='almas_smart_content_version', READY_KEY='almas_smart_updater_ready', MANIFEST_CACHE_KEY='almas_smart_manifest_cache';
  const FALLBACK_EXTRAS=['run-fov.js','look-sensitivity.js','minimap.js','weapons-runtime-v2.js'];
  const objectUrls=new Map();
  const sleep=ms=>new Promise(r=>setTimeout(r,ms));
  const fmtBytes=n=>{if(!Number.isFinite(n)||n<=0)return'0 Б';const u=['Б','КБ','МБ','ГБ'];let i=0,v=n;while(v>=1024&&i<u.length-1){v/=1024;i++;}return(i===0?Math.round(v):v.toFixed(v>=10?1:2))+' '+u[i];};

  function normalizePath(url){
    if(!url)return'';let s=String(url).replace(/\\/g,'/');
    try{const u=new URL(s,location.href);s=u.pathname;const mark='/android_asset/';const i=s.indexOf(mark);if(i>=0)s=s.slice(i+mark.length);else s=s.replace(/^\/+/, '');}
    catch(_){s=s.replace(/^\.\//,'').replace(/^\/+/, '');}
    try{s=decodeURIComponent(s);}catch(_){}
    return s.split('?')[0].split('#')[0].replace(/^assets\//,'');
  }
  function openDb(){return new Promise((resolve,reject)=>{if(!('indexedDB'in window))return reject(Error('IndexedDB unavailable'));const r=indexedDB.open(DB_NAME,DB_VERSION);r.onupgradeneeded=()=>{const db=r.result;if(!db.objectStoreNames.contains(STORE))db.createObjectStore(STORE,{keyPath:'path'});};r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});}
  function os(db,mode='readonly'){return db.transaction(STORE,mode).objectStore(STORE);}
  function getAll(db){return new Promise((res,rej)=>{const r=os(db).getAll();r.onsuccess=()=>res(r.result||[]);r.onerror=()=>rej(r.error);});}
  function put(db,rec){return new Promise((res,rej)=>{const r=os(db,'readwrite').put(rec);r.onsuccess=()=>res();r.onerror=()=>rej(r.error);});}
  async function fetchManifest(){const c=new AbortController(),t=setTimeout(()=>c.abort(),8000);try{const r=await fetch(MANIFEST_URL+'?_='+Date.now(),{cache:'no-store',signal:c.signal});if(!r.ok)throw Error('Manifest HTTP '+r.status);const m=await r.json();try{localStorage.setItem(MANIFEST_CACHE_KEY,JSON.stringify(m));}catch(_){}return m;}finally{clearTimeout(t);}}
  function cachedManifest(){try{return JSON.parse(localStorage.getItem(MANIFEST_CACHE_KEY)||'null');}catch(_){return null;}}

  function ensureOverlay(){
    let root=document.getElementById('almasSmartUpdate');if(root)return root;
    root=document.createElement('div');root.id='almasSmartUpdate';root.innerHTML='<div class="asu-card"><div class="asu-logo">ALMAS <b>GAME</b></div><div class="asu-title">ЖАҢАРТУ</div><div class="asu-version" id="asuVersion">Нұсқа тексерілуде…</div><div class="asu-track"><div class="asu-bar" id="asuBar"></div></div><div class="asu-row"><span id="asuStatus">Сервермен байланыс…</span><span id="asuPercent">0%</span></div><div class="asu-detail" id="asuDetail"></div></div>';
    const st=document.createElement('style');st.textContent='#almasSmartUpdate{position:fixed;inset:0;z-index:2147483647;display:none;align-items:center;justify-content:center;background:radial-gradient(circle at 50% 25%,#25332f 0,#151c1a 45%,#090d0c 100%);font-family:Arial,sans-serif;color:#f3f7f5;pointer-events:all}#almasSmartUpdate .asu-card{width:min(86vw,560px);padding:30px 28px 26px;border-radius:24px;background:rgba(24,33,30,.96);border:1px solid rgba(255,255,255,.11);box-shadow:0 18px 60px rgba(0,0,0,.48)}#almasSmartUpdate .asu-logo{font-size:clamp(28px,6vw,46px);font-weight:900;letter-spacing:2px;text-align:center;margin-bottom:26px}#almasSmartUpdate .asu-logo b{color:#86d9a4}#almasSmartUpdate .asu-title{text-align:center;font-weight:800;font-size:18px;letter-spacing:2.5px;margin-bottom:8px}#almasSmartUpdate .asu-version{text-align:center;color:rgba(239,247,242,.68);font-size:13px;margin-bottom:24px}#almasSmartUpdate .asu-track{height:13px;border-radius:99px;background:rgba(255,255,255,.10);overflow:hidden}#almasSmartUpdate .asu-bar{height:100%;width:0;border-radius:99px;background:linear-gradient(90deg,#70c58d,#b2efc4);transition:width .15s ease}#almasSmartUpdate .asu-row{display:flex;justify-content:space-between;gap:12px;margin-top:12px;font-size:14px;font-weight:700}#almasSmartUpdate .asu-detail{margin-top:8px;text-align:center;color:rgba(239,247,242,.58);font-size:12px;min-height:16px}';document.head.appendChild(st);document.body.appendChild(root);return root;
  }
  function progress(p,s,d){const q=id=>document.getElementById(id);if(q('asuBar'))q('asuBar').style.width=Math.max(0,Math.min(100,p))+'%';if(q('asuPercent'))q('asuPercent').textContent=Math.round(p)+'%';if(q('asuStatus'))q('asuStatus').textContent=s||'';if(q('asuDetail'))q('asuDetail').textContent=d||'';}
  function show(m,n){const r=ensureOverlay();r.style.display='flex';const v=document.getElementById('asuVersion');if(v)v.textContent='Нұсқа '+(m.versionName||m.contentVersion||'');progress(0,'Жаңарту дайындалуда…','0 / '+n+' файл');}
  function hide(){const r=document.getElementById('almasSmartUpdate');if(r)r.style.display='none';}
  function current(rec,item){if(!rec||!rec.blob)return false;if(item.revision!=null&&String(rec.revision||'')===String(item.revision))return true;const legacy=Number(item.legacyVersion||0);return(!rec.revision&&legacy>0&&Number(rec.version||0)>=legacy)?'legacy':false;}

  function b64bytes(s){
    s=String(s||'').replace(/\s+/g,'');const raw=atob(s),u=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)u[i]=raw.charCodeAt(i);return u;
  }
  async function gunzip(u8){
    if(typeof DecompressionStream!=='undefined'){
      const ds=new DecompressionStream('gzip');const ab=await new Response(new Blob([u8]).stream().pipeThrough(ds)).arrayBuffer();return new Uint8Array(ab);
    }
    if(window.fflate&&fflate.gunzipSync)return fflate.gunzipSync(u8);
    throw Error('gzip decompressor unavailable');
  }
  async function fetchPart(url){const r=await fetch(url+(url.includes('?')?'&':'?')+'_='+Date.now(),{cache:'no-store'});if(!r.ok)throw Error('part HTTP '+r.status);return b64bytes(await r.text());}
  async function download(db,item,manifestVersion,onPart){
    let blob;
    if(Array.isArray(item.parts)&&item.parts.length){
      const chunks=[];let total=0;
      for(let i=0;i<item.parts.length;i++){const u=await fetchPart(item.parts[i]);chunks.push(u);total+=u.length;if(onPart)onPart(i+1,item.parts.length,total);}
      const all=new Uint8Array(total);let off=0;for(const u of chunks){all.set(u,off);off+=u.length;}
      const bytes=item.compression==='gzip'?await gunzip(all):all;
      blob=new Blob([bytes],{type:item.mime||'application/octet-stream'});
    }else{
      const r=await fetch(item.url+(item.url.includes('?')?'&':'?')+'_='+Date.now(),{cache:'no-store'});if(!r.ok)throw Error(item.path+': HTTP '+r.status);blob=await r.blob();
    }
    await put(db,{path:normalizePath(item.path),blob,version:Number(manifestVersion)||0,smartVersion:Number(item.fileVersion||1),revision:String(item.revision||''),mime:blob.type||item.mime||''});
    return blob.size||0;
  }
  async function installResolver(db){
    const records=await getAll(db),map=new Map(records.filter(r=>r&&r.path&&r.blob).map(r=>[normalizePath(r.path),r]));
    function resolve(url){const p=normalizePath(url),r=map.get(p);if(!r)return url;if(!objectUrls.has(p))objectUrls.set(p,URL.createObjectURL(r.blob));return objectUrls.get(p);}
    window.__almasResolveUpdatedAsset=resolve;
    if(window.THREE&&THREE.DefaultLoadingManager&&THREE.DefaultLoadingManager.setURLModifier)THREE.DefaultLoadingManager.setURLModifier(resolve);
  }
  async function runScript(db,path){const r=(await getAll(db)).find(x=>normalizePath(x.path)===normalizePath(path)&&x.blob);if(!r)return false;const s=document.createElement('script');s.async=false;s.textContent=(await r.blob.text())+'\n//# sourceURL=almas-smart-update://'+path;document.body.appendChild(s);return true;}
  async function runExtras(db,m){for(const p of(m&&Array.isArray(m.extraScripts)?m.extraScripts:FALLBACK_EXTRAS)){if(p==='smart-updater.js')continue;try{await runScript(db,p);}catch(e){console.warn('[ALMAS update extra]',p,e);}}}

  async function main(){
    let db,m;
    try{
      db=await openDb();try{m=await fetchManifest();}catch(e){console.warn('[ALMAS update] manifest offline',e);m=cachedManifest();if(!m){await installResolver(db);await runExtras(db,null);return;}}
      const cur=new Map((await getAll(db)).map(r=>[normalizePath(r.path),r])),pending=[];
      for(const raw of(Array.isArray(m.files)?m.files:[])){
        if(!raw||!raw.path||(!raw.url&&!(raw.parts&&raw.parts.length)))continue;
        const item=Object.assign({},raw,{path:normalizePath(raw.path)}),rec=cur.get(item.path),ok=current(rec,item);
        if(ok==='legacy'){await put(db,Object.assign({},rec,{revision:String(item.revision||''),smartVersion:Number(item.fileVersion||1)}));}
        else if(!ok)pending.push(item);
      }
      const first=localStorage.getItem(READY_KEY)!=='1';if(pending.length||first)show(m,pending.length);
      if(pending.length){
        const declared=pending.reduce((a,x)=>a+(Number(x.size)||0),0);let done=0;
        for(let i=0;i<pending.length;i++){
          const item=pending[i];progress((i/pending.length)*100,'Жүктелуде: '+(item.label||item.path),(i)+' / '+pending.length+' файл');
          const got=await download(db,item,m.contentVersion,(a,b)=>progress(((i+a/b)/pending.length)*100,'Жүктелуде: '+(item.label||item.path),'Бөлік '+a+' / '+b));done+=got;
          progress(declared?Math.min(99,done/declared*100):((i+1)/pending.length*100),'Жаңарту жүктелуде…',declared?fmtBytes(done)+' / '+fmtBytes(declared):(i+1)+' / '+pending.length+' файл');
        }
        localStorage.setItem(STATE_KEY,String(m.contentVersion||0));localStorage.setItem(READY_KEY,'1');progress(100,'Жаңарту аяқталды','Ойын қайта іске қосылуда…');await sleep(500);location.reload();return;
      }
      localStorage.setItem(STATE_KEY,String(m.contentVersion||0));localStorage.setItem(READY_KEY,'1');await installResolver(db);if(first){progress(100,'Жаңарту жүйесі дайын','Ойын ашылуда…');await sleep(300);}hide();await runExtras(db,m);
      if(window.gameDiagnostics)Object.assign(window.gameDiagnostics,{smartUpdater:true,smartUpdaterVersion:3,updateManifestVersion:Number(m.contentVersion)||0,updateOnlyChangedFiles:true,reinstallRestore:true,binaryAssetCache:true,multipartModelUpdates:true});
    }catch(e){console.error('[ALMAS smart updater v3]',e);try{if(db){await installResolver(db);hide();await runExtras(db,m);}}catch(_){} }
  }
  main();
})();
