const {test,expect}=require("@playwright/test");
const http=require("node:http");
const fs=require("node:fs");
const path=require("node:path");
const ROOT=path.resolve(__dirname,"../..");
const ASSETS=path.join(ROOT,"web/worker-console");
function fixture(){
  const actions=[],sockets=new Set(),sse=new Set();
  const now=new Date().toISOString();
  const worker={lifecycle:"IDLE",running:true,paused:false,draining:false,connected:true,workerId:"NODE-3090",
    controllerUrl:"http://127.0.0.1:8787",currentJob:"job-123",currentTopic:"Real sample news report",
    jobType:"VIDEO",stage:"TTS",detail:"KOKORO USED voice=af_sarah",
    jobStartedAt:now,lastHeartbeat:now,lastContact:now,uptimeSeconds:45,lastIssue:""};
  const metrics={sampledAt:now,ageSeconds:2,stale:false,sample:{hostname:"NODE-3090",os:"Windows 10",java:"21",
    processors:24,cpuLoad:33.3,processCpuLoad:12.1,memoryTotalMb:65536,memoryFreeMb:30000,jvmUsedMb:512,jvmMaxMb:8192,
    gpu:{available:true,name:"NVIDIA GeForce RTX 3090",utilization:42,memoryUsedMb:12000,memoryTotalMb:24576,temperatureC:64}}};
  const settings={controllerUrl:worker.controllerUrl,workerId:worker.workerId,port:8788,autoStart:true,autoOpenBrowser:true,
    logLimit:500,refreshSeconds:5,controllerInheritedEncoder:"auto",controllerComfy:true,controllerRequireComfy:true,
    ttsPrimary:"kokoro",ttsFallback:"qwen3",controllerTokenConfigured:true};
  let logs=[{at:now,severity:"INFO",component:"startup",message:"Worker connected"},
    {at:now,severity:"ERROR",component:"TTS",message:"Voice sample failed"}];
  let results={status:"NOT CHECKED",results:[]};
  const jobs=[{jobId:"job-122",topic:"Previous video",jobType:"VIDEO",startedAt:now,
    endedAt:now,result:"FAILED",lastStage:"RENDER",lastError:"Encoder exited 1"}];
  const send=(res,status,data)=>{res.writeHead(status,{"Content-Type":"application/json"});res.end(JSON.stringify(data))};
  const server=http.createServer(async(req,res)=>{
    const uri=new URL(req.url,"http://127.0.0.1");
    if(uri.pathname==="/api/worker/events"){
      res.writeHead(200,{"Content-Type":"text/event-stream","Cache-Control":"no-store","Connection":"keep-alive"});
      res.write(": ready\n\n");sse.add(res);req.on("close",()=>sse.delete(res));return;
    }
    if(req.method==="GET"&&["/","/index.html","/styles.css","/app.js"].includes(uri.pathname)){
      const name=uri.pathname==="/"?"index.html":uri.pathname.slice(1);
      res.writeHead(200,{"Content-Type":name.endsWith(".css")?"text/css":name.endsWith(".js")?"application/javascript":"text/html"});
      res.end(fs.readFileSync(path.join(ASSETS,name)));return;
    }
    if(req.method==="GET"){switch(uri.pathname){
      case "/api/worker/status":return send(res,200,{worker,metrics,controllerUiUrl:worker.controllerUrl,diagnostics:results.status});
      case "/api/worker/jobs":return send(res,200,{jobs});
      case "/api/worker/logs":return send(res,200,{logs});
      case "/api/worker/settings":return send(res,200,settings);
      case "/api/worker/diagnostics":return send(res,200,results);
      default:return send(res,404,{error:"Missing"});
    }}
    let body="";for await(const chunk of req)body+=chunk;
    const args=JSON.parse(body||"{}");
    if(uri.pathname==="/api/worker/control"){
      actions.push(args.action);
      if(args.action==="pause"){worker.lifecycle="PAUSED";worker.paused=true}
      if(args.action==="resume"){worker.lifecycle="IDLE";worker.paused=false}
      if(args.action==="drain"){worker.lifecycle="DRAINING";worker.draining=true}
      if(args.action==="start"){worker.lifecycle="STARTING";worker.running=true}
      return send(res,202,{accepted:true,state:worker.lifecycle});
    }
    if(uri.pathname==="/api/worker/settings"){Object.assign(settings,args);return send(res,200,settings)}
    if(uri.pathname==="/api/worker/diagnostics/run"){
      results={status:"DONE",checkedAt:now,results:[{name:"FFmpeg",status:"PASS",detail:"Exit 0"},
        {name:"Ollama",status:"WARN",detail:"Unreachable"}]};return send(res,202,{accepted:true,state:"RUNNING"})}
    send(res,404,{error:"Missing"});
  });
  server.on("connection",s=>{sockets.add(s);s.on("close",()=>sockets.delete(s))});
  return {actions,worker,metrics,settings,jobs,
    pushLog(e){logs.push(e);const msg="data: "+JSON.stringify({type:"log",entry:e})+"\n\n";for(const res of sse)res.write(msg)},
    async start(){await new Promise(ok=>server.listen(0,"127.0.0.1",ok));return "http://127.0.0.1:"+server.address().port},
    async stop(){for(const r of sse)r.end();for(const s of sockets)s.destroy();await new Promise(ok=>server.close(ok))}};
}
test("real node, hardware and active production state appear without invented values",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    await expect(page.locator("#lifecycle")).toHaveText("IDLE");
    await expect(page.locator("#gpuName")).toContainText("RTX 3090");
    await expect(page.locator("#gpuLoadLabel")).toHaveText("42.0%");
    await expect(page.locator("#activeStage")).toHaveText("TTS");
    await expect(page.locator("#controllerLink")).toHaveAttribute("href","http://127.0.0.1:8787/");
    await page.getByRole("button",{name:/HARDWARE/}).click();
    await expect(page.locator("#ram")).toContainText("MB");
    await expect(page.locator("#gpuThermal")).toHaveText("64.0°C");
  }finally{await f.stop()}
});
test("one request per control and actual paused/draining state",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    await page.locator('[data-action="pause"]').click();
    await expect(page.locator("#lifecycle")).toHaveText("PAUSED");
    await page.locator('[data-action="resume"]').click();
    await expect(page.locator("#lifecycle")).toHaveText("IDLE");
    await page.locator('[data-action="drain"]').click();
    await expect(page.locator("#lifecycle")).toHaveText("DRAINING");
    expect(f.actions).toEqual(["pause","resume","drain"]);
  }finally{await f.stop()}
});
test("logs can filter, select text and survive an SSE update",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    await page.getByRole("button",{name:/LIVE LOGS/}).click();
    await expect(page.locator(".log-row")).toHaveCount(2);
    await page.locator("#logSeverity").selectOption("ERROR");
    await expect(page.locator(".log-row")).toHaveCount(1);
    await page.locator("#logSeverity").selectOption("");
    await page.locator("#logSearch").fill("connected");
    await expect(page.locator(".log-row")).toHaveCount(1);
    await page.locator("#logSearch").fill("");
    await page.evaluate(()=>{
      const row=document.querySelector(".log-row"),range=document.createRange();range.selectNodeContents(row);
      const selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);
    });
    const before=await page.evaluate(()=>window.getSelection().toString());
    f.pushLog({at:new Date(Date.now()+100).toISOString(),severity:"INFO",component:"render",message:"Audit complete"});
    await page.waitForTimeout(600);
    const after=await page.evaluate(()=>window.getSelection().toString());
    expect(after).toBe(before);
    await page.locator("#pauseLogs").click();await expect(page.locator("#pauseLogs")).toHaveText("RESUME DISPLAY");
  }finally{await f.stop()}
});
test("history, diagnostics and validated settings are operational",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    await page.getByRole("button",{name:/HISTORY/}).click();
    await expect(page.locator("#historyRows")).toContainText("Encoder exited 1");
    await page.getByRole("button",{name:/DIAGNOSTICS/}).click();
    await page.locator("#runDiagnostics").click();
    await expect(page.locator("#notice")).toContainText("RUNNING");
    await page.getByRole("button",{name:/SETTINGS/}).click();
    await page.locator('input[name="workerId"]').fill("INVALID ID");
    await page.locator('#settingsForm button[type="submit"]').click();
    expect(f.settings.workerId).toBe("NODE-3090");
    await page.locator('input[name="workerId"]').fill("NODE-ALT");
    await page.locator('#settingsForm button[type="submit"]').click();
    await expect(page.locator("#notice")).toContainText("SAVED");
    expect(f.settings.workerId).toBe("NODE-ALT");
  }finally{await f.stop()}
});
test("worker terminal screenshots and responsive width at reference viewports",async({page})=>{
  const f=fixture(),url=await f.start();
  const dest=path.join(ROOT,"test-results","worker-console-screenshots");
  fs.mkdirSync(dest,{recursive:true});
  try{
    for(const [width,height] of [[1440,900],[1024,768],[390,844]]){
      await page.setViewportSize({width,height});await page.goto(url);
      await expect(page.locator("#gpuLoadLabel")).toHaveText("42.0%");
      const overflow=await page.evaluate(()=>document.documentElement.scrollWidth-innerWidth);
      expect(overflow).toBeLessThanOrEqual(1);
      await page.screenshot({path:path.join(dest,width+"x"+height+".png"),fullPage:true});
    }
  }finally{await f.stop()}
});
