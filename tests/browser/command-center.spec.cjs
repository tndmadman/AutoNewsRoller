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
  let getDelay=0,boardDelay=0,postDelay=0,getCount=0,boardCount=0,postStatus=200,dropResponse=false;
  const server=http.createServer(async(req,res)=>{
    const url=new URL(req.url,"http://127.0.0.1");
    if(url.pathname==="/api/events"){
      res.writeHead(200,{"Content-Type":"text/event-stream","Cache-Control":"no-cache","Connection":"keep-alive"});
      res.write('data: {"type":"connected"}\n\n');clients.add(res);req.on("close",()=>clients.delete(res));return;
    }
    if(url.pathname==="/api/state"){
      getCount++;
      const snapshot=JSON.stringify(url.searchParams.get("includeStories")==="false"?{...state,stories:[]}:state), delay=getDelay;
      setTimeout(()=>{if(!res.writableEnded){res.writeHead(200,{"Content-Type":"application/json"});res.end(snapshot)}},delay);return;
    }
    if(url.pathname==="/api/board/stories"){
      boardCount++;
      const pageSize=Number(url.searchParams.get("pageSize")||25),wanted=Number(url.searchParams.get("page")||1);
      const status=url.searchParams.get("status")||"ALL",query=(url.searchParams.get("search")||"").toLowerCase();
      const hours=Number(url.searchParams.get("ageHours")||0);
      const operational=new Set(["QUEUED","PRODUCING","HOLD"]);
      const match=(s,filter)=>{
        const stat=String(s.status||"");
        if(filter==="ALL")return true;
        if(filter==="COMPLETE")return stat.startsWith("COMPLETE");
        if(filter==="TO_POST")return stat==="COMPLETE"&&!s.uploaded&&!s.scrapped;
        if(filter==="UPLOADED")return !!s.uploaded&&!s.scrapped;
        if(filter==="SCRAPPED")return !!s.scrapped;
        if(filter==="WORTHY")return !!s.worthy&&["DISCOVERED","VERIFIED","WORTHY"].includes(stat);
        return stat===filter;
      };
      const recent=s=>!hours||(Date.now()-Date.parse(s.latestPublishedAt))/3600000<=hours;
      const facets={};
      for(const key of ["ALL","DISCOVERED","WORTHY","VERIFIED","QUEUED","PRODUCING","HOLD","FAILED","COMPLETE","TO_POST","UPLOADED","SCRAPPED","SKIPPED"])
        facets[key]=state.stories.filter(s=>match(s,key)&&(recent(s)||operational.has(key))).length;
      const all=state.stories.filter(s=>match(s,status)&&(recent(s)||operational.has(status))
        &&[s.topic,s.category,s.uploadedPlatform,s.uploadNote,s.scrapReason,s.lastFailure,
          s.failureType,s.assignedWorker,s.lastAssignedWorker,...(s.publishers||[])].join(" ").toLowerCase().includes(query))
        .sort((a,b)=>(b.score||0)-(a.score||0)||String(a.id).localeCompare(String(b.id)));
      const totalPages=Math.max(1,Math.ceil(all.length/pageSize)),page=Math.min(Math.max(1,wanted),totalPages);
      const payload=JSON.stringify({stories:all.slice((page-1)*pageSize,page*pageSize),
        page,pageSize,total:all.length,totalPages,facets});
      const delay=boardDelay;
      setTimeout(()=>{if(!res.writableEnded){res.writeHead(200,{"Content-Type":"application/json"});res.end(payload)}},delay);
      return;
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
      const target=state.stories.find(s=>url.pathname.startsWith("/api/stories/"+encodeURIComponent(s.id)+"/"))||story;
      let storyResult={...target};
      if(url.pathname.endsWith("/action")){
        const a=body.action;
        if(a==="MAKE"||a==="WORTH"||a==="REMAKE"){target.status="QUEUED";target.stage="QUEUED";target.progress=15;state.counts.queued=1;}
        else if(a==="HOLD"){target.status="HOLD";target.stage="HOLD"}
        else if(a==="SKIP"){target.status="SKIPPED";target.stage="SKIPPED"}
        else if(a==="AUTO"){target.status="VERIFIED";target.stage="DISCOVERED"}
        storyResult={...target};
      }else if(url.pathname.endsWith("/upload-status")){
        version.uploaded=!!body.uploaded;
        target.uploaded=version.uploaded;
        state.counts.uploaded=+version.uploaded;
        storyResult={...target};
      }else if(url.pathname.endsWith("/scrap-status")){
        version.scrapped=!!body.scrapped;
        target.scrapped=version.scrapped;
        state.counts.scrapped=+version.scrapped;
        storyResult={...target};
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
  function event(type="worker",payload={status:"UPDATE"}){
    const data="data: "+JSON.stringify({type,timestamp:new Date().toISOString(),payload})+"\n\n";
    for(const client of clients)if(!client.writableEnded)client.write(data);
  }
  return {server,state,story,version,posts,event,setGetDelay:v=>{getDelay=v},setBoardDelay:v=>{boardDelay=v},get boardCount(){return boardCount},setPostDelay:v=>{postDelay=v},setPostStatus:v=>{postStatus=v},dropNextPost:()=>{dropResponse=true},get getCount(){return getCount},
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
    await expect.poll(()=>f.posts.filter(p=>p.body.action==="WORTH").length).toBe(1);
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
test("high-volume dashboard renders just one page while retaining all 120 stories",async({page})=>{
  const f=fixture();
  f.state.stories=Array.from({length:120},(_,i)=>({...f.story,id:"story-"+i,topic:"High-volume story #"+i}));
  f.state.counts.stories=120;
  const url=await f.start();
  try{
    await page.goto(url);
    const cards=page.locator(".storyCard");
    await expect(cards).toHaveCount(25);
    await page.evaluate(()=>{window.__firstCard=document.querySelector(".storyCard")});
    for(let i=0;i<12;i++){
      f.state.workers[i%5].metrics.cpuLoad=i*5;
      f.event("worker");await page.waitForTimeout(340);
    }
    expect(await page.evaluate(()=>window.__firstCard===document.querySelector(".storyCard")&&window.__firstCard.isConnected)).toBe(true);
    await expect(cards).toHaveCount(25);
    await expect(page.locator(".feed")).toHaveCount(90);
    await expect(page.locator("#boardResultCount")).toContainText("120");
    await page.locator('#boardBottomPager button[data-board-page="5"]').click();
    await expect(cards).toHaveCount(20);
    await expect(page.locator("#boardResultCount")).toContainText("101–120");
  }finally{await f.stop()}
});

test("scoped feed and worker SSE updates do not request or rebuild the full dashboard",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    await expect(page.locator(".workerCard")).toHaveCount(5);
    await expect(page.locator(".feed")).toHaveCount(90);
    await page.waitForTimeout(600);
    const count=f.getCount;
    await page.evaluate(()=>{
      window.__workerCard=document.querySelector(".workerCard");
      window.__feedCard=document.querySelector(".feed");
    });
    f.event("worker",{...f.state.workers[0],state:"PRODUCING"});
    f.event("feed",{...f.state.feeds[0],status:"SCANNING"});
    await expect(page.locator(".workerCard .workerState").first()).toContainText("PRODUCING");
    await expect(page.locator(".feed").first()).toHaveClass(/scanning/);
    expect(f.getCount).toBe(count);
    expect(await page.evaluate(()=>window.__workerCard===document.querySelector(".workerCard")&&
      window.__feedCard===document.querySelector(".feed"))).toBe(true);
  }finally{await f.stop()}
});

test("a snapshot started before SSE cannot overwrite newer worker status",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    await expect(page.locator(".workerCard")).toHaveCount(5);
    await page.waitForTimeout(350);
    f.setGetDelay(900);
    const count=f.getCount;
    f.event("story");
    await expect.poll(()=>f.getCount).toBeGreaterThan(count);
    f.event("worker",{...f.state.workers[0],state:"PRODUCING"});
    await expect(page.locator(".workerCard .workerState").first()).toContainText("PRODUCING");
    await page.waitForTimeout(1200);
    await expect(page.locator(".workerCard .workerState").first()).toContainText("PRODUCING");
  }finally{await f.stop()}
});


test("3,000 stories: lightweight polling and a maximum of one page in the DOM",async({page})=>{
  const f=fixture();
  f.state.stories=Array.from({length:3000},(_,i)=>({...f.story,id:"bulk-"+String(i).padStart(4,"0"),
    topic:"Bulk "+i,score:1-i/3001}));
  f.state.counts.stories=3000;
  const url=await f.start();
  let fullStatePayload=false,maxBoardItems=0,lightStateBytes=0,firstBoardBytes=0;
  page.on("response",async response=>{
    const u=new URL(response.url());
    if(u.pathname==="/api/state"){
      if(u.searchParams.get("includeStories")!=="false")fullStatePayload=true;
      else try{lightStateBytes=(await response.body()).length}catch{}
    }
    if(u.pathname==="/api/board/stories"){
      try{const body=await response.body();firstBoardBytes||=body.length;
        maxBoardItems=Math.max(maxBoardItems,JSON.parse(body.toString()).stories.length)}catch{}
    }
  });
  try{
    await page.goto(url);
    await expect(page.locator(".storyCard")).toHaveCount(25);
    await expect(page.locator("#boardResultCount")).toContainText("3,000");
    await page.locator("#boardPageSize").selectOption("100");
    await expect(page.locator(".storyCard")).toHaveCount(100);
    await page.locator("#boardBottomPager button[aria-label='Next page']").click();
    await expect(page.locator("#boardResultCount")).toContainText("101–200");
    expect(await page.locator(".storyCard").count()).toBeLessThanOrEqual(100);
    expect(maxBoardItems).toBeLessThanOrEqual(100);
    expect(fullStatePayload).toBe(false);
    const legacyStateBytes=Buffer.byteLength(JSON.stringify(f.state));
    console.log("Pagination fixture payload bytes",JSON.stringify({legacyStateBytes,lightStateBytes,firstBoardBytes,maxBoardItems,visibleCards:await page.locator(".storyCard").count()}));
    expect(firstBoardBytes).toBeLessThan(legacyStateBytes);
    expect(lightStateBytes).toBeLessThan(legacyStateBytes);
  }finally{await f.stop()}
});
test("page-2 story operation, search across pages, and clamping",async({page})=>{
  const f=fixture();
  f.state.stories=Array.from({length:26},(_,i)=>({...f.story,id:"story-"+String(i).padStart(3,"0"),
    topic:"Operation item "+i,score:1-i/100}));
  f.state.counts.stories=26;
  const url=await f.start();
  try{
    await page.goto(url);
    await expect(page.locator(".storyCard")).toHaveCount(25);
    await page.locator("#boardBottomPager button[aria-label='Next page']").click();
    await expect(page.locator(".storyCard")).toHaveCount(1);
    await page.locator('.storyCard [data-command="MAKE"]').click();
    await expect(page.locator(".storyCard .statusTag")).toContainText("QUEUED");
    expect(f.posts.filter(p=>p.body.action==="MAKE")).toHaveLength(1);
    await page.locator("#search").fill("Operation item 0");
    await expect(page.locator("#boardResultCount")).toContainText("Showing 1");
    await expect(page.locator(".storyCard")).toHaveCount(1);
    await expect(page.locator(".storyCard")).toContainText("Operation item 0");
  }finally{await f.stop()}
});
test("out-of-order requests never replace later pages or newer searches",async({page})=>{
  const f=fixture();
  f.state.stories=Array.from({length:100},(_,i)=>({...f.story,id:"out-"+i,topic:"Page race "+i,score:1-i/100}));
  f.state.counts.stories=100;
  const url=await f.start();
  try{
    await page.goto(url);
    await expect(page.locator(".storyCard")).toHaveCount(25);
    f.setBoardDelay(800);
    await page.locator("#boardBottomPager button[aria-label='Next page']").click();
    f.setBoardDelay(0);
    await page.locator("#boardTopPager button[aria-label='Page 4']").click();
    await expect(page.locator("#boardResultCount")).toContainText("76–100");
    await page.waitForTimeout(1050);
    await expect(page.locator("#boardResultCount")).toContainText("76–100");
    f.setBoardDelay(800);
    await page.locator("#search").fill("Page race 91");
    await page.waitForTimeout(300);
    f.setBoardDelay(0);
    await page.locator("#search").fill("Page race 3");
    await expect(page.locator(".storyCard").first()).toContainText("Page race 3");
    await page.waitForTimeout(1100);
    await expect(page.locator(".storyCard").first()).toContainText("Page race 3");
  }finally{await f.stop()}
});


test("status-changing action on the last filtered page clamps safely",async({page})=>{
  const f=fixture();
  f.state.stories=Array.from({length:26},(_,i)=>({...f.story,id:"clamp-"+i,
    topic:"Clamp example "+i,score:1-i/100,status:"VERIFIED"}));
  f.state.counts.stories=26;
  const url=await f.start();
  try{
    await page.goto(url);
    await expect(page.locator(".storyCard")).toHaveCount(25);
    await page.locator("#statusFilter").selectOption("VERIFIED");
    await expect(page.locator("#boardResultCount")).toContainText("of 26");
    await page.locator("#boardBottomPager button[aria-label='Next page']").click();
    await expect(page.locator(".storyCard")).toHaveCount(1);
    await page.locator('.storyCard [data-command="MAKE"]').click();
    await expect(page.locator("#boardResultCount")).toContainText("Showing 1–25 of 25");
    await expect(page.locator(".storyCard")).toHaveCount(25);
    expect(f.posts.filter(x=>x.body.action==="MAKE")).toHaveLength(1);
  }finally{await f.stop()}
});
test("old queued stories remain visible under age filter, failed stories obey age",async({page})=>{
  const f=fixture();
  const now=new Date().toISOString(),old=new Date(Date.now()-4*3600*1000).toISOString();
  f.state.stories=[
    {...f.story,id:"old-queue",topic:"Older queue",status:"QUEUED",latestPublishedAt:old},
    {...f.story,id:"old-failed",topic:"Older failure",status:"FAILED",latestPublishedAt:old},
    {...f.story,id:"new-failed",topic:"Recent failure",status:"FAILED",latestPublishedAt:now}];
  f.state.counts.stories=3;
  const url=await f.start();
  try{
    await page.goto(url);
    await expect(page.locator(".storyCard")).toHaveCount(3);
    await page.locator("#storyAgeFilter").selectOption("1");
    await expect(page.locator(".storyCard")).toHaveCount(1);
    await page.locator("#statusFilter").selectOption("QUEUED");
    await expect(page.locator(".storyCard")).toContainText("Older queue");
    await expect(page.locator("#storyAgeFilter")).toBeDisabled();
    await page.locator("#statusFilter").selectOption("FAILED");
    await expect(page.locator(".storyCard")).toContainText("Recent failure");
    await expect(page.locator("#boardResultCount")).toContainText("of 1");
  }finally{await f.stop()}
});
