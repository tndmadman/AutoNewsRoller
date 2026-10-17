"use strict";
// A terminal UI for real worker JSON; never parses or injects production text as HTML.
const $=id=>document.getElementById(id);
const state={worker:null,metrics:null,settings:null,pausedLogs:false,autoScroll:true,logEntries:[],
  logKeys:new Set(),clearBefore:0,busy:false,lastHistory:"",lastDiagnostics:"",pollMs:5000,
  stageEvents:[],activeJob:null,seq:0,lastApplied:0};
const fmt=value=>value===null||value===undefined||value===""?"UNAVAILABLE":String(value);
const stamp=value=>{if(!value)return "—";const d=new Date(value);return Number.isFinite(d.getTime())?d.toLocaleString():String(value)};
const duration=seconds=>{if(!Number.isFinite(seconds))return "—";let s=Math.max(0,Math.floor(seconds));return Math.floor(s/3600)+"h "+String(Math.floor(s%3600/60)).padStart(2,"0")+"m "+String(s%60).padStart(2,"0")+"s"};
function val(id,text){const n=$(id);if(n){const next=fmt(text);if(n.textContent!==next)n.textContent=next}}
function number(v,unit=""){return typeof v==="number"&&Number.isFinite(v)?v.toFixed(1)+unit:"UNAVAILABLE"}
function issue(message,isError=false){$("notice").textContent=message;$("notice").className="notice"+(isError?" error":"")}
async function api(path,options={}){
  const response=await fetch("/api/worker/"+path,{cache:"no-store",...options});
  let body;try{body=await response.json()}catch{body={error:"Invalid response"}}
  if(!response.ok)throw new Error(body.error||("HTTP "+response.status));
  return body;
}
async function post(path,data){
  return api(path,{method:"POST",headers:{"Content-Type":"application/json","X-Worker-Request":"1"},body:JSON.stringify(data)});
}
function percent(value){return typeof value==="number"&&Number.isFinite(value)&&value>=0?Math.min(value,100):null}
function meter(id,p){$(id).style.width=p===null?"0%":p+"%"}
function renderMetrics(info){
  const m=info&&info.sample||{},gpu=m.gpu||{};
  const bad=!info||info.stale||m.status==="measuring";
  const gpuUsed=gpu.memoryUsedMb,gpuTotal=gpu.memoryTotalMb;
  val("metricAge",bad?"STALE / MEASURING":info.ageSeconds+"s OLD");
  val("gpuName",bad?"UNAVAILABLE":gpu.available?fmt(gpu.name):"NO NVIDIA TELEMETRY");
  val("gpuDevice",bad?"UNAVAILABLE":gpu.available?fmt(gpu.name):"UNAVAILABLE");
  val("gpuLoadLabel",!bad&&gpu.available?number(gpu.utilization,"%"):"UNAVAILABLE");
  val("gpuUtil",!bad&&gpu.available?number(gpu.utilization,"%"):"UNAVAILABLE");
  val("gpuTemp",!bad&&gpu.available?number(gpu.temperatureC,"°C"):"UNAVAILABLE");
  val("gpuThermal",!bad&&gpu.available?number(gpu.temperatureC,"°C"):"UNAVAILABLE");
  val("vramLabel",!bad&&gpu.available&&gpuTotal>0?number(gpuUsed," MB")+" / "+number(gpuTotal," MB"):"UNAVAILABLE");
  val("gpuMemory",!bad&&gpu.available&&gpuTotal>0?number(gpuUsed," MB")+" / "+number(gpuTotal," MB"):"UNAVAILABLE");
  meter("gpuLoadBar",!bad&&gpu.available?percent(gpu.utilization):null);
  meter("vramBar",!bad&&gpu.available&&gpuTotal>0?percent(gpuUsed/gpuTotal*100):null);
  val("cpuSummary",bad?"UNAVAILABLE":number(m.cpuLoad,"%"));
  val("hostName",fmt(m.hostname));val("os",fmt(m.os));val("java",fmt(m.java));val("processors",fmt(m.processors));
  val("cpu",bad?"UNAVAILABLE":number(m.cpuLoad,"%"));val("processCpu",bad?"UNAVAILABLE":number(m.processCpuLoad,"%"));
  const memoryTotal=m.memoryTotalMb,memoryFree=m.memoryFreeMb;
  val("ram",!bad&&typeof memoryTotal==="number"&&typeof memoryFree==="number"?
    number(memoryTotal-memoryFree," MB")+" / "+number(memoryTotal," MB"):"UNAVAILABLE");
  val("jvm",!bad?number(m.jvmUsedMb," MB")+" / "+number(m.jvmMaxMb," MB"):"UNAVAILABLE");
  val("sampledAt",stamp(info&&info.sampledAt));
}
function safeController(url){
  try{const u=new URL(url);return ["http:","https:"].includes(u.protocol)&&!u.username&&!u.password?u.href:null}
  catch{return null}
}
function renderStatus(data){
  const w=data.worker||{},oldId=state.activeJob;
  state.worker=w;state.metrics=data.metrics;
  const life=fmt(w.lifecycle);val("lifecycle",life);$("lifecycle").className="state-readout"+(["ERROR","DISCONNECTED"].includes(life)?" bad":life==="DRAINING"||life==="PAUSED"?" warn":"");
  const connected=!!w.connected;$("connectionLamp").className="lamp "+(connected?"online":"offline");
  val("connectionText",connected?"CONTROLLER ONLINE":"CONTROLLER OFFLINE");
  val("workerId",w.workerId||"—");val("controllerUrl",w.controllerUrl||"—");
  val("heartbeat",stamp(w.lastHeartbeat));val("lastContact","LAST CONTACT "+stamp(w.lastContact));
  val("uptime",duration(w.uptimeSeconds));val("lastIssue",w.lastIssue||w.lastError||"NONE REPORTED");
  val("footerStatus",life+" / "+(connected?"LINK OK":"LINK DOWN"));
  const u=safeController(w.controllerUrl||data.controllerUiUrl);
  if(u){$("controllerLink").href=u;$("controllerLink").removeAttribute("aria-disabled")}
  else{$("controllerLink").removeAttribute("href");$("controllerLink").setAttribute("aria-disabled","true")}
  const claimed=!!w.currentJob;
  val("activeTitle",w.currentTopic||"No active job");val("activeId",w.currentJob||"—");
  val("activeType",w.jobType||"—");val("activeStage",w.stage||"—");val("activeDetail",w.detail||"—");
  val("activeStarted",stamp(w.jobStartedAt));
  val("jobTitle",w.currentTopic||"No active job running");val("jobId",w.currentJob||"—");
  val("jobType",w.jobType||"—");val("jobStage",w.stage||"—");
  val("jobDetail",w.detail||"—");val("jobStart",stamp(w.jobStartedAt));
  val("jobElapsed",w.jobStartedAt?duration((Date.now()-new Date(w.jobStartedAt).getTime())/1000):"—");
  if(oldId!==w.currentJob){state.activeJob=w.currentJob;state.stageEvents=[];renderStages()}
  for(const b of document.querySelectorAll("[data-action]")){
    const action=b.dataset.action,alive=!!w.running||["STARTING","CONNECTING","DRAINING","PAUSED","IDLE","RUNNING_VIDEO","RUNNING_ANALYSIS","DISCONNECTED"].includes(life);
    b.disabled=state.busy||(
      action==="start"?alive:
      action==="pause"?!alive||life==="PAUSED"||life==="DRAINING":
      action==="resume"?!alive||life!=="PAUSED":
      action==="drain"?!alive||life==="DRAINING":
      action==="restart"?life==="DRAINING":false
    );
  }
  renderMetrics(data.metrics);
}
function renderStages(){
  const ol=$("stageEvents");ol.replaceChildren();
  const entries=state.stageEvents.length?state.stageEvents:[{stage:"—",detail:"No live events received"}];
  for(const e of entries){const li=document.createElement("li");
    li.textContent=(e.timestamp?stamp(e.timestamp)+" / ":"")+fmt(e.stage)+" / "+fmt(e.detail);ol.append(li)}
}
function onWorkerEvent(e){
  if(e.type==="progress"&&state.worker&&state.worker.currentJob){
    state.stageEvents.push({stage:e.stage,detail:e.detail,timestamp:e.timestamp||e.at});
    if(state.stageEvents.length>80)state.stageEvents.shift();renderStages();
  }
  if(["job-start","job-finish","lifecycle","error","diagnostics"].includes(e.type))refresh();
  if(e.type==="log"&&e.entry)ingest([e.entry]);
}
function selectTab(name){
  for(const b of document.querySelectorAll(".tab")){const selected=b.dataset.tab===name;
    b.classList.toggle("active",selected);selected?b.setAttribute("aria-current","page"):b.removeAttribute("aria-current")}
  for(const view of document.querySelectorAll(".view"))view.classList.toggle("active",view.id==="tab-"+name);
  if(name==="logs")refreshLogs();if(name==="history")refreshJobs();if(name==="diagnostics")refreshDiagnostics();
  if(name==="settings")refreshSettings();
}
async function action(name){
  if(state.busy)return;state.busy=true;if(state.worker)renderStatus({worker:state.worker,metrics:state.metrics});
  try{const result=await post("control",{action:name});
    issue(name.toUpperCase()+" ACCEPTED / "+result.state+(result.next?" → "+result.next:""));
    await refresh();
  }catch(e){issue(name.toUpperCase()+" FAILED / "+e.message,true);await refresh()}
  finally{state.busy=false;if(state.worker)renderStatus({worker:state.worker,metrics:state.metrics})}
}
async function refresh(){
  const request=++state.seq;
  try{const data=await api("status");
    if(request<state.lastApplied)return;state.lastApplied=request;renderStatus(data);
    if(request===1)issue("Worker status loaded; controls ready.");
  }catch(e){issue("WORKER CONSOLE UNREACHABLE / "+e.message,true);$("connectionLamp").className="lamp offline"}
}
function makeCell(value){const td=document.createElement("td");td.textContent=fmt(value);return td}
async function refreshJobs(){
  try{const data=await api("jobs");const digest=JSON.stringify(data);
    if(digest===state.lastHistory)return;state.lastHistory=digest;
    const body=$("historyRows");const fragment=document.createDocumentFragment();
    if(!data.jobs?.length){const tr=document.createElement("tr");const td=makeCell("No jobs recorded");td.colSpan=8;tr.append(td);fragment.append(tr)}
    for(const j of data.jobs||[]){const tr=document.createElement("tr");
      for(const col of [j.jobId,j.topic,j.jobType,stamp(j.startedAt),stamp(j.endedAt),j.result,j.lastStage||j.stage,j.lastError||j.outputPath||"—"])tr.append(makeCell(col));
      fragment.append(tr)}
    body.replaceChildren(fragment);
  }catch(e){issue("HISTORY / "+e.message,true)}
}
function logKey(e){return [e.at,e.severity,e.component,e.message].join("|")}
function ingest(entries){
  for(const e of entries||[]){
    const key=logKey(e);if(state.logKeys.has(key))continue;
    state.logKeys.add(key);state.logEntries.push(e);
  }
  while(state.logEntries.length>2200){const old=state.logEntries.shift();state.logKeys.delete(logKey(old))}
  if(!state.pausedLogs)renderLogs();
}
async function refreshLogs(){try{ingest((await api("logs")).logs||[])}catch(e){issue("LOGS / "+e.message,true)}}
function visibleLogs(){
  const q=$("logSearch").value.trim().toLowerCase(),level=$("logSeverity").value;
  return state.logEntries.filter(e=>(!level||e.severity===level)&&
    Date.parse(e.at)>state.clearBefore&&
    (!q||[e.at,e.severity,e.component,e.message].join(" ").toLowerCase().includes(q)));
}
function renderLogs(){
  const term=$("logTerminal"),nearBottom=term.scrollTop+term.clientHeight>=term.scrollHeight-50;
  const fragment=document.createDocumentFragment();
  const rows=visibleLogs().slice(-700);
  for(const e of rows){const row=document.createElement("div");row.className="log-row "+String(e.severity).toLowerCase();
    const time=document.createElement("span"),level=document.createElement("span"),src=document.createElement("span"),message=document.createTextNode(" "+fmt(e.message));
    time.className="time";time.textContent=stamp(e.at)+" ";
    level.className="level";level.textContent=fmt(e.severity)+" ";
    src.textContent="["+fmt(e.component)+"]";
    row.append(time,level,src,message);fragment.append(row);
  }
  if(!rows.length){const div=document.createElement("div");div.textContent="No log entries match the current filter.";fragment.append(div)}
  // Avoid disturbing a user-selected range during incoming events.
  const selection=window.getSelection();
  if(selection&&selection.rangeCount&&selection.toString()&&term.contains(selection.anchorNode))return;
  term.replaceChildren(fragment);
  if(state.autoScroll&&nearBottom)term.scrollTop=term.scrollHeight;
}
async function refreshDiagnostics(){
  try{const result=await api("diagnostics"),digest=JSON.stringify(result);
    if(digest===state.lastDiagnostics)return;state.lastDiagnostics=digest;
    val("diagnosticsStatus",result.status||"NOT CHECKED");
    const frag=document.createDocumentFragment();
    if(!result.results?.length){const tr=document.createElement("tr");const td=makeCell("Not checked");td.colSpan=3;tr.append(td);frag.append(tr)}
    for(const c of result.results||[]){const tr=document.createElement("tr");
      tr.append(makeCell(c.name),makeCell(c.status),makeCell(c.detail));frag.append(tr)}
    $("diagnosticsRows").replaceChildren(frag);
  }catch(e){issue("DIAGNOSTICS / "+e.message,true)}
}
async function runDiagnostics(){
  const b=$("runDiagnostics");b.disabled=true;
  try{await post("diagnostics/run",{});issue("DEPENDENCY CHECKS RUNNING / NO HEAVY MODELS WILL BE LOADED")}
  catch(e){issue("DIAGNOSTICS / "+e.message,true)}
  finally{setTimeout(()=>{b.disabled=false;refreshDiagnostics()},5000)}
}
async function refreshSettings(){
  try{const d=await api("settings");if(state.settings)return;state.settings=d;
    for(const name of ["controllerUrl","workerId","port","logLimit","refreshSeconds"]){$("settingsForm").elements[name].value=d[name]}
    for(const name of ["autoStart","autoOpenBrowser"])$("settingsForm").elements[name].checked=!!d[name];
    val("inheritedConfig","INHERITED / ENCODER: "+fmt(d.controllerInheritedEncoder)+" // COMFYUI: "+fmt(d.controllerComfy)+
      " // REQUIRE COMFY: "+fmt(d.controllerRequireComfy)+" // TTS: "+fmt(d.ttsPrimary)+" → "+fmt(d.ttsFallback));
    val("credential",d.controllerTokenConfigured?"CONTROLLER CREDENTIAL: CONFIGURED (AUTONEWS_TOKEN)":"CONTROLLER CREDENTIAL: NOT CONFIGURED");
  }catch(e){issue("SETTINGS / "+e.message,true)}
}
async function saveSettings(ev){
  ev.preventDefault();const f=$("settingsForm");if(!f.reportValidity())return;
  const data={controllerUrl:f.elements.controllerUrl.value.trim(),workerId:f.elements.workerId.value.trim(),
    port:Number(f.elements.port.value),logLimit:Number(f.elements.logLimit.value),
    refreshSeconds:Number(f.elements.refreshSeconds.value),autoStart:f.elements.autoStart.checked,
    autoOpenBrowser:f.elements.autoOpenBrowser.checked};
  const b=f.querySelector('[type="submit"]');b.disabled=true;
  try{const result=await post("settings",data);state.settings=result;
    state.pollMs=result.refreshSeconds*1000;issue("PREFERENCES SAVED / CONNECTION CHANGES REQUIRE SAFE RESTART; PORT REQUIRES RELAUNCH")}
  catch(e){issue("SAVE FAILED / "+e.message,true)}
  finally{b.disabled=false}
}
function connectEvents(){
  const events=new EventSource("/api/worker/events");
  events.onmessage=msg=>{try{onWorkerEvent(JSON.parse(msg.data))}catch(e){console.warn("Invalid SSE event",e)}};
  events.onerror=()=>{issue("EVENT STREAM DISCONNECTED / RECONNECTING");refresh()};
}
for(const b of document.querySelectorAll(".tab"))b.addEventListener("click",()=>selectTab(b.dataset.tab));
for(const b of document.querySelectorAll("[data-action]"))b.addEventListener("click",()=>action(b.dataset.action));
$("runDiagnostics").addEventListener("click",runDiagnostics);
$("settingsForm").addEventListener("submit",saveSettings);
$("logSearch").addEventListener("input",renderLogs);$("logSeverity").addEventListener("change",renderLogs);
$("pauseLogs").addEventListener("click",()=>{state.pausedLogs=!state.pausedLogs;val("pauseLogs",state.pausedLogs?"RESUME DISPLAY":"PAUSE DISPLAY");if(!state.pausedLogs)renderLogs()});
$("toggleScroll").addEventListener("click",()=>{state.autoScroll=!state.autoScroll;val("toggleScroll","AUTO-SCROLL: "+(state.autoScroll?"ON":"OFF"))});
$("clearLogs").addEventListener("click",()=>{state.clearBefore=Date.now();renderLogs()});
$("copyLogs").addEventListener("click",async()=>{
  const t=visibleLogs().slice(-700).map(e=>[e.at,e.severity,e.component,e.message].join(" ")).join("\n");
  try{await navigator.clipboard.writeText(t);issue("VISIBLE EVENTS COPIED")}
  catch(e){issue("COPY FAILED / "+e.message,true)}
});
setInterval(()=>{
  val("clock",new Date().toLocaleTimeString());
  if(state.worker?.jobStartedAt)val("jobElapsed",duration((Date.now()-Date.parse(state.worker.jobStartedAt))/1000));
},1000);
(async()=>{
  await refresh();
  await Promise.all([refreshJobs(),refreshLogs(),refreshDiagnostics(),refreshSettings()]);
  state.pollMs=(state.settings?.refreshSeconds||5)*1000;
  connectEvents();
  // One reconciliation loop, even if SSE disconnects; no DOM replacement on unchanged state.
  async function poll(){await refresh();setTimeout(poll,state.pollMs)}
  setTimeout(poll,state.pollMs);
  setInterval(()=>{if(document.querySelector("#tab-history.active"))refreshJobs();
    if(document.querySelector("#tab-diagnostics.active"))refreshDiagnostics();
    if(document.querySelector("#tab-logs.active"))refreshLogs();
  },10000);
})();
