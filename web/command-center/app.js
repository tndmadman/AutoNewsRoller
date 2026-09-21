const $=s=>document.querySelector(s);
let state={stories:[],feeds:[],workers:[],videos:[],counts:{}}, token="", activeFilter="ALL", eventSource=null, refreshTimer=null;
const WORTHY_AGE_OPTIONS=new Set([0,1,2,3,4,6,8,12,24,48,72]);
let worthyAgeHours=Number(localStorage.getItem("autonewsWorthyAgeHours")||0);
if(!WORTHY_AGE_OPTIONS.has(worthyAgeHours))worthyAgeHours=0;
const qp=new URLSearchParams(location.search);
token=qp.get("token")||localStorage.getItem("autonewsToken")||"";
if(qp.get("token")) localStorage.setItem("autonewsToken",token);

function headers(extra={}){const h={...extra};if(token)h["X-AutoNews-Token"]=token;return h}
async function api(path,opt={}){
  opt.headers=headers(opt.headers||{});
  const r=await fetch(path,opt);
  if(r.status===401)throw new Error("AUTH_REQUIRED");
  if(!r.ok){let m="HTTP "+r.status;try{const j=await r.json();m=j.error||m}catch{}throw new Error(m)}
  if(r.status===204)return null;
  return await r.json();
}
function esc(v){return String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]))}
function pct(v){return Math.max(0,Math.min(100,Number(v)||0))}
function age(t){if(!t)return "";const d=(Date.now()-Date.parse(t))/60000;if(d<60)return Math.max(0,Math.round(d))+"m";if(d<1440)return Math.round(d/60)+"h";return Math.round(d/1440)+"d"}
function num(v,d=0){return Number.isFinite(Number(v))?Number(v):d}
const ACTIONABLE_WORTHY_STATUSES=new Set(["DISCOVERED","VERIFIED","WORTHY"]);
function isActionableWorthy(s){
  if(!s)return false;
  if(s.actionableWorthy!==undefined&&s.actionableWorthy!==null)return !!s.actionableWorthy;
  return !!s.worthy&&ACTIONABLE_WORTHY_STATUSES.has(String(s.status||""));
}
function currentStoryFilter(){return $("#statusFilter").value!=="ALL"?$("#statusFilter").value:activeFilter}
function storyAgeHours(s){
  const t=Date.parse(String((s&&s.latestPublishedAt)||""));
  if(!Number.isFinite(t))return null;
  return Math.max(0,(Date.now()-t)/3600000);
}
function withinWorthyAge(s){
  if(worthyAgeHours<=0)return true;
  const h=storyAgeHours(s);
  return h!==null&&h<=worthyAgeHours;
}
function syncWorthyAgeControl(){
  const el=$("#worthyAgeFilter");
  if(!el)return;
  el.value=String(worthyAgeHours);
  const worthyView=currentStoryFilter()==="WORTHY";
  el.disabled=!worthyView;
  el.classList.toggle("activeFilterControl",worthyView&&worthyAgeHours>0);
  el.title=worthyView
    ?(worthyAgeHours>0?"Showing worthy stories published within the last "+worthyAgeHours+" hour(s).":"Showing worthy stories of any age.")
    :"Worthy age filter becomes active when the WORTHY view is selected.";
}
function safeHttpUrl(v){try{const u=new URL(String(v||""));return (u.protocol==="https:"||u.protocol==="http:")?u.href:""}catch{return ""}}
function toast(msg,error=false){const t=$("#toast");t.textContent=msg;t.className="toast show"+(error?" error":"");clearTimeout(t._timer);t._timer=setTimeout(()=>t.className="toast",3500)}

async function loadState(){
  try{
    state=await api("/api/state");
    $("#connection").textContent="LIVE";$("#connection").className="pill ok";
    render();
  }catch(e){
    $("#connection").textContent=e.message==="AUTH_REQUIRED"?"LOCKED":"OFFLINE";$("#connection").className="pill danger";
    if(e.message==="AUTH_REQUIRED"&&!token)setTimeout(authPrompt,100);
  }
}
function render(){
  const c=state.counts||{};
  $("#feedsOnline").textContent=c.feedsOk||0;$("#feedsFailed").textContent=(c.feedsFailed||0)+" failed";
  $("#storiesTracked").textContent=c.stories||0;$("#verifiedCount").textContent=(c.worthy||0)+" worthy / "+(c.verified||0)+" verified";
  $("#queuedCount").textContent=c.queued||0;$("#producingCount").textContent=(c.producing||0)+" active";
  $("#completeCount").textContent=c.videoVersions!=null?c.videoVersions:(c.complete||0);
  $("#completeSub").textContent=(c.toPost||0)+" to post // "+(c.uploaded||0)+" uploaded // "+(c.scrapped||0)+" scrapped";
  $("#workersOnline").textContent=c.workersOnline||0;
  $("#autoThreshold").textContent=Math.round(num(state.autoThreshold)*100)+"%";
  $("#autoQueueState").textContent=state.autoQueue
    ? "SOFT "+Math.round(num(state.softWorthThreshold)*100)+"% // 1-SRC AUTO "+Math.round(num(state.singleSourceAutoQueueThreshold)*100)+"%"
    : "MANUAL QUEUE";
  $("#scanState").textContent=state.scanning?"SCANNING":"STANDBY";
  $("#feedSummary").textContent=(state.feeds||[]).length+" FEEDS";
  syncWorthyAgeControl();renderRails();renderWorkers();renderStories();renderFeeds();renderVideos();drawRadar();
}
function renderRails(){
  const ss=state.stories||[];
  $("#railAll").textContent=ss.length;
  $("#railFound").textContent=ss.filter(x=>x.status==="DISCOVERED").length;
  const worthyAll=ss.filter(isActionableWorthy);
  const worthyVisible=worthyAll.filter(withinWorthyAge);
  $("#railVerified").textContent=currentStoryFilter()==="WORTHY"&&worthyAgeHours>0
    ?worthyVisible.length+"/"+worthyAll.length
    :worthyAll.length;
  $("#railQueued").textContent=ss.filter(x=>x.status==="QUEUED").length;
  $("#railProducing").textContent=ss.filter(x=>x.status==="PRODUCING").length;
  $("#railComplete").textContent=ss.filter(x=>String(x.status).startsWith("COMPLETE")).length;
  $("#railToPost").textContent=ss.filter(x=>String(x.status)==="COMPLETE"&&!x.uploaded&&!x.scrapped).length;
  $("#railUploaded").textContent=ss.filter(x=>!!x.uploaded&&!x.scrapped).length;
  $("#railScrapped").textContent=ss.filter(x=>!!x.scrapped).length;
}
function renderWorkers(){
  const root=$("#workers"),ws=state.workers||[];
  if(!ws.length){root.className="workerGrid emptyState";root.textContent="No workers connected.";return}
  root.className="workerGrid";
  root.innerHTML=ws.map(w=>{
    const m=w.metrics||{},g=m.gpu||{},online=w.online;
    const memTot=num(m.memoryTotalMb),memFree=num(m.memoryFreeMb),ram=memTot?Math.round((memTot-memFree)/memTot*100):0;
    const gpuMem=num(g.memoryTotalMb)?Math.round(num(g.memoryUsedMb)/num(g.memoryTotalMb)*100):0;
    return `<div class="workerCard">
      <div class="workerHead"><div class="workerName">${esc(w.id)}</div><div class="workerState">${online?"● ONLINE":"○ OFFLINE"} // ${esc(w.state||"UNKNOWN")}</div></div>
      <div class="telemetry">
        <div><span>CPU</span><b>${Math.round(num(m.cpuLoad))}%</b></div>
        <div><span>RAM</span><b>${ram}%</b></div>
        <div><span>GPU</span><b>${g.available?Math.round(num(g.utilization))+"%":"N/A"}</b></div>
        <div><span>VRAM</span><b>${g.available?gpuMem+"%":"N/A"}</b></div>
      </div>
      <div class="workerJob">${g.available?esc(g.name)+" // "+Math.round(num(g.memoryUsedMb))+"/"+Math.round(num(g.memoryTotalMb))+" MB // "+Math.round(num(g.temperatureC))+"°C":"NO NVIDIA TELEMETRY"}<br>${w.currentTopic?"JOB: "+esc(w.currentTopic):"IDLE / WAITING FOR QUEUE"}</div>
    </div>`
  }).join("");
}
function storyMatches(s){
  const q=$("#search").value.trim().toLowerCase(),filter=currentStoryFilter();
  const status=String(s.status||"");
  if(filter!=="ALL"){
    if(filter==="COMPLETE"){if(!status.startsWith("COMPLETE"))return false;}
    else if(filter==="TO_POST"){if(status!=="COMPLETE"||!!s.uploaded||!!s.scrapped)return false;}
    else if(filter==="UPLOADED"){if(!s.uploaded||!!s.scrapped)return false;}
    else if(filter==="SCRAPPED"){if(!s.scrapped)return false;}
    else if(filter==="WORTHY"){if(!isActionableWorthy(s)||!withinWorthyAge(s))return false;}
    else if(status!==filter)return false;
  }
  if(!q)return true;
  return [s.topic,s.category,s.uploadedPlatform,s.uploadNote,s.scrapReason,...(s.publishers||[])].join(" ").toLowerCase().includes(q);
}
function renderStories(){
  const root=$("#stories"),ss=(state.stories||[]).filter(storyMatches);
  if(!ss.length){
    if(currentStoryFilter()==="WORTHY"&&worthyAgeHours>0)
      root.innerHTML='<div class="emptyState">No worthy stories were published within the last '+worthyAgeHours+' hour(s).</div>';
    else root.innerHTML='<div class="emptyState">No stories match this view.</div>';
    return;
  }
  root.innerHTML=ss.map(s=>storyCard(s)).join("");
}
function storyCard(s){
  const score=Math.round(num(s.score)*100),verified=!!s.verified,status=String(s.status||"DISCOVERED"),mix=s.sourceMix||{},total=Math.max(1,num(mix.left)+num(mix.center)+num(mix.right)+num(mix.unknown));
  const bar=k=>Math.round(num(mix[k])/total*100);
  const pubs=(s.publishers||[]).slice(0,8).map(p=>`<span class="sourceChip">${esc(p)}</span>`).join("");
  const cls=status==="PRODUCING"?" producing":status==="FAILED"?" failed":"";
  const makeDisabled=["QUEUED","PRODUCING","COMPLETE","COMPLETE_HISTORY"].includes(status);
  const completeLike=status.startsWith("COMPLETE");
  const engine=esc(s.ttsEngine||"pending");
  const voice=esc(s.ttsVoice||"");
  const visual=esc(s.visualMode||((s.stage==="VISUALS"&&s.detail)?s.detail:"pending"));
  const checkpoint=esc(s.comfyCheckpoint||"");
  const liveMessage=s.error||s.comfyStatus||s.detail||"";
  const liveDetail=liveMessage?`<div class="liveDetail">${esc(liveMessage)}</div>`:"";
  const sourceDetails=mix.publisherDetails||{};
  const baselineDetails=Object.entries(sourceDetails).map(([publisher,d])=>{
    const href=safeHttpUrl(d&&d.url);
    const label=(d&&d.originalClassification)||((d&&d.bucket)||"unknown");
    const confidence=d&&d.confidence?(" // "+d.confidence):"";
    const inner=`${esc(publisher)}: ${esc(label)}${esc(confidence)}`;
    return href?`<a class="biasSourceChip" href="${href}" target="_blank" rel="noopener">${inner}</a>`:`<span class="biasSourceChip">${inner}</span>`;
  }).join("");
  const framing=s.framingAnalysis||{};
  const framingStatus=String(s.biasAnalysisStatus||"");
  const framingWeights=framing.overallWeights||{};
  const framingBar=k=>Math.round(num(framingWeights[k])*100);
  const framingArticles=Array.isArray(framing.articles)?framing.articles:[];
  const framingRows=framingArticles.slice(0,6).map(a=>`<div class="framingRow"><span>${esc(a.publisher||"source")}</span><b class="frame-${esc(a.classification||"uncertain")}">${esc(String(a.classification||"uncertain").replace("_"," ").toUpperCase())}</b><small>${Math.round(num(a.confidence)*100)}%</small></div>`).join("");
  const analysisButton=(framingStatus==="QUEUED"||framingStatus==="ANALYZING")
    ?`<button class="btn ghost" disabled>${framingStatus==="ANALYZING"?"ANALYZING…":"ANALYSIS QUEUED"}</button>`
    :`<button class="btn ghost" onclick="analyzeBias('${esc(s.id)}')">${framingStatus==="COMPLETE"?"↻ REANALYZE FRAMING":"◎ ANALYZE FRAMING"}</button>`;
  const framingPanel=framingStatus==="COMPLETE"
    ?`<div class="framingPanel">
        <div class="mixTitle"><span>ARTICLE FRAMING // LOCAL OLLAMA</span><span>${esc(String(framing.overallClassification||"uncertain").replace("_"," ").toUpperCase())} // ${Math.round(num(framing.overallConfidence)*100)}%</span></div>
        <div class="triBias"><div class="tri left" style="--v:${framingBar("left")}%"><span>LEFT WEIGHT</span><b>${framingBar("left")}%</b></div><div class="tri center" style="--v:${framingBar("center")}%"><span>CENTER WEIGHT</span><b>${framingBar("center")}%</b></div><div class="tri right" style="--v:${framingBar("right")}%"><span>RIGHT WEIGHT</span><b>${framingBar("right")}%</b></div></div>
        <div class="framingSummary">${esc(framing.summary||"")}</div>
        ${framingRows}
        <div class="framingMeta">HEURISTIC WEIGHTS, NOT TRUTH PROBABILITIES // MIXED ${num(framing.mixed)} // UNCERTAIN ${num(framing.uncertain)} // NOT POLITICAL ${num(framing.notPolitical)} // MODEL ${esc(framing.model||"")}</div>
        ${analysisButton}
      </div>`
    :`<div class="framingPanel pending"><div class="mixTitle"><span>ARTICLE FRAMING // LOCAL OLLAMA</span><span>${esc(framingStatus||"NOT ANALYZED")}</span></div><div class="framingSummary">${framingStatus==="FAILED"?esc(s.biasAnalysisError||"Analysis failed."):s.politicalCandidate?"Queued automatically for idle-worker analysis.":"This story was not automatically flagged as political; you can still analyze it manually."}</div>${analysisButton}</div>`;
  const productionFacts=(status==="PRODUCING"||status==="COMPLETE"||status==="FAILED")
    ?`<div class="productionFacts">
        <span><b>TTS</b> ${engine}${voice?" // "+voice:""}</span>
        <span><b>VISUALS</b> ${visual}${checkpoint?" // "+checkpoint:""}</span>
        ${s.comfyImages!=null?`<span><b>COMFY</b> ${num(s.comfyImages)} generated image(s)</span>`:""}
        ${num(s.leaseRecoveries)>0?`<span><b>RECOVERED</b> ${num(s.leaseRecoveries)} worker lease(s)</span>`:""}
      </div>`:"";
  const uploadState=status==="COMPLETE"
    ?(s.scrapped
      ?`<div class="uploadState scrapped"><b>× SCRAPPED VERSION ${num(s.videoVersion)||""}</b><span>${s.scrappedAt?new Date(s.scrappedAt).toLocaleString():""}${s.scrapReason?" // "+esc(s.scrapReason):""}</span></div>`
      :s.uploaded
        ?`<div class="uploadState uploaded"><b>✓ MANUALLY UPLOADED // V${num(s.videoVersion)||1}</b><span>${esc(s.uploadedPlatform||"platform not noted")}${s.uploadedAt?" // "+new Date(s.uploadedAt).toLocaleString():""}${s.uploadNote?" // "+esc(s.uploadNote):""}</span></div>`
        :`<div class="uploadState pending"><b>⇧ READY TO POST // V${num(s.videoVersion)||1}</b><span>Not marked uploaded yet.</span></div>`)
    :"";
  return `<article class="storyCard${cls}">
    <div class="storyTop">
      <div class="scoreRing" style="--score:${score}"><div><b>${score}</b><small>WORTH</small></div></div>
      <div><div class="storyTitle">${esc(s.topic)}</div>
        <div class="storyMeta"><span class="statusTag ${verified||s.worthy?"verified":status==="FAILED"?"failed":""}">${esc(status)}</span>
        ${s.manualVerificationOverride?'<span class="statusTag failed">MANUAL VERIFY OVERRIDE</span>':""}
        ${s.softVerificationOverride?'<span class="statusTag">RELAXED 1-SOURCE</span>':""}
        ${s.scrapped?'<span class="statusTag scrapped">SCRAPPED</span>':s.uploaded?'<span class="statusTag uploaded">UPLOADED</span>':status==="COMPLETE"?'<span class="statusTag toPost">TO POST</span>':""}
        ${esc(s.category||"general").toUpperCase()} // ${num(s.independentSources)} INDEPENDENT // ${age(s.latestPublishedAt)} OLD</div>
      </div>
    </div>
    <div class="verifyReason">${verified?"✓ VERIFIED // ":s.worthy?"★ WORTHY // ":"⚠ "}${esc(s.verificationReason||"")}</div>
    <div class="sources">${pubs||'<span class="sourceChip">NO SOURCE LABELS</span>'}</div>
    <div class="progressWrap"><div class="progressText"><span>${esc(s.stage||status)}</span><span>${Math.round(num(s.progress))}%</span></div><div class="progress"><i style="width:${pct(s.progress)}%"></i></div></div>
    ${liveDetail}
    ${productionFacts}
    ${uploadState}
    <div class="mix" title="${esc(mix.note||"External publisher baseline classifications")}">
      <div class="mixTitle"><span>PUBLISHER BASELINE</span><span>${esc(mix.provider||"unconfigured")}${mix.asOf?" // "+esc(mix.asOf):""}</span></div>
      <div class="mixBars"><div class="mixBar left"><i style="width:${bar("left")}%"></i></div><div class="mixBar center"><i style="width:${bar("center")}%"></i></div><div class="mixBar right"><i style="width:${bar("right")}%"></i></div><div class="mixBar unknown"><i style="width:${bar("unknown")}%"></i></div></div>
      <div class="mixLabels"><span>LEFT ${num(mix.left)}</span><span>CENTER ${num(mix.center)}</span><span>RIGHT ${num(mix.right)}</span><span>UNKNOWN ${num(mix.unknown)}</span></div>
      <div class="biasSourceDetails">${baselineDetails||'<span class="biasSourceChip">NO ATTRIBUTED RATING</span>'}</div>
    </div>
    ${framingPanel}
    <div class="storyActions">
      ${!completeLike?`
        <button class="btn good" onclick="storyAction('${esc(s.id)}','WORTH')" ${makeDisabled?"disabled":""}>★ WORTH IT</button>
        <button class="btn primary" onclick="storyAction('${esc(s.id)}','MAKE')" ${makeDisabled?"disabled":""}>▶ MAKE VIDEO</button>
        <button class="btn warn" onclick="storyAction('${esc(s.id)}','HOLD')">Ⅱ HOLD</button>
        <button class="btn bad" onclick="storyAction('${esc(s.id)}','SKIP')">× NOT WORTH</button>
        <button class="btn ghost" onclick="storyAction('${esc(s.id)}','AUTO')">↻ AUTO</button>`:""}
      ${status==="COMPLETE"?`
        <button class="btn primary" onclick="remakeVideo('${esc(s.id)}')">↻ RE-MAKE VIDEO</button>
        ${s.scrapped
          ?`<button class="btn ghost" onclick="setScrapStatus('${esc(s.id)}','',false)">↩ RESTORE VIDEO</button>`
          :`<button class="btn bad" onclick="setScrapStatus('${esc(s.id)}','',true)">× SCRAP VIDEO</button>`}
        ${!s.scrapped?(s.uploaded
          ?`<button class="btn uploadedBtn" onclick="setUploadStatus('${esc(s.id)}','',false)">↩ UNMARK UPLOADED</button>`
          :`<button class="btn publish" onclick="setUploadStatus('${esc(s.id)}','',true)">⇧ MARK UPLOADED</button>`):""}`:""}
    </div>
  </article>`
}
function renderFeeds(){
  const root=$("#feeds"),fs=state.feeds||[];
  root.innerHTML=fs.map(f=>`<div class="feed ${String(f.status||"").toLowerCase()}"><i class="lamp"></i><div class="feedText"><div class="feedName">${esc(f.name)}</div><div class="feedMeta">${esc(f.category)} // ${esc(f.status||"UNKNOWN")} ${f.entries!=null?"// "+f.entries:""}</div></div></div>`).join("");
}
function renderVideos(){
  const root=$("#videos"),vs=state.videos||[];
  if(!vs.length){root.className="videoList emptyState";root.textContent="No completed videos yet.";return}
  root.className="videoList";
  root.innerHTML=vs.slice().sort((a,b)=>{
    const stateRank=v=>v.scrapped?2:v.uploaded?1:0;
    const stateOrder=stateRank(a)-stateRank(b);
    if(stateOrder!==0)return stateOrder;
    return Date.parse(b.completedAt||0)-Date.parse(a.completedAt||0);
  }).map(v=>{
    const storyId=v.storyId||v.jobId;
    const versionId=v.versionId||"";
    const href="/videos/"+encodeURIComponent(v.filename||"")+(token?"?token="+encodeURIComponent(token):"");
    const tech=[v.ttsEngine?("TTS "+v.ttsEngine+(v.ttsVoice?" / "+v.ttsVoice:"")):"",v.visualMode?("VISUALS "+v.visualMode):"",num(v.comfyImages)>0?("COMFY "+num(v.comfyImages)+" IMG"):""].filter(Boolean).join(" // ");
    const lifecycle=v.scrapped
      ?`<span class="uploadBadge scrapped">SCRAPPED${v.scrappedAt?" // "+new Date(v.scrappedAt).toLocaleDateString():""}</span>`
      :v.uploaded
        ?`<span class="uploadBadge uploaded">UPLOADED // ${esc(v.uploadedPlatform||"UNSPECIFIED")}${v.uploadedAt?" // "+new Date(v.uploadedAt).toLocaleDateString():""}</span>`
        :`<span class="uploadBadge pending">TO POST</span>`;
    const uploadButton=v.scrapped?"":v.uploaded
      ?`<button class="btn uploadedBtn" onclick="setUploadStatus('${esc(storyId)}','${esc(versionId)}',false)">UNMARK</button>`
      :`<button class="btn publish" onclick="setUploadStatus('${esc(storyId)}','${esc(versionId)}',true)">MARK UPLOADED</button>`;
    const scrapButton=v.scrapped
      ?`<button class="btn ghost" onclick="setScrapStatus('${esc(storyId)}','${esc(versionId)}',false)">RESTORE</button>`
      :`<button class="btn bad" onclick="setScrapStatus('${esc(storyId)}','${esc(versionId)}',true)">SCRAP</button>`;
    const remakeButton=v.current
      ?`<button class="btn primary" onclick="remakeVideo('${esc(storyId)}')">RE-MAKE</button>`
      :"";
    return `<div class="videoRow"><div class="videoInfo"><strong>${esc(v.topic||storyId)} // V${num(v.version)||1}${v.current?" // CURRENT":""}</strong><small>${esc(v.filename||"")} // ${v.completedAt?new Date(v.completedAt).toLocaleString():""}${tech?"<br>"+esc(tech):""}</small>${lifecycle}${v.scrapReason?`<small class="uploadNote">SCRAP: ${esc(v.scrapReason)}</small>`:""}${v.uploadNote?`<small class="uploadNote">${esc(v.uploadNote)}</small>`:""}</div><div class="videoActions"><a href="${href}" target="_blank">OPEN MP4</a>${remakeButton}${uploadButton}${scrapButton}</div></div>`
  }).join("");
}

async function storyAction(id,action){
  try{
    const result=await api("/api/stories/"+encodeURIComponent(id)+"/action",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({action})});
    if((action==="MAKE"||action==="WORTH")&&result&&result.manualVerificationOverride)toast(action==="WORTH"?"Marked WORTH IT and queued with a one-source override.":"Force-queued. Manual verification override recorded.");
    else if(action==="WORTH")toast("Marked WORTH IT and queued for production.");
    else if(action==="REMAKE")toast("Re-make queued. Previous video version kept in archive.");
    else toast(action==="MAKE"?"Queued for video production.":action+" saved.");
    await loadState();
  }catch(e){toast(e.message,true)}
}
window.storyAction=storyAction;
async function remakeVideo(id){
  if(!confirm("Queue a fresh re-make? The existing completed video will stay in the archive as its own version."))return;
  await storyAction(id,"REMAKE");
}
window.remakeVideo=remakeVideo;
async function setUploadStatus(id,versionId,uploaded){
  try{
    let platform="",note="";
    if(uploaded){
      const remembered=localStorage.getItem("autonewsUploadPlatform")||"TikTok";
      const p=prompt("Where did you manually upload this video?",remembered);
      if(p===null)return;
      platform=p.trim();
      if(platform)localStorage.setItem("autonewsUploadPlatform",platform);
      const n=prompt("Optional upload note (account, caption/version, etc.):","");
      if(n===null)return;
      note=n.trim();
    }else if(!confirm("Mark this video as NOT uploaded? The history entry will be kept."))return;

    await api("/api/stories/"+encodeURIComponent(id)+"/upload-status",{
      method:"POST",
      headers:{"Content-Type":"application/json"},
      body:JSON.stringify({uploaded,platform,note,versionId})
    });
    toast(uploaded?"Marked manually uploaded.":"Upload mark removed.");
    await loadState();
  }catch(e){toast(e.message,true)}
}
window.setUploadStatus=setUploadStatus;
async function setScrapStatus(id,versionId,scrapped){
  try{
    let reason="";
    if(scrapped){
      const r=prompt("Why are you scrapping this video? (optional)","");
      if(r===null)return;
      reason=r.trim();
      if(!confirm("Scrap this completed video version? It will stay in the archive but leave TO POST / UPLOADED."))return;
    }else if(!confirm("Restore this scrapped video version?"))return;
    await api("/api/stories/"+encodeURIComponent(id)+"/scrap-status",{
      method:"POST",
      headers:{"Content-Type":"application/json"},
      body:JSON.stringify({scrapped,reason,versionId})
    });
    toast(scrapped?"Video marked SCRAPPED.":"Video restored.");
    await loadState();
  }catch(e){toast(e.message,true)}
}
window.setScrapStatus=setScrapStatus;
async function analyzeBias(id){
  try{
    await api("/api/stories/"+encodeURIComponent(id)+"/analyze-bias",{method:"POST"});
    toast("Political framing analysis queued for an idle worker.");
    await loadState();
  }catch(e){toast(e.message,true)}
}
window.analyzeBias=analyzeBias;

$("#scanBtn").onclick=async()=>{try{await api("/api/scan",{method:"POST"});toast("Full RSS scan started.")}catch(e){toast(e.message,true)}};
$("#authBtn").onclick=authPrompt;
function authPrompt(){const v=prompt("Command Center API token (leave blank for localhost/no-token):",token);if(v===null)return;token=v.trim();localStorage.setItem("autonewsToken",token);connectEvents();loadState()}
$("#search").addEventListener("input",renderStories);
$("#statusFilter").addEventListener("change",()=>{
  activeFilter="ALL";
  document.querySelectorAll(".rail").forEach(x=>x.classList.remove("active"));
  syncWorthyAgeControl();renderRails();renderStories();
});
$("#worthyAgeFilter").value=String(worthyAgeHours);
$("#worthyAgeFilter").addEventListener("change",()=>{
  const next=Number($("#worthyAgeFilter").value||0);
  worthyAgeHours=WORTHY_AGE_OPTIONS.has(next)?next:0;
  localStorage.setItem("autonewsWorthyAgeHours",String(worthyAgeHours));
  syncWorthyAgeControl();renderRails();renderStories();
});
document.querySelectorAll(".rail").forEach(b=>b.onclick=()=>{
  activeFilter=b.dataset.filter;
  $("#statusFilter").value="ALL";
  document.querySelectorAll(".rail").forEach(x=>x.classList.toggle("active",x===b));
  syncWorthyAgeControl();renderRails();renderStories();
});

function connectEvents(){
  if(eventSource)eventSource.close();
  const u="/api/events"+(token?"?token="+encodeURIComponent(token):"");
  eventSource=new EventSource(u);
  eventSource.onopen=()=>{$("#connection").textContent="LIVE";$("#connection").className="pill ok"};
  eventSource.onmessage=e=>{try{const v=JSON.parse(e.data);logEvent(v);debouncedRefresh()}catch{}};
  eventSource.onerror=()=>{$("#connection").textContent="RECONNECTING";$("#connection").className="pill danger"};
}
function logEvent(e){
  const root=$("#eventLog"),time=new Date(e.timestamp||Date.now()).toLocaleTimeString(),type=e.type||"event",p=e.payload||{};
  const detail=p.topic||p.name||p.status||p.stage||p.filename||"update";
  root.insertAdjacentHTML("afterbegin",`<div class="eventLine"><b>${esc(time)} // ${esc(type).toUpperCase()}</b> ${esc(detail)}</div>`);
  while(root.children.length>60)root.lastElementChild.remove();
}
function debouncedRefresh(){clearTimeout(refreshTimer);refreshTimer=setTimeout(loadState,250)}
setInterval(()=>{$("#clock").textContent=new Date().toLocaleTimeString([],{hour12:false})},500);
setInterval(loadState,5000);

let sweep=0;
function drawRadar(){
  const canvas=$("#radarCanvas"),rect=canvas.getBoundingClientRect(),dpr=devicePixelRatio||1;
  if(canvas.width!==Math.floor(rect.width*dpr)||canvas.height!==Math.floor(rect.height*dpr)){canvas.width=Math.floor(rect.width*dpr);canvas.height=Math.floor(rect.height*dpr)}
  const ctx=canvas.getContext("2d");ctx.setTransform(dpr,0,0,dpr,0,0);const w=rect.width,h=rect.height,cx=w/2,cy=h/2+5,r=Math.min(w,h)*.42;
  ctx.clearRect(0,0,w,h);ctx.strokeStyle="rgba(56,232,255,.12)";ctx.lineWidth=1;
  for(let i=1;i<=4;i++){ctx.beginPath();ctx.arc(cx,cy,r*i/4,0,Math.PI*2);ctx.stroke()}
  for(let i=0;i<8;i++){const a=i*Math.PI/4;ctx.beginPath();ctx.moveTo(cx,cy);ctx.lineTo(cx+Math.cos(a)*r,cy+Math.sin(a)*r);ctx.stroke()}
  const a=sweep;const grad=ctx.createLinearGradient(cx,cy,cx+Math.cos(a)*r,cy+Math.sin(a)*r);grad.addColorStop(0,"rgba(56,232,255,0)");grad.addColorStop(1,"rgba(56,232,255,.75)");ctx.strokeStyle=grad;ctx.lineWidth=2;ctx.beginPath();ctx.moveTo(cx,cy);ctx.lineTo(cx+Math.cos(a)*r,cy+Math.sin(a)*r);ctx.stroke();
  (state.feeds||[]).forEach((f,i)=>{let hash=0;for(const ch of (f.url||f.name||""))hash=(hash*31+ch.charCodeAt(0))>>>0;const ang=(hash%6283)/1000,rr=r*(.2+((hash>>>8)%800)/1000*.78),x=cx+Math.cos(ang)*rr,y=cy+Math.sin(ang)*rr;ctx.fillStyle=f.status==="OK"?"#45ff9a":f.status==="FAILED"?"#ff5470":"#38e8ff";ctx.shadowColor=ctx.fillStyle;ctx.shadowBlur=7;ctx.beginPath();ctx.arc(x,y,2.4,0,Math.PI*2);ctx.fill();ctx.shadowBlur=0});
}
function animateRadar(){sweep=(sweep+.012)%(Math.PI*2);drawRadar();requestAnimationFrame(animateRadar)}
connectEvents();loadState();animateRadar();
