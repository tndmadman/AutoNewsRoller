const $=s=>document.querySelector(s);
let state={stories:[],feeds:[],workers:[],videos:[],counts:{}}, token="", activeFilter="ALL", eventSource=null, refreshTimer=null;
const BOARD_PAGE_SIZES=new Set([10,25,50,100]);
let boardPageSize=Number(localStorage.getItem("autonewsBoardPageSize")||25);
if(!BOARD_PAGE_SIZES.has(boardPageSize))boardPageSize=25;
let boardPage=1, boardData={stories:[],page:1,pageSize:boardPageSize,total:0,totalPages:1,facets:{}};
let boardGeneration=0, boardInFlight=false, boardSearchTimer=null, boardRenderDeferred=false;
let refreshInFlight=null, refreshAgain=false, stateEpoch=0, pointerTarget=null;
const pendingActions=new Map();
let liveSequence=0;
const liveOverrides=new Map();
document.addEventListener("pointerdown",e=>{pointerTarget=e.target},true);
document.addEventListener("pointerup",()=>setTimeout(()=>{pointerTarget=null},150),true);
document.addEventListener("pointercancel",()=>{pointerTarget=null},true);
const STORY_AGE_OPTIONS=new Set([0,1,2,3,4,6,8,12,24,48,72]);
const storedStoryAge=localStorage.getItem("autonewsStoryAgeHours");
const legacyWorthyAge=localStorage.getItem("autonewsWorthyAgeHours");
let storyAgeLimitHours=Number(storedStoryAge??legacyWorthyAge??0);
if(!STORY_AGE_OPTIONS.has(storyAgeLimitHours))storyAgeLimitHours=0;
if(storedStoryAge===null&&legacyWorthyAge!==null)localStorage.setItem("autonewsStoryAgeHours",String(storyAgeLimitHours));
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
const OPERATIONAL_FILTERS=new Set(["QUEUED","PRODUCING","HOLD"]);
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
function withinStoryAge(s){
  if(storyAgeLimitHours<=0)return true;
  const h=storyAgeHours(s);
  return h!==null&&h<=storyAgeLimitHours;
}
function syncStoryAgeControl(){
  const el=$("#storyAgeFilter");
  if(!el)return;
  el.value=String(storyAgeLimitHours);
  const operational=OPERATIONAL_FILTERS.has(currentStoryFilter());
  el.disabled=operational;
  el.classList.toggle("activeFilterControl",storyAgeLimitHours>0&&!operational);
  el.title=operational
    ?"Age filtering is ignored for active operational states so unresolved work cannot disappear."
    :storyAgeLimitHours>0
      ?"Showing stories published within the last "+storyAgeLimitHours+" hour(s)."
      :"Showing stories of any age.";
}
function safeHttpUrl(v){try{const u=new URL(String(v||""));return (u.protocol==="https:"||u.protocol==="http:")?u.href:""}catch{return ""}}
function feedEndpointLabel(url){
  try{
    const u=new URL(String(url||""));
    const parts=u.pathname.split("/").filter(Boolean);
    let label=parts.length?parts[parts.length-1]:u.hostname;
    label=label.replace(/\.(xml|rss|atom)$/i,"").replace(/[_-]+/g," ").trim();
    if(!label||/^(rss|feed|index|main|latest)$/i.test(label)){
      label=parts.length>1?parts[parts.length-2]:u.hostname.replace(/^www\./,"");
      label=String(label).replace(/[_-]+/g," ").trim();
    }
    return label||u.hostname.replace(/^www\./,"");
  }catch{return ""}
}
function feedCategoryClass(category){
  return "cat-"+String(category||"general").toLowerCase().replace(/[^a-z0-9]+/g,"-");
}
function toast(msg,error=false){const t=$("#toast");t.textContent=msg;t.className="toast show"+(error?" error":"");clearTimeout(t._timer);t._timer=setTimeout(()=>t.className="toast",3500)}

// One network refresh at a time. Mutations invalidate responses started before their acknowledgement.
async function loadState(){
  if(refreshInFlight){refreshAgain=true;return refreshInFlight}
  const epoch=stateEpoch, liveAtStart=liveSequence;
  refreshInFlight=(async()=>{
    try{
      const snapshot=await api("/api/state?includeStories=false");
      if(epoch!==stateEpoch){refreshAgain=true;return}
      // A worker/feed event may arrive while this GET is in flight.
      // Reapply just those newer entities so the older snapshot cannot undo them.
      for(const {sequence,event} of liveOverrides.values()){
        if(sequence>liveAtStart)mergeLiveEntity(snapshot,event);
      }
      state=snapshot;
      $("#connection").textContent="LIVE";$("#connection").className="pill ok";
      render();
    }catch(e){
      $("#connection").textContent=e.message==="AUTH_REQUIRED"?"LOCKED":"OFFLINE";
      $("#connection").className="pill danger";
      if(e.message==="AUTH_REQUIRED"&&!token)setTimeout(authPrompt,100);
      throw e;
    }
  })();
  try{return await refreshInFlight}
  finally{
    refreshInFlight=null;
    if(refreshAgain){refreshAgain=false;queueMicrotask(()=>loadState().catch(()=>{}))}
  }
}
// Keyed records keep their DOM identity. Subtree updates only change differing leaf nodes.
// Selection and pointer protection stop rendering from interrupting copying or clicks.
function interactionProtected(node){
  if(pointerTarget&&node.contains(pointerTarget))return true;
  const focused=document.activeElement;
  if(focused&&node.contains(focused)&&(focused.matches("input,textarea,select")||focused.isContentEditable))return true;
  const selection=window.getSelection();
  if(selection&&!selection.isCollapsed)for(let i=0;i<selection.rangeCount;i++){
    try{if(selection.getRangeAt(i).intersectsNode(node))return true}catch{}
  }
  return false;
}
function patchNode(oldNode,newNode){
  if(oldNode.nodeType!==newNode.nodeType || (oldNode.nodeType===1&&oldNode.tagName!==newNode.tagName)){
    oldNode.replaceWith(newNode.cloneNode(true));return;
  }
  if(oldNode.nodeType===3){
    if(oldNode.nodeValue!==newNode.nodeValue)oldNode.nodeValue=newNode.nodeValue;
    return;
  }
  if(oldNode.nodeType!==1)return;
  const expanded=oldNode.tagName==="DETAILS"&&oldNode.open;
  for(const a of [...oldNode.attributes])if(a.name!=="open"&&!newNode.hasAttribute(a.name))oldNode.removeAttribute(a.name);
  for(const a of [...newNode.attributes])if(a.name!=="open"&&oldNode.getAttribute(a.name)!==a.value)oldNode.setAttribute(a.name,a.value);
  if(oldNode.tagName==="DETAILS")oldNode.open=expanded;
  const children=[...newNode.childNodes];
  for(let i=0;i<children.length;i++){
    if(!oldNode.childNodes[i])oldNode.appendChild(children[i].cloneNode(true));
    else patchNode(oldNode.childNodes[i],children[i]);
  }
  while(oldNode.childNodes.length>children.length)oldNode.lastChild.remove();
}
function reconcileList(root,records,emptyText=""){
  // Return whether interaction protection deferred part of the requested update.
  // The next board poll must retry even if the server response is byte-for-byte
  // identical to the acknowledged, but not yet displayed, page.
  let deferred=false;
  const current=new Map([...root.children].filter(x=>x.dataset.recordKey!==undefined).map(x=>[x.dataset.recordKey,x]));
  if(!records.length){
    if([...root.children].some(interactionProtected))return true;
    if(root.textContent!==emptyText||root.children.length)root.textContent=emptyText;
    return false;
  }
  if(root.children.length===0 || (root.children.length===1&&!root.firstElementChild?.dataset.recordKey)){
    if(!interactionProtected(root))root.replaceChildren();
    else deferred=true;
  }
  const expectedKeys=new Set(records.map(r=>String(r.key)));
  // Remove obsolete, unprotected cards before adding replacements, so DOM
  // never temporarily grows beyond the requested page size.
  if(root.id==="stories")for(const [key,node] of current){
    if(!expectedKeys.has(key)&&!interactionProtected(node)){
      node.remove();current.delete(key);
    }
  }
  const wanted=new Set();
  records.forEach((r,i)=>{
    const key=String(r.key);
    if(wanted.has(key))return;
    wanted.add(key);
    const template=document.createElement("template");template.innerHTML=r.html.trim();
    const next=template.content.firstElementChild;if(!next)return;
    next.dataset.recordKey=key;
    let existing=current.get(key);
    if(!existing){
      // A protected obsolete story cannot be removed until pointerup or text
      // selection ends. Delay its replacement rather than exceed the page cap.
      if(root.id==="stories"&&root.children.length>=records.length){deferred=true;return;}
      existing=next;existing.dataset.recordKey=key;root.appendChild(existing);
    }
    else if(interactionProtected(existing))deferred=true;
    else patchNode(existing,next);
    if(root.children[i]!==existing){
      if(interactionProtected(existing)||interactionProtected(root))deferred=true;
      else root.insertBefore(existing,root.children[i]||null);
    }
  });
  for(const [key,node] of current)if(!wanted.has(key)){
    if(interactionProtected(node))deferred=true;
    else node.remove();
  }
  updatePendingControls();
  return deferred;
}
function updatePendingControls(){
  document.querySelectorAll("[data-command]").forEach(button=>{
    const row=button.closest("[data-story-id]");
    const blocked=row&&pendingActions.has(row.dataset.storyId);
    if(blocked){
      if(button.dataset.pendingOriginal===undefined){
        button.dataset.pendingOriginal=button.textContent;
        button.dataset.pendingWasDisabled=String(button.disabled);
      }
      button.disabled=true;
      if(button===pendingActions.get(row.dataset.storyId)?.button)button.textContent="WORKING…";
    }else if(button.dataset.pendingOriginal!==undefined){
      button.textContent=button.dataset.pendingOriginal;
      button.disabled=button.dataset.pendingWasDisabled==="true";
      delete button.dataset.pendingOriginal;
      delete button.dataset.pendingWasDisabled;
    }
  });
}
function render(){
  const c=state.counts||{};
  $("#feedsOnline").textContent=c.feedsOk||0;$("#feedsFailed").textContent=(c.feedsFailed||0)+" failed";
  $("#storiesTracked").textContent=c.stories||0;$("#verifiedCount").textContent=(c.worthy||0)+" worthy / "+(c.verified||0)+" verified";
  $("#queuedCount").textContent=c.queued||0;$("#producingCount").textContent=(c.producing||0)+" active // "+(c.failed||0)+" failed";
  $("#completeCount").textContent=c.videoVersions!=null?c.videoVersions:(c.complete||0);
  $("#completeSub").textContent=(c.toPost||0)+" to post // "+(c.uploaded||0)+" uploaded // "+(c.scrapped||0)+" scrapped";
  $("#workersOnline").textContent=c.workersOnline||0;
  $("#autoThreshold").textContent=Math.round(num(state.autoThreshold)*100)+"%";
  $("#autoQueueState").textContent=state.autoQueue
    ? "SOFT "+Math.round(num(state.softWorthThreshold)*100)+"% // 1-SRC AUTO "+Math.round(num(state.singleSourceAutoQueueThreshold)*100)+"%"
    : "MANUAL QUEUE";
  const autoToggle=$("#autoVideoToggle");
  if(autoToggle){
    autoToggle.classList.toggle("on",!!state.autoQueue);
    autoToggle.classList.toggle("off",!state.autoQueue);
    autoToggle.setAttribute("aria-pressed",state.autoQueue?"true":"false");
    $("#autoVideoToggleText").textContent=state.autoQueue?"AUTO VIDEO ON":"AUTO VIDEO OFF";
    autoToggle.title=state.autoQueue
      ?"Automatic video queueing is ON. Click to switch to manual-only video creation."
      :"Automatic video queueing is OFF. Manual MAKE VIDEO still works.";
  }
  $("#scanState").textContent=state.scanning?"SCANNING":"STANDBY";
  $("#feedSummary").textContent=(state.feeds||[]).length+" FEEDS";
  syncStoryAgeControl();renderWorkers();renderFeeds();renderFeedMatrix();renderVideos();drawRadar();
}
function renderRails(){
  const f=boardData.facets||{};
  const fields={railAll:"ALL",railFound:"DISCOVERED",railVerified:"WORTHY",railQueued:"QUEUED",
    railProducing:"PRODUCING",railFailed:"FAILED",railComplete:"COMPLETE",
    railToPost:"TO_POST",railUploaded:"UPLOADED",railScrapped:"SCRAPPED"};
  for(const [id,status] of Object.entries(fields))$("#"+id).textContent=f[status]||0;
  renderStatusDistribution();
}
// Render genuine instantaneous utilization, with N/A rather than a false zero.
function telemetryMeter(label,value){
  const available=value!==null&&value!==undefined&&Number.isFinite(Number(value));
  const percent=available?pct(value):0;
  return '<div class="telemetryMeter'+(available?'':' unavailable')+'">'+
    '<div class="meterReadout"><span>'+label+'</span><b>'+(available?Math.round(percent)+'%':'N/A')+'</b></div>'+
    '<div class="meterTrack" role="meter" aria-label="'+label+'" aria-valuemin="0" aria-valuemax="100"'+
    (available?' aria-valuenow="'+Math.round(percent)+'"':' aria-valuetext="Not available"')+'>'+
    '<i class="meterFill" style="width:'+percent+'%"></i></div></div>';
}
function renderWorkers(){
  const root=$("#workers"),ws=state.workers||[];
  root.className=ws.length?"workerGrid":"workerGrid emptyState";
  reconcileList(root,ws.map(w=>{
    const m=w.metrics||{},g=m.gpu||{},online=!!w.online;
    const memTot=num(m.memoryTotalMb),memFree=num(m.memoryFreeMb);
    const ram=online&&memTot>0&&m.memoryFreeMb!=null?(memTot-memFree)/memTot*100:null;
    const gpuMem=online&&g.available&&num(g.memoryTotalMb)>0&&g.memoryUsedMb!=null?
      num(g.memoryUsedMb)/num(g.memoryTotalMb)*100:null;
    const gAvailable=online&&g.available;
    const gpuInfo=gAvailable?
      esc(g.name||"GPU")+" / "+(g.memoryUsedMb!=null?Math.round(num(g.memoryUsedMb)):"?")+
      "/"+(g.memoryTotalMb!=null?Math.round(num(g.memoryTotalMb)):"?")+" MB VRAM / "+
      (g.temperatureC!=null?Math.round(num(g.temperatureC))+"°C":"TEMP N/A"):
      "GPU TELEMETRY N/A";
    return {key:String(w.id),html:`<div class="workerCard">
      <div class="workerHead"><div class="workerName">${esc(w.id)}</div><div class="workerState ${online?'':'offline'}">${online?'● LINKED':'○ OFFLINE'} / ${esc(w.state||'UNKNOWN')}</div></div>
      <div class="telemetry">
        ${telemetryMeter('CPU',online?m.cpuLoad:null)}
        ${telemetryMeter('RAM',ram)}
        ${telemetryMeter('GPU',gAvailable?g.utilization:null)}
        ${telemetryMeter('VRAM',gpuMem)}
      </div>
      <div class="workerJob">${gpuInfo}<div>${online?(w.currentTopic?'JOB: '+esc(w.currentTopic):'IDLE / WAITING FOR QUEUE'):'NODE OFFLINE / LAST REPORT NOT CURRENT'}</div></div>
    </div>`}
  }),"No workers connected.");
}
// These status facets are current snapshot counts, not history or rate.
function renderStatusDistribution(){
  const f=boardData.facets||{},root=$("#statusDistribution");
  if(!root)return;
  const keys=['DISCOVERED','VERIFIED','QUEUED','PRODUCING','HOLD','FAILED','COMPLETE','SKIPPED'];
  const buckets=keys.map(k=>({key:k,n:Math.max(0,Math.round(num(f[k])))}));
  const known=buckets.reduce((n,b)=>n+b.n,0);
  const other=Math.max(0,Math.round(num(f.ALL))-known);
  if(other)buckets.push({key:'OTHER',n:other});
  const sum=buckets.reduce((n,b)=>n+b.n,0);
  const html='<div class="distributionHeading"><b>STATUS ALLOCATION / CURRENT SNAPSHOT</b><span>'+
    sum.toLocaleString()+' CLASSIFIED / NO HISTORICAL RATE</span></div>'+
    '<div class="distributionTrack" role="img" aria-label="'+esc(buckets.map(b=>b.key+' '+b.n).join(', '))+'">'+
    buckets.map(b=>b.n?'<i class="distributionSegment '+b.key.toLowerCase()+'" style="width:'+(sum?b.n/sum*100:0)+'%"></i>':'').join('')+
    '</div><div class="distributionLabels">'+
    buckets.map(b=>'<span>'+esc(b.key)+' <b>'+b.n.toLocaleString()+'</b></span>').join('')+'</div>';
  if(root._html!==html){root.innerHTML=html;root._html=html;}
}
function renderFeedMatrix(){
  const root=$("#feedMatrix");if(!root)return;
  const feeds=state.feeds||[];
  const status=f=>String(f.status||'UNKNOWN').toUpperCase();
  const failed=feeds.filter(f=>['FAILED','ERROR'].includes(status(f))).length;
  const scanning=feeds.filter(f=>status(f)==='SCANNING').length;
  const online=feeds.filter(f=>status(f)==='OK').length;
  const cells=feeds.slice().sort((a,b)=>String(a.name||a.url).localeCompare(String(b.name||b.url))).map(f=>{
    const st=status(f),cls=st==='OK'?'ok':st==='SCANNING'?'scanning':['FAILED','ERROR'].includes(st)?'failed':'unknown';
    return '<i aria-hidden="true" class="matrixCell '+cls+'" title="'+esc(f.name||f.url)+' / '+esc(st)+'"></i>';
  }).join('');
  const html='<div class="matrixHeading"><b>FEED STATUS MATRIX / PRESENT SNAPSHOT</b><span>'+
    online+' OK / '+failed+' FAILED / '+scanning+' SCANNING / '+Math.max(0,feeds.length-online-failed-scanning)+' OTHER</span></div>'+
    '<div class="matrixCells" role="img" aria-label="'+feeds.length+' feeds: '+online+' online, '+failed+' failed, '+scanning+' scanning, '+Math.max(0,feeds.length-online-failed-scanning)+' other">'+cells+'</div>';
  if(root._html!==html){root.innerHTML=html;root._html=html;}
}

// Board requests are independent of dashboard polls. A later generation invalidates
// every older response, including those started before a successful mutation.
function boardQuery(){
  return new URLSearchParams({page:String(boardPage),pageSize:String(boardPageSize),
    status:currentStoryFilter(),search:$("#search").value.trim(),ageHours:String(storyAgeLimitHours)}).toString();
}
async function loadBoard(force=false){
  if(boardInFlight&&!force)return;
  const generation=++boardGeneration;
  boardInFlight=true;
  try{
    const next=await api("/api/board/stories?"+boardQuery());
    if(generation!==boardGeneration)return;
    if(!Array.isArray(next.stories))throw new Error("Invalid board response");
    const changed=JSON.stringify(next)!==JSON.stringify(boardData);
    boardData=next;
    boardPage=next.page;
    if(changed||boardRenderDeferred)renderStories();else renderPagination();
    renderRails();
  }catch(e){
    if(generation!==boardGeneration)return;
    toast(e.message==="AUTH_REQUIRED"?"Board authentication required.":e.message,true);
  }finally{
    if(generation===boardGeneration)boardInFlight=false;
  }
}
function selectBoardPage(page){
  const next=Math.min(Math.max(1,page),boardData.totalPages||1);
  if(next===boardPage)return;
  boardPage=next;
  // Explicit navigation removes prior-page cards, even protected selections.
  $("#stories").replaceChildren();
  loadBoard(true);
}
function boardFilterChanged(){
  boardPage=1;boardGeneration++;
  $("#stories").replaceChildren();
  loadBoard(true);
}
function pageButtons(){
  const total=Math.max(1,Number(boardData.totalPages)||1),current=boardPage;
  const pages=new Set([1,total]);
  for(let n=current-2;n<=current+2;n++)if(n>=1&&n<=total)pages.add(n);
  const ordered=[...pages].sort((x,y)=>x-y),out=[];
  for(let n=0;n<ordered.length;n++){
    const page=ordered[n],active=page===current;
    if(n&&page>ordered[n-1]+1)out.push('<span class="boardEllipsis" aria-hidden="true">…</span>');
    out.push('<button type="button" data-board-page="'+page+'" aria-label="Page '+page+'" '+
      (active?'aria-current="page" ':'')+'class="boardPageButton'+(active?' current':'')+'">'+page+'</button>');
  }
  return out.join("");
}
function renderPagination(){
  const total=Number(boardData.total)||0,pages=Math.max(1,Number(boardData.totalPages)||1);
  const first=total?(boardPage-1)*boardPageSize+1:0,last=Math.min(total,boardPage*boardPageSize);
  $("#boardResultCount").textContent="Showing "+first.toLocaleString()+"–"+last.toLocaleString()+" of "+total.toLocaleString()+" stories";
  $("#boardPageSize").value=String(boardPageSize);
  const controls='<button type="button" data-board-page="'+(boardPage-1)+'" aria-label="Previous page" '+(boardPage<=1?'disabled':'')+'>‹ PREV</button>'+
    '<div class="boardNumbers" role="group" aria-label="Story board pages">'+pageButtons()+'</div>'+
    '<button type="button" data-board-page="'+(boardPage+1)+'" aria-label="Next page" '+(boardPage>=pages?'disabled':'')+'>NEXT ›</button>';
  for(const root of [$("#boardTopPager"),$("#boardBottomPager")]){
    // Never replace the button currently receiving a pointerdown/click.
    // A stable pager should not rebuild its DOM at each five-second poll.
    if(root._boardMarkup!==controls&&!interactionProtected(root)){
      root.innerHTML=controls;
      root._boardMarkup=controls;
    }
  }
}
function renderStories(){
  const root=$("#stories"),ss=boardData.stories||[];
  const empty=storyAgeLimitHours>0
    ?"No stories in this view were published within the last "+storyAgeLimitHours+" hour(s)."
    :"No stories match this view.";
  boardRenderDeferred=reconcileList(root,ss.map(s=>({key:String(s.id),html:storyCard(s)})),empty);
  renderPagination();
}
function failurePanelHtml(s,status){
  if(status!=="FAILED")return "";
  const attempts=Array.isArray(s.productionAttempts)?s.productionAttempts:[];
  const recent=attempts.slice(-5).reverse().map(a=>{
    const when=a.at?new Date(a.at).toLocaleString():"";
    const detail=[a.stage||"UNKNOWN",a.worker||"unknown"].join(" // ");
    const err=a.error?(" // "+esc(a.error)):"";
    return '<div class="attemptRow"><b>'+esc(a.event||"EVENT")+'</b><span>#'+(num(a.attempt)||"?")+' // '+esc(detail)+'</span><small>'+esc(when)+err+'</small></div>';
  }).join("");
  const history=recent?'<details class="attemptHistory"><summary>SHOW RECENT ATTEMPTS ('+attempts.length+')</summary>'+recent+'</details>':"";
  return '<div class="failurePanel">'+
    '<div class="failureHead"><b>PRODUCTION FAILED</b><span>'+esc(s.failureType||"PRODUCTION_FAILURE")+'</span></div>'+
    '<div class="failureStats">'+
      '<span><b>CLAIMS</b> '+num(s.claimCount)+'</span>'+
      '<span><b>FAILURES</b> '+num(s.failureCount)+'</span>'+
      '<span><b>LEASE RECOVERIES</b> '+num(s.leaseRecoveries)+'</span>'+
      '<span><b>LAST WORKER</b> '+esc(s.lastAssignedWorker||"unknown")+'</span>'+
    '</div>'+
    '<div class="failureReason">'+esc(s.lastFailure||s.error||s.detail||"Automatic retry or lease-recovery guard was exhausted.")+'</div>'+
    history+
  '</div>';
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
  const failurePanel=failurePanelHtml(s,status);
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
    :`<button class="btn ghost" data-command="ANALYZE">${framingStatus==="COMPLETE"?"↻ REANALYZE FRAMING":"◎ ANALYZE FRAMING"}</button>`;
  const framingPanel=framingStatus==="COMPLETE"
    ?`<div class="framingPanel">
        <div class="mixTitle"><span>STORY FRAMING // LOCAL OLLAMA</span><span>${esc(String(framing.overallClassification||"uncertain").replace("_"," ").toUpperCase())} // ${Math.round(num(framing.overallConfidence)*100)}%</span></div>
        <div class="triBias"><div class="tri left" style="--v:${framingBar("left")}%"><span>LEFT WEIGHT</span><b>${framingBar("left")}%</b></div><div class="tri center" style="--v:${framingBar("center")}%"><span>CENTER WEIGHT</span><b>${framingBar("center")}%</b></div><div class="tri right" style="--v:${framingBar("right")}%"><span>RIGHT WEIGHT</span><b>${framingBar("right")}%</b></div></div>
        <div class="framingSummary">${esc(framing.summary||"")}</div>
        ${framingRows}
        <div class="framingMeta">AUTOMATED FRAMING ANALYSIS // HEURISTIC WEIGHTS, NOT TRUTH PROBABILITIES // MIXED ${num(framing.mixed)} // UNCERTAIN ${num(framing.uncertain)} // NOT POLITICAL ${num(framing.notPolitical)} // MODEL ${esc(framing.model||"")}</div>
        ${analysisButton}
      </div>`
    :`<div class="framingPanel pending"><div class="mixTitle"><span>STORY FRAMING // LOCAL OLLAMA</span><span>${esc(framingStatus||"NOT ANALYZED")}</span></div><div class="framingSummary">${framingStatus==="FAILED"?esc(s.biasAnalysisError||"Analysis failed."):s.politicalCandidate?"Queued automatically for idle-worker analysis.":"This story was not automatically flagged as political; you can still analyze it manually."}</div>${analysisButton}</div>`;
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
  return `<article class="storyCard${cls}" data-story-id="${esc(s.id)}" data-current-version-id="${esc(s.currentVideoVersionId||"")}">
    <div class="storyTop">
      <div class="scoreRing" role="meter" aria-label="Worth score, not truth probability" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${score}"><div><b>${score}</b><small>WORTH</small></div><span class="scoreTrack"><i style="width:${pct(score)}%"></i></span></div>
      <div><div class="storyTitle">${esc(s.topic)}</div>
        <div class="storyMeta"><span class="statusTag ${verified||s.worthy?"verified":status==="FAILED"?"failed":""}">${esc(status)}</span>
        ${s.manualVerificationOverride?'<span class="statusTag failed">MANUAL VERIFY OVERRIDE</span>':""}
        ${s.softVerificationOverride?'<span class="statusTag">RELAXED 1-SOURCE</span>':""}
        ${s.productionMode==="FRAMING_EXPOSURE"?'<span class="statusTag">FRAMING EXPOSURE</span>':""}
        ${s.pairedVideoState?'<span class="statusTag">PAIR: '+esc(s.pairedVideoState.replaceAll("_"," "))+"</span>":""}
        ${s.scrapped?'<span class="statusTag scrapped">SCRAPPED</span>':s.uploaded?'<span class="statusTag uploaded">UPLOADED</span>':status==="COMPLETE"?'<span class="statusTag toPost">TO POST</span>':""}
        ${esc(s.category||"general").toUpperCase()} // ${num(s.independentSources)} INDEPENDENT // ${age(s.latestPublishedAt)} OLD</div>
      </div>
    </div>
    <div class="progressWrap"><div class="progressText"><span>${esc(s.stage||status)}</span><span>${Math.round(num(s.progress))}%</span></div><div class="progress"><i style="width:${pct(s.progress)}%"></i></div></div>
    ${failurePanel}
    ${liveDetail}
    ${uploadState}
    <div class="storyActions">
      ${!completeLike?`
        <button class="btn good" data-command="WORTH" title="Select for normal news followed by a framing review" ${makeDisabled?"disabled":""}>★ WORTH IT</button>
        <button class="btn primary" data-command="MAKE" title="Make normal news video, then queue a separate political-framing review" ${makeDisabled?"disabled":""}>${status==="FAILED"?"↻ RETRY VIDEO":"▶ MAKE VIDEO"}</button>
        <button class="btn warn" data-command="HOLD">Ⅱ HOLD</button>
        <button class="btn bad" data-command="SKIP">× NOT WORTH</button>
        <button class="btn ghost" data-command="AUTO">↻ AUTO</button>`:""}
      <button class="btn ghost" data-command="MAKE_FRAMING_EXPOSURE" ${["QUEUED","PRODUCING"].includes(status)?"disabled title='Another video is already queued or producing'":""}>◉ EXPOSE POLITICAL FRAMING</button>
      ${status==="COMPLETE"?`
        <button class="btn primary" data-command="REMAKE" title="Remake the normal report, then queue another framing review">↻ RE-MAKE VIDEO</button>
        ${s.scrapped
          ?`<button class="btn ghost" data-command="RESTORE">↩ RESTORE VIDEO</button>`
          :`<button class="btn bad" data-command="SCRAP">× SCRAP VIDEO</button>`}
        ${!s.scrapped?(s.uploaded
          ?`<button class="btn uploadedBtn" data-command="UNUPLOAD">↩ UNMARK UPLOADED</button>`
          :`<button class="btn publish" data-command="UPLOAD">⇧ MARK UPLOADED</button>`):""}`:""}
    </div>
    <details class="storyDetails"><summary>INTELLIGENCE / SOURCES / FRAMING / PRODUCTION DETAILS</summary><div class="storyIntel">
    <div class="verifyReason">${verified?"✓ VERIFIED // ":s.worthy?"★ WORTHY // ":"⚠ "}${esc(s.verificationReason||"")}</div>
    <div class="sources">${pubs||'<span class="sourceChip">NO SOURCE LABELS</span>'}</div>
      ${productionFacts}
    <div class="mix" title="${esc(mix.note||"External publisher baseline classifications")}">
      <div class="mixTitle"><span>PUBLISHER BASELINE</span><span>${esc(mix.provider||"unconfigured")}${mix.asOf?" // "+esc(mix.asOf):""}</span></div>
      <div class="mixBars"><div class="mixBar left"><i style="width:${bar("left")}%"></i></div><div class="mixBar center"><i style="width:${bar("center")}%"></i></div><div class="mixBar right"><i style="width:${bar("right")}%"></i></div><div class="mixBar unknown"><i style="width:${bar("unknown")}%"></i></div></div>
      <div class="mixLabels"><span>LEFT ${num(mix.left)}</span><span>CENTER ${num(mix.center)}</span><span>RIGHT ${num(mix.right)}</span><span>UNKNOWN ${num(mix.unknown)}</span></div>
      <div class="biasSourceDetails">${baselineDetails||'<span class="biasSourceChip">NO ATTRIBUTED RATING</span>'}</div>
    </div>
    ${framingPanel}
    </div></details>
  </article>`
}
function renderFeeds(){
  const root=$("#feeds"),fs=state.feeds||[];
  reconcileList(root,fs.map(f=>{
    const category=String(f.category||"general").toUpperCase();
    const endpoint=feedEndpointLabel(f.url);
    const title=[f.name,category,f.url].filter(Boolean).join(" // ");
    return {key:String(f.url||f.name),html:`<div class="feed ${String(f.status||"").toLowerCase()} ${feedCategoryClass(f.category)}" title="${esc(title)}"><i class="lamp"></i><div class="feedText"><div class="feedName"><span class="feedPublisher">${esc(f.name)}</span><span class="feedCategory">${esc(category)}</span></div><div class="feedMeta"><span>${esc(f.status||"UNKNOWN")}${f.entries!=null?" // "+f.entries:""}</span>${endpoint?`<span class="feedEndpoint">${esc(endpoint)}</span>`:""}</div></div></div>`};
  })) ;
}
function renderVideos(){
  const root=$("#videos"),vs=state.videos||[];
  root.className=vs.length?"videoList":"videoList emptyState";
  reconcileList(root,vs.slice().sort((a,b)=>{
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
      ?`<button class="btn uploadedBtn" data-command="UNUPLOAD">UNMARK</button>`
      :`<button class="btn publish" data-command="UPLOAD">MARK UPLOADED</button>`;
    const scrapButton=v.scrapped
      ?`<button class="btn ghost" data-command="RESTORE">RESTORE</button>`
      :`<button class="btn bad" data-command="SCRAP">SCRAP</button>`;
    const remakeButton=v.current
      ?`<button class="btn primary" data-command="REMAKE">RE-MAKE</button>`
      :"";
    return {key:String(versionId||storyId+"#v"+(v.version||1)),html:`<div class="videoRow" data-story-id="${esc(storyId)}" data-version-id="${esc(versionId)}"><div class="videoInfo"><strong>${esc(v.topic||storyId)} // V${num(v.version)||1}${v.current?" // CURRENT":""} // ${esc(v.productionMode||"STANDARD")}</strong><small>${esc(v.filename||"")} // ${v.completedAt?new Date(v.completedAt).toLocaleString():""}${tech?"<br>"+esc(tech):""}</small>${lifecycle}${v.scrapReason?`<small class="uploadNote">SCRAP: ${esc(v.scrapReason)}</small>`:""}${v.uploadNote?`<small class="uploadNote">${esc(v.uploadNote)}</small>`:""}</div><div class="videoActions"><a href="${href}" target="_blank">OPEN MP4</a>${remakeButton}${uploadButton}${scrapButton}</div></div>`}
  }),"No completed videos yet.");
}

// All story and version mutations share one visible operation lifecycle.
function requestId(){return typeof crypto!=="undefined"&&crypto.randomUUID?crypto.randomUUID():String(Date.now())+"-"+Math.random().toString(36).slice(2)}
async function submitOperation(id,button,path,body,verb,success){
  if(pendingActions.has(id))return;
  const entry={button};pendingActions.set(id,entry);updatePendingControls();
  const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),20000);
  try{
    const result=await api(path,{method:verb||"POST",headers:{"Content-Type":"application/json"},body:body?JSON.stringify({...body,operationId:requestId()}):undefined,signal:controller.signal});
    stateEpoch++;
    if(result&&result.id){
      const i=(boardData.stories||[]).findIndex(s=>s.id===result.id);
      if(i!==-1){boardData.stories[i]={...boardData.stories[i],...result};renderStories();}
    }
    toast(success);
    loadState().catch(()=>toast("Action saved, but dashboard refresh failed. Reconnect to verify.",true));
    loadBoard(true);
  }catch(e){
    const uncertain=e.name==="AbortError"||e.name==="TypeError";
    toast(uncertain?"Connection interrupted; action may have completed. Refreshing status.":e.message==="AUTH_REQUIRED"?"Authentication required. Enter your API token using KEY.":e.message,true);
    loadState().catch(()=>{});
    loadBoard(true);
  }finally{
    clearTimeout(timeout);pendingActions.delete(id);updatePendingControls();
    // A click may have kept its card protected during the acknowledgement.
    // Give pointerup protection time to expire, then retry once immediately.
    if(boardRenderDeferred){
      const generation=boardGeneration;
      setTimeout(()=>{if(boardRenderDeferred&&generation===boardGeneration)renderStories()},220);
    }
  }
}
document.addEventListener("click",e=>{
  const button=e.target.closest("button[data-command]");
  if(!button||button.disabled)return;
  const row=button.closest("[data-story-id]");
  if(!row)return;
  const id=row.dataset.storyId,command=button.dataset.command;
  if(pendingActions.has(id))return;
  const versionId=row.dataset.versionId||row.dataset.currentVersionId||"";
  const path="/api/stories/"+encodeURIComponent(id);
  if(command==="REMAKE"&&!confirm("Queue a fresh re-make? The existing completed video will stay in the archive as its own version."))return;
  if(command==="MAKE_FRAMING_EXPOSURE"&&!confirm("Analyze the political framing within this article? A second news outlet is not required. Completed versions remain archived."))return;
  if(command==="ANALYZE"){
    submitOperation(id,button,path+"/analyze-bias",{},"POST","Political framing analysis queued for an idle worker.");
    return;
  }
  if(["UPLOAD","UNUPLOAD"].includes(command)){
    const uploaded=command==="UPLOAD";
    let platform="",note="";
    if(uploaded){
      const p=prompt("Where did you manually upload this video?",localStorage.getItem("autonewsUploadPlatform")||"TikTok");
      if(p===null)return;platform=p.trim();
      const n=prompt("Optional upload note (account, caption/version, etc.):","");
      if(n===null)return;note=n.trim();
      if(platform)localStorage.setItem("autonewsUploadPlatform",platform);
    }else if(!confirm("Mark this video as NOT uploaded? The history entry will be kept."))return;
    if(!versionId){toast("Video version not identified; reload before changing upload status.",true);return}
    submitOperation(id,button,path+"/upload-status",{uploaded,platform,note,versionId},"POST",uploaded?"Marked manually uploaded.":"Upload mark removed.");
    return;
  }
  if(["SCRAP","RESTORE"].includes(command)){
    const scrapped=command==="SCRAP";
    let reason="";
    if(scrapped){
      const r=prompt("Why are you scrapping this video? (optional)","");
      if(r===null)return;reason=r.trim();
      if(!confirm("Scrap this completed video version? It remains in the archive."))return;
    }else if(!confirm("Restore this scrapped video version?"))return;
    if(!versionId){toast("Video version not identified; reload before changing scrap status.",true);return}
    submitOperation(id,button,path+"/scrap-status",{scrapped,reason,versionId},"POST",scrapped?"Video marked SCRAPPED.":"Video restored.");
    return;
  }
  submitOperation(id,button,path+"/action",{action:command},"POST",
    command==="MAKE_FRAMING_EXPOSURE"?"Standalone framing review queued.":command==="MAKE"?"Queued original video, then framing review.":command==="WORTH"?"Queued original video, then framing review.":command==="REMAKE"?"Remake queued, followed by a framing review.":command+" saved.");
});
// Toolbar actions also acknowledge the initial click and lock while the request runs.
$("#autoVideoToggle").onclick=async()=>{
  const btn=$("#autoVideoToggle");
  if(btn.disabled)return;
  const enabled=!state.autoQueue;
  btn.disabled=true;
  const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),20000);
  try{
    const result=await api("/api/settings/auto-video",{
      method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({enabled}),signal:controller.signal
    });
    stateEpoch++;state.autoQueue=!!result.autoQueue;render();
    toast(enabled?"Automatic video creation enabled.":"Automatic video creation disabled. Manual MAKE VIDEO remains available.");
    loadState().catch(()=>toast("Setting saved but status refresh failed.",true));
  }catch(e){toast(e.name==="AbortError"?"Request timed out; verify toggle status.":e.message,true);loadState().catch(()=>{})}
  finally{clearTimeout(timeout);btn.disabled=false}
};
$("#scanBtn").onclick=async()=>{
  const btn=$("#scanBtn");
  if(btn.disabled)return;
  btn.disabled=true;
  const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),20000);
  try{
    const result=await api("/api/scan",{method:"POST",signal:controller.signal});
    toast(result.status==="already-scanning"?"RSS scan is already running.":"Full RSS scan started.");
    loadState().catch(()=>{});
  }catch(e){toast(e.name==="AbortError"?"Scan request timed out; check the scan status.":e.message,true)}
  finally{clearTimeout(timeout);btn.disabled=false}
};
$("#authBtn").onclick=authPrompt;
function authPrompt(){const v=prompt("Command Center API token (leave blank for localhost/no-token):",token);if(v===null)return;token=v.trim();localStorage.setItem("autonewsToken",token);connectEvents();loadState();loadBoard(true)}
$("#search").addEventListener("input",()=>{
  boardGeneration++; // Ignore older queries while debounce is pending.
  clearTimeout(boardSearchTimer);
  boardSearchTimer=setTimeout(()=>boardFilterChanged(),250);
});
$("#statusFilter").addEventListener("change",()=>{
  activeFilter="ALL";
  document.querySelectorAll(".rail").forEach(x=>x.classList.remove("active"));
  syncStoryAgeControl();boardFilterChanged();
});
$("#storyAgeFilter").value=String(storyAgeLimitHours);
$("#storyAgeFilter").addEventListener("change",()=>{
  const next=Number($("#storyAgeFilter").value||0);
  storyAgeLimitHours=STORY_AGE_OPTIONS.has(next)?next:0;
  localStorage.setItem("autonewsStoryAgeHours",String(storyAgeLimitHours));
  syncStoryAgeControl();boardFilterChanged();
});
$("#boardPageSize").value=String(boardPageSize);
$("#boardPageSize").addEventListener("change",()=>{
  const selected=Number($("#boardPageSize").value);
  boardPageSize=BOARD_PAGE_SIZES.has(selected)?selected:25;
  localStorage.setItem("autonewsBoardPageSize",String(boardPageSize));
  boardFilterChanged();
});
document.addEventListener("click",e=>{
  const button=e.target.closest("button[data-board-page]");
  if(button&&!button.disabled)selectBoardPage(Number(button.dataset.boardPage));
});
document.querySelectorAll(".rail").forEach(b=>b.onclick=()=>{
  activeFilter=b.dataset.filter;
  $("#statusFilter").value="ALL";
  document.querySelectorAll(".rail").forEach(x=>x.classList.toggle("active",x===b));
  syncStoryAgeControl();boardFilterChanged();
});

// Worker and feed SSE payloads are full entity updates. Render only their keyed
// rows; retain the periodic snapshot to reconcile offline status and missed events.
function mergeLiveEntity(target,event){
  const p=event.payload||{};
  if(event.type==="worker"&&p.id){
    const ws=target.workers||[];
    const idx=ws.findIndex(w=>w.id===p.id);
    const value={...(idx>=0?ws[idx]:{}),...p,online:true};
    if(idx<0)ws.push(value);else ws[idx]=value;
    target.workers=ws;
    target.counts=target.counts||{};
    target.counts.workersOnline=ws.filter(x=>x.online).length;
    return true;
  }
  if(event.type==="feed"&&p.url){
    const feeds=target.feeds||[];
    const idx=feeds.findIndex(f=>f.url===p.url);
    const value={...(idx>=0?feeds[idx]:{}),...p};
    if(idx<0)feeds.push(value);else feeds[idx]=value;
    target.feeds=feeds;
    target.counts=target.counts||{};
    target.counts.feedsOk=feeds.filter(x=>x.status==="OK").length;
    target.counts.feedsFailed=feeds.filter(x=>x.status==="FAILED").length;
    return true;
  }
  return false;
}
function applyLiveEvent(event){
  if(!mergeLiveEntity(state,event))return false;
  const p=event.payload;
  const key=event.type+":"+(event.type==="worker"?p.id:p.url);
  liveOverrides.set(key,{sequence:++liveSequence,event});
  if(event.type==="worker"){
    $("#workersOnline").textContent=state.counts.workersOnline;
    renderWorkers();
  }else{
    $("#feedsOnline").textContent=state.counts.feedsOk;
    $("#feedsFailed").textContent=state.counts.feedsFailed+" failed";
    $("#feedSummary").textContent=state.feeds.length+" FEEDS";
    renderFeeds();renderFeedMatrix();drawRadar();
  }
  return true;
}
function connectEvents(){
  if(eventSource)eventSource.close();
  const u="/api/events"+(token?"?token="+encodeURIComponent(token):"");
  eventSource=new EventSource(u);
  eventSource.onopen=()=>{$("#connection").textContent="LIVE";$("#connection").className="pill ok"};
  eventSource.onmessage=e=>{
    try{
      const event=JSON.parse(e.data);
      logEvent(event);
      if(event.type!=="connected"&&!applyLiveEvent(event))debouncedRefresh();
    }catch{}
  };
  eventSource.onerror=()=>{$("#connection").textContent="RECONNECTING";$("#connection").className="pill danger"};
}
function logEvent(e){
  const root=$("#eventLog"),time=new Date(e.timestamp||Date.now()).toLocaleTimeString(),type=e.type||"event",p=e.payload||{};
  const detail=p.topic||p.name||p.status||p.stage||p.filename||"update";
  root.insertAdjacentHTML("afterbegin",`<div class="eventLine"><b>${esc(time)} // ${esc(type).toUpperCase()}</b> ${esc(detail)}</div>`);
  while(root.children.length>60)root.lastElementChild.remove();
}
function debouncedRefresh(){if(refreshTimer)return;refreshTimer=setTimeout(()=>{refreshTimer=null;loadState().catch(()=>{});loadBoard()},250)}
setInterval(()=>{$("#clock").textContent=new Date().toLocaleTimeString([],{hour12:false})},500);
setInterval(()=>{loadState().catch(()=>{});loadBoard()},5000);

// This plot visualizes current source statuses. Coordinates are stable hashes, not geography.
function drawRadar(){
  const canvas=$("#radarCanvas");if(!canvas)return;
  const rect=canvas.getBoundingClientRect(),dpr=Math.min(window.devicePixelRatio||1,3);
  const w=rect.width,h=rect.height;if(w<=0||h<=0)return;
  const cw=Math.max(1,Math.round(w*dpr)),ch=Math.max(1,Math.round(h*dpr));
  if(canvas.width!==cw||canvas.height!==ch){canvas.width=cw;canvas.height=ch;}
  const ctx=canvas.getContext("2d");if(!ctx)return;
  ctx.setTransform(dpr,0,0,dpr,0,0);
  ctx.clearRect(0,0,w,h);ctx.fillStyle="#080f09";ctx.fillRect(0,0,w,h);
  const pad=18,left=pad,right=w-pad,top=pad,bottom=h-pad;
  ctx.lineWidth=1;ctx.strokeStyle="#273b2a";
  for(let x=left;x<=right;x+=24){ctx.beginPath();ctx.moveTo(x,top);ctx.lineTo(x,bottom);ctx.stroke();}
  for(let y=top;y<=bottom;y+=24){ctx.beginPath();ctx.moveTo(left,y);ctx.lineTo(right,y);ctx.stroke();}
  ctx.strokeStyle="#486549";ctx.strokeRect(left,top,right-left,bottom-top);
  ctx.fillStyle="#87a184";ctx.font='10px Consolas, monospace';
  ctx.fillText('CH01 / SIGNALS',left+6,top+12);
  ctx.fillText('ID-HASH / NOT GEO',left+6,bottom-5);
  for(const f of (state.feeds||[])){
    let hash=2166136261;
    for(const ch of String(f.url||f.name||''))hash=Math.imul(hash^ch.charCodeAt(0),16777619)>>>0;
    const hx=(hash%997)/997,hy=((hash>>>12)%991)/991;
    const x=left+11+hx*Math.max(0,right-left-22);
    const y=top+17+hy*Math.max(0,bottom-top-34);
    const st=String(f.status||'UNKNOWN').toUpperCase();
    ctx.fillStyle=st==='OK'?'#9ef27c':st==='SCANNING'?'#f0c36c':['FAILED','ERROR'].includes(st)?'#e47a6b':'#9ba99b';
    ctx.fillRect(Math.round(x)-2,Math.round(y)-2,4,4);
  }
}
window.addEventListener('resize',drawRadar,{passive:true});
if(typeof ResizeObserver!=='undefined')new ResizeObserver(drawRadar).observe($("#radarCanvas"));
connectEvents();loadState().catch(()=>{});loadBoard(true);drawRadar();
