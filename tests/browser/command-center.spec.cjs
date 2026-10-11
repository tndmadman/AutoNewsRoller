const {test,expect}=require("@playwright/test");
const http=require("node:http");
const fs=require("node:fs");
const path=require("node:path");
const {execFileSync}=require("node:child_process");
const ROOT=path.resolve(__dirname,"../..");
const files={"": "index.html","/":"index.html","/app.js":"app.js","/styles.css":"styles.css"};
function fixture(appOverride=null){
  const story={id:"story-1",topic:"Selected text must survive updates and worker activity",status:"VERIFIED",stage:"DISCOVERED",progress:12,score:.9,verified:true,worthy:true,
    latestPublishedAt:new Date().toISOString(),publishers:["Associated Press","Reuters"],category:"general",independentSources:2,sourceMix:{left:0,center:2,right:0,unknown:0}};
  const version={storyId:"story-1",versionId:"story-1#v1",version:1,current:true,topic:story.topic,
    completedAt:new Date().toISOString(),filename:"test.mp4",uploaded:false,scrapped:false};
  const state={stories:[story],videos:[version],workers:Array.from({length:5},(_,i)=>({id:"worker-"+i,online:true,state:"IDLE",metrics:{cpuLoad:12+i,memoryTotalMb:20000,memoryFreeMb:10000,gpu:{available:false}}})),
    feeds:Array.from({length:90},(_,i)=>({url:"https://feed.test/rss/"+i,name:"Publisher "+i,category:"general",status:"OK",entries:3})),
    counts:{stories:1,worthy:1,verified:1,feedsOk:90,feedsFailed:0,workersOnline:5,queued:0,producing:0,complete:1,videoVersions:1,toPost:1,uploaded:0,scrapped:0},
    autoQueue:false,autoThreshold:.75,scanning:false};
  const clients=new Set(),posts=[];
  let getDelay=0,postDelay=0,getCount=0,postStatus=200,dropResponse=false;
  const server=http.createServer(async(req,res)=>{
    const url=new URL(req.url,"http://127.0.0.1");
    if(url.pathname==="/api/events"){
      res.writeHead(200,{"Content-Type":"text/event-stream","Cache-Control":"no-cache","Connection":"keep-alive"});
      res.write('data: {"type":"connected"}\n\n');clients.add(res);req.on("close",()=>clients.delete(res));return;
    }
    if(url.pathname==="/api/state"){
      getCount++;
      const snapshot=JSON.stringify(state), delay=getDelay;
      setTimeout(()=>{if(!res.writableEnded){res.writeHead(200,{"Content-Type":"application/json"});res.end(snapshot)}},delay);return;
    }
    if(req.method==="POST"){
      let raw="";for await(const chunk of req)raw+=chunk;
      const body=raw?JSON.parse(raw):{};
      posts.push({path:url.pathname,body});
      if(postStatus!==200){
        const status=postStatus, response=JSON.stringify({error:status===409?"Job is already producing":status===500?"State could not be saved":"authentication required"});
        setTimeout(()=>{if(!res.writableEnded){res.writeHead(status,{"Content-Type":"application/json"});res.end(response)}},postDelay);
        return;
      }
      let storyResult={...story};
      if(url.pathname.endsWith("/action")){
        const a=body.action;
        if(a==="MAKE"||a==="WORTH"||a==="REMAKE"){story.status="QUEUED";story.stage="QUEUED";story.progress=15;state.counts.queued=1;}
        else if(a==="HOLD"){story.status="HOLD";story.stage="HOLD"}
        else if(a==="SKIP"){story.status="SKIPPED";story.stage="SKIPPED"}
        else if(a==="AUTO"){story.status="VERIFIED";story.stage="DISCOVERED"}
        storyResult={...story};
      }else if(url.pathname.endsWith("/upload-status")){
        version.uploaded=!!body.uploaded;
        story.uploaded=version.uploaded;
        state.counts.uploaded=+version.uploaded;
        storyResult={...story};
      }else if(url.pathname.endsWith("/scrap-status")){
        version.scrapped=!!body.scrapped;
        story.scrapped=version.scrapped;
        state.counts.scrapped=+version.scrapped;
        storyResult={...story};
      }
      if(dropResponse){dropResponse=false;req.socket.destroy();return}
      const response=JSON.stringify(storyResult);
      setTimeout(()=>{if(!res.writableEnded){res.writeHead(200,{"Content-Type":"application/json"});res.end(response)}},postDelay);return;
    }
    const name=files[url.pathname];
    if(name){
      const body=name==="app.js"&&appOverride!==null?Buffer.from(appOverride):fs.readFileSync(path.join(ROOT,"web/command-center",name));
      res.writeHead(200,{"Content-Type":name.endsWith(".js")?"application/javascript":name.endsWith(".css")?"text/css":"text/html"});res.end(body);return;
    }
    res.writeHead(404);res.end("Not found");
  });
  function event(type="worker"){
    const data="data: "+JSON.stringify({type,timestamp:new Date().toISOString(),payload:{status:"UPDATE"}})+"\n\n";
    for(const client of clients)if(!client.writableEnded)client.write(data);
  }
  return {server,state,story,version,posts,event,setGetDelay:v=>{getDelay=v},setPostDelay:v=>{postDelay=v},setPostStatus:v=>{postStatus=v},dropNextPost:()=>{dropResponse=true},get getCount(){return getCount},
    async start(){await new Promise(ok=>server.listen(0,"127.0.0.1",ok));return "http://127.0.0.1:"+server.address().port},
    async stop(){for(const client of clients)client.destroy();await new Promise(ok=>server.close(ok))}};
}
test("selected text and card identity survive thirty seconds of live worker/feed updates",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    const card=page.locator("#stories .storyCard").first();
    await expect(card).toBeVisible();
    const initial=await page.evaluate(()=>{
      const element=document.querySelector(".storyTitle");
      window.__trackedTitle=element;const range=document.createRange();range.selectNodeContents(element);
      const sel=getSelection();sel.removeAllRanges();sel.addRange(range);return sel.toString();
    });
    for(let i=0;i<310;i++){
      f.state.workers[0].metrics.cpuLoad=i%100;
      f.story.progress=i%100;
      f.state.feeds[i%90].status=i%2?"OK":"SCANNING";
      f.event("worker");f.event("feed");
      await page.waitForTimeout(100);
    }
    const after=await page.evaluate(()=>({
      text:getSelection().toString(),same:document.querySelector(".storyTitle")===window.__trackedTitle,
      connected:window.__trackedTitle.isConnected
    }));
    expect(after).toEqual({text:initial,same:true,connected:true});
    expect(f.getCount).toBeLessThan(130);
  }finally{await f.stop()}
});
test("pointerdown plus refresh before pointerup results in exactly one MAKE action",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    const button=page.locator('.storyCard [data-command="MAKE"]');
    await expect(button).toBeVisible();
    await button.scrollIntoViewIfNeeded();
    const rect=await button.boundingBox();
    const hit=await page.evaluate(({x,y})=>document.elementFromPoint(x,y)?.closest("[data-command]")?.dataset.command,
      {x:rect.x+rect.width/2,y:rect.y+rect.height/2});
    expect(hit).toBe("MAKE");
    await page.mouse.move(rect.x+rect.width/2,rect.y+rect.height/2);
    await page.mouse.down();
    f.story.progress=14;f.event("story");
    await page.waitForTimeout(700);
    await page.mouse.up();
    await expect.poll(()=>f.posts.filter(p=>p.body.action==="MAKE").length).toBe(1);
    await expect(page.locator(".storyCard .statusTag").first()).toContainText("QUEUED");
  }finally{await f.stop()}
});
test("five click burst locks pending action and never posts duplicate",async({page})=>{
  const f=fixture(),url=await f.start();f.setPostDelay(950);
  try{
    await page.goto(url);
    await expect(page.locator(".storyCard")).toHaveCount(1);
    await page.evaluate(()=>{
      const button=document.querySelector('.storyCard [data-command="WORTH"]');
      for(let i=0;i<5;i++)button.dispatchEvent(new MouseEvent("click",{bubbles:true}));
    });
    await expect(page.locator('.storyCard [data-command="WORTH"]')).toBeDisabled();
    expect(f.posts.filter(p=>p.body.action==="WORTH").length).toBe(1);
    await expect(page.locator(".storyCard .statusTag").first()).toContainText("QUEUED");
  }finally{await f.stop()}
});
test("archive actions target exact version, with selection surviving archive updates",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    page.on("dialog",dialog=>dialog.accept(dialog.type()==="prompt"?"duplicate test":""));
    const row=page.locator(".videoRow").first();
    await expect(row).toBeVisible();
    await row.locator('[data-command="SCRAP"]').click();
    await expect.poll(()=>f.posts.filter(p=>p.path.endsWith("/scrap-status")).length).toBe(1);
    const submission=f.posts.find(p=>p.path.endsWith("/scrap-status"));
    expect(submission.body.versionId).toBe("story-1#v1");
    expect(submission.body.operationId).toBeTruthy();
    await expect(row.locator('[data-command="RESTORE"]')).toBeVisible();
  }finally{await f.stop()}
});
test("snapshot begun before action cannot replace acknowledged result",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    await expect(page.locator(".storyCard")).toHaveCount(1);
    f.setGetDelay(900);
    f.event("worker");
    await page.waitForTimeout(350);
    await page.locator('.storyCard [data-command="MAKE"]').click();
    await expect(page.locator(".storyCard .statusTag").first()).toContainText("QUEUED");
    await page.waitForTimeout(1400);
    await expect(page.locator(".storyCard .statusTag").first()).toContainText("QUEUED");
  }finally{await f.stop()}
});

test("before/after baseline: live refresh no longer replaces story cards",async({page})=>{
  const baseline=execFileSync("git",["show","538370e30fd0dcbd011af47eaa868d4842c4c345:web/command-center/app.js"],{cwd:ROOT,encoding:"utf8"});
  async function measure(app){
    const f=fixture(app),url=await f.start();
    try{
      await page.goto(url);
      await expect(page.locator(".storyCard")).toHaveCount(1);
      await page.evaluate(()=>{
        const root=document.getElementById("stories");
        window.__domChurn=0;
        window.__initialCard=root.firstElementChild;
        window.__watch=new MutationObserver(entries=>{
          for(const entry of entries)if(entry.target===root)window.__domChurn+=entry.removedNodes.length;
        });
        window.__watch.observe(root,{childList:true,subtree:false});
      });
      for(let i=0;i<10;i++){
        f.state.workers[0].metrics.cpuLoad=20+i;
        f.event("worker");
        await page.waitForTimeout(430);
      }
      await page.waitForTimeout(500);
      const metric=await page.evaluate(()=>{
        window.__watch.disconnect();
        return {removedStoryNodes:window.__domChurn,originalCardSurvived:window.__initialCard.isConnected};
      });
      return {...metric,refreshRequests:f.getCount};
    }finally{await f.stop()}
  }
  const before=await measure(baseline);
  const after=await measure(null);
  console.log("Command Center live-refresh DOM churn benchmark",JSON.stringify({before,after}));
  expect(before.removedStoryNodes).toBeGreaterThan(5);
  expect(before.originalCardSurvived).toBe(false);
  expect(after.removedStoryNodes).toBe(0);
  expect(after.originalCardSurvived).toBe(true);
});

test("HTTP 401, 409, and 500 display errors and restore usable controls",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    const button=page.locator('.storyCard [data-command="MAKE"]');
    await expect(button).toBeVisible();
    for(const [status,message] of [[401,"Authentication required"],[409,"already producing"],[500,"could not be saved"]]){
      f.setPostStatus(status);
      await button.click();
      await expect(page.locator("#toast")).toContainText(message);
      await expect(button).toBeEnabled();
    }
    expect(f.posts.filter(p=>p.body.action==="MAKE").length).toBe(3);
    await expect(page.locator(".storyCard .statusTag").first()).toContainText("VERIFIED");
  }finally{await f.stop()}
});
test("lost response after server commit is reported as uncertain and refreshes state",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    await expect(page.locator(".storyCard")).toHaveCount(1);
    // Emulate a committed server-side mutation whose response never reaches fetch.
    // Routing is deterministic across Firefox/Chromium, unlike socket destruction
    // after the Node HTTP request stream has completed.
    await page.route("**/api/stories/story-1/action",async route=>{
      const body=JSON.parse(route.request().postData()||"{}");
      f.posts.push({path:"/api/stories/story-1/action",body});
      f.story.status="QUEUED";f.story.stage="QUEUED";f.story.progress=15;f.state.counts.queued=1;
      await route.abort("failed");
    });
    await page.locator('.storyCard [data-command="MAKE"]').click();
    await expect(page.locator("#toast")).toContainText("may have completed");
    await expect(page.locator(".storyCard .statusTag").first()).toContainText("QUEUED");
    expect(f.posts.filter(p=>p.body.action==="MAKE").length).toBe(1);
  }finally{await f.stop()}
});
test("expanded failure history remains open during live updates",async({page})=>{
  const f=fixture();f.story.status="FAILED";f.story.failureType="RENDER_FAILURE";f.story.lastFailure="retry budget exhausted";
  f.story.productionAttempts=[{event:"FAILED",attempt:1,stage:"RENDER",worker:"worker-1",error:"timeout",at:new Date().toISOString()}];
  const url=await f.start();
  try{
    await page.goto(url);
    const details=page.locator(".storyCard details.attemptHistory");
    await expect(details).toBeVisible();
    await details.locator("summary").click();
    await expect(details).toHaveAttribute("open","");
    for(let i=0;i<12;i++){f.story.progress=i;f.event("story");await page.waitForTimeout(340)}
    await expect(details).toHaveAttribute("open","");
  }finally{await f.stop()}
});
test("high-volume dashboard preserves card identity with 120 stories, 90 feeds and five workers",async({page})=>{
  const f=fixture();
  f.state.stories=Array.from({length:120},(_,i)=>({...f.story,id:"story-"+i,topic:"High-volume story #"+i}));
  f.state.counts.stories=120;
  const url=await f.start();
  try{
    await page.goto(url);
    const cards=page.locator(".storyCard");
    await expect(cards).toHaveCount(120);
    await page.evaluate(()=>{window.__firstCard=document.querySelector(".storyCard")});
    for(let i=0;i<12;i++){
      f.state.workers[i%5].metrics.cpuLoad=i*5;
      f.event("worker");await page.waitForTimeout(340);
    }
    expect(await page.evaluate(()=>window.__firstCard===document.querySelector(".storyCard")&&window.__firstCard.isConnected)).toBe(true);
    await expect(cards).toHaveCount(120);
    await expect(page.locator(".feed")).toHaveCount(90);
  }finally{await f.stop()}
});
