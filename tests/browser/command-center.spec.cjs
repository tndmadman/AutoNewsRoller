const {test,expect}=require("@playwright/test");
const http=require("node:http");
const fs=require("node:fs");
const path=require("node:path");
const {execFileSync}=require("node:child_process");
const ROOT=path.resolve(__dirname,"../..");
const files={"": "index.html","/":"index.html","/app.js":"app.js","/assistant.js":"assistant.js","/styles.css":"styles.css"};
function fixture(appOverride=null){
  const story={id:"story-1",topic:"Selected text must survive updates and worker activity",status:"VERIFIED",stage:"DISCOVERED",progress:12,score:.9,verified:true,worthy:true,
    latestPublishedAt:new Date().toISOString(),publishers:["Associated Press","Reuters"],category:"local",politicalCandidate:false,independentSources:1,sourceMix:{left:0,center:2,right:0,unknown:0}};
  const version={storyId:"story-1",versionId:"story-1#v1",version:1,current:true,topic:story.topic,
    completedAt:new Date().toISOString(),filename:"test.mp4",productionMode:"STANDARD",uploaded:false,scrapped:false};
  const state={stories:[story],videos:[version],workers:Array.from({length:5},(_,i)=>({id:"worker-"+i,online:true,state:"IDLE",metrics:{cpuLoad:12+i,memoryTotalMb:20000,memoryFreeMb:10000,gpu:{available:false}}})),
    feeds:Array.from({length:90},(_,i)=>({url:"https://feed.test/rss/"+i,name:"Publisher "+i,category:"general",status:"OK",entries:3})),
    counts:{stories:1,worthy:1,verified:1,feedsOk:90,feedsFailed:0,workersOnline:5,queued:0,producing:0,complete:1,videoVersions:1,toPost:1,uploaded:0,scrapped:0},
    autoQueue:false,autoThreshold:.75,scanning:false};
  const clients=new Set(),posts=[],assistantMessages=[
    {role:"user",content:"What is queued?",at:new Date().toISOString()},
    {role:"assistant",content:"One sample story in the current queue.",at:new Date().toISOString()}
  ];
  let getDelay=0,boardDelay=0,postDelay=0,getCount=0,boardCount=0,postStatus=200,dropResponse=false;
  const server=http.createServer(async(req,res)=>{
    const url=new URL(req.url,"http://127.0.0.1");
    if(url.pathname==="/api/events"){
      res.writeHead(200,{"Content-Type":"text/event-stream","Cache-Control":"no-cache","Connection":"keep-alive"});
      res.write('data: {"type":"connected"}\n\n');clients.add(res);req.on("close",()=>clients.delete(res));return;
    }
    if(url.pathname==="/api/assistant"&&req.method==="GET"){
      res.writeHead(200,{"Content-Type":"application/json"});
      res.end(JSON.stringify({model:"fixture-ollama",messages:assistantMessages,persisted:true,readOnly:true}));return;
    }
    if(url.pathname==="/api/assistant/prompts"&&req.method==="GET"){
      res.writeHead(200,{"Content-Type":"application/json"});
      res.end(JSON.stringify({prompts:[{id:"meme-hook",label:"Meme hooks",path:"src/autonewsroller/script/MemeHookPlanner.java"}]}));return;
    }
    if(url.pathname==="/api/assistant/prompt"&&req.method==="GET"){
      res.writeHead(200,{"Content-Type":"application/json"});
      res.end(JSON.stringify({path:"src/autonewsroller/script/MemeHookPlanner.java",
        text:"You are a comedy hook planner. Source <img src=x onerror=alert(1)>",
        note:"Source template; not exact runtime prompt.",truncated:false}));return;
    }
    if(url.pathname==="/api/assistant/history"&&req.method==="DELETE"){
      assistantMessages.splice(0);
      res.writeHead(200,{"Content-Type":"application/json"});
      res.end(JSON.stringify({ok:true,messages:[]}));return;
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
      const memeFilter=url.searchParams.get("memeFilter")||"ALL_ANALYZED";
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
        if(filter==="MEME_WORTHY")return s.memeManualDecision!=="NOT_WORTH"&&
          (s.memeManualDecision==="WORTH"||(s.memeAnalysisStatus==="COMPLETE"&&Number(s.memeScore)>=72));
        if(filter==="MEME_DISCOVERY")return s.memeAnalysisStatus==="COMPLETE";
        return stat===filter;
      };
      const matchMeme=(s,filter)=>{
        if(filter==="PENDING")return ["QUEUED","ANALYZING"].includes(s.memeAnalysisStatus);
        if(filter==="FAILED")return s.memeAnalysisStatus==="FAILED";
        if(s.memeAnalysisStatus!=="COMPLETE")return false;
        if(filter==="ALL_ANALYZED")return true;
        const insufficient=s.memeClassification==="INSUFFICIENT_EVIDENCE"||
          s.memeAnalysis?.evidenceStatus==="INSUFFICIENT_EVIDENCE";
        if(filter==="INSUFFICIENT_EVIDENCE")return insufficient;
        if(insufficient||s.memeScore==null)return false;
        if(filter==="EXCELLENT")return s.memeScore>=85;
        if(filter==="WORTHY")return s.memeScore>=72;
        if(filter==="POSSIBLE")return s.memeScore>=55&&s.memeScore<72;
        if(filter==="LOW")return s.memeScore<55;
        return false;
      };
      const recent=s=>!hours||(Date.now()-Date.parse(s.latestPublishedAt))/3600000<=hours;
      const facets={};
      for(const key of ["ALL","DISCOVERED","WORTHY","MEME_WORTHY","MEME_DISCOVERY","VERIFIED","QUEUED","PRODUCING","HOLD","FAILED","COMPLETE","TO_POST","UPLOADED","SCRAPPED","SKIPPED"])
        facets[key]=state.stories.filter(s=>match(s,key)&&(recent(s)||operational.has(key))).length;
      const isMemeView=["MEME_DISCOVERY","MEME_WORTHY"].includes(status);
      const all=state.stories.filter(s=>(status==="MEME_DISCOVERY"?matchMeme(s,memeFilter):match(s,status))
        &&(recent(s)||operational.has(status))
        &&[s.topic,s.category,s.uploadedPlatform,s.uploadNote,s.scrapReason,s.lastFailure,
          s.failureType,s.assignedWorker,s.lastAssignedWorker,...(s.publishers||[])].join(" ").toLowerCase().includes(query))
        .sort((a,b)=>(isMemeView?(b.memeScore??-1)-(a.memeScore??-1):(b.score||0)-(a.score||0))||
          String(a.id).localeCompare(String(b.id)));
      const totalPages=Math.max(1,Math.ceil(all.length/pageSize)),page=Math.min(Math.max(1,wanted),totalPages);
      const payload=JSON.stringify({stories:all.slice((page-1)*pageSize,page*pageSize).map(s=>isMemeView?{...s,memeCandidate:true,boardEntryId:s.id+"::MEME_CANDIDATE"}:s),
        page,pageSize,total:all.length,totalPages,facets});
      const delay=boardDelay;
      setTimeout(()=>{if(!res.writableEnded){res.writeHead(200,{"Content-Type":"application/json"});res.end(payload)}},delay);
      return;
    }
    if(req.method==="POST"){
      let raw="";for await(const chunk of req)raw+=chunk;
      const body=raw?JSON.parse(raw):{};
      posts.push({path:url.pathname,body});
      if(url.pathname==="/api/assistant/chat"){
        const at=new Date().toISOString();
        assistantMessages.push({role:"user",content:body.message,at},
          {role:"assistant",content:"CURRENT: 1 tracked story. <script>alert(1)</script>",at});
        res.writeHead(200,{"Content-Type":"application/json"});
        res.end(JSON.stringify({model:"fixture-ollama",answer:assistantMessages.at(-1).content,
          at,messages:assistantMessages.length,readOnly:true}));return;
      }
      if(postStatus!==200){
        const status=postStatus, response=JSON.stringify({error:status===409?"Job is already producing":status===500?"State could not be saved":"authentication required"});
        setTimeout(()=>{if(!res.writableEnded){res.writeHead(status,{"Content-Type":"application/json"});res.end(response)}},postDelay);
        return;
      }
      const target=state.stories.find(s=>url.pathname.startsWith("/api/stories/"+encodeURIComponent(s.id)+"/"))||story;
      let storyResult={...target};
      if(url.pathname.endsWith("/action")){
        const a=body.action;
        if(a==="MAKE"||a==="WORTH"||a==="REMAKE"||a==="MAKE_FRAMING_EXPOSURE"||a==="REMAKE_FRAMING_EXPOSURE"){target.status="QUEUED";target.productionMode=a==="MAKE_FRAMING_EXPOSURE"?"FRAMING_EXPOSURE":"STANDARD";target.stage="QUEUED";target.progress=15;state.counts.queued=1;}
        else if(a==="HOLD"){target.status="HOLD";target.stage="HOLD"}
        else if(a==="SKIP"){target.status="SKIPPED";target.stage="SKIPPED"}
        else if(a==="AUTO"){target.status="VERIFIED";target.stage="DISCOVERED"}
        storyResult={...target};
      }else if(url.pathname.endsWith("/analyze-meme")){
        target.memeAnalysisStatus="QUEUED";
      }else if(url.pathname.endsWith("/meme-decision")){
        target.memeManualDecision=body.decision;
      }else if(url.pathname.endsWith("/upload-status")){
        const chosen=state.videos.find(v=>v.versionId===body.versionId)||version;
        chosen.uploaded=!!body.uploaded;
        const match=state.stories.find(x=>x.currentVideoVersionId===chosen.versionId);
        if(match)match.uploaded=chosen.uploaded;
        state.counts.uploaded=state.videos.filter(v=>v.uploaded).length;
        storyResult={...target};
      }else if(url.pathname.endsWith("/scrap-status")){
        const chosen=state.videos.find(v=>v.versionId===body.versionId)||version;
        chosen.scrapped=!!body.scrapped;
        const match=state.stories.find(x=>x.currentVideoVersionId===chosen.versionId);
        if(match)match.scrapped=chosen.scrapped;
        state.counts.scrapped=state.videos.filter(v=>v.scrapped).length;
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
test("local Ollama chat restores history, escapes untrusted answers, inspects prompts and clears safely",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    await expect(page.locator("#assistantMessages .assistantBubble")).toHaveCount(2);
    await expect(page.locator("#assistantModel")).toContainText("fixture-ollama");
    await page.locator("#assistantInput").fill("What is being made?");
    await page.locator("#assistantSend").click();
    await expect(page.locator("#assistantMessages .assistantBubble")).toHaveCount(4);
    await expect(page.locator("#assistantMessages")).toContainText("CURRENT: 1 tracked story.");
    await expect(page.locator("#assistantMessages script")).toHaveCount(0);
    expect(f.posts.filter(x=>x.path==="/api/assistant/chat")).toHaveLength(1);
    await page.reload();
    await expect(page.locator("#assistantMessages .assistantBubble")).toHaveCount(4);
    await page.locator(".assistantPromptLibrary summary").click();
    await page.locator("#assistantPromptSelect").selectOption("meme-hook");
    await expect(page.locator("#assistantPromptSource")).toContainText("comedy hook planner");
    await expect(page.locator("#assistantPromptSource img")).toHaveCount(0);
    page.on("dialog",dialog=>dialog.accept());
    await page.locator("#assistantClear").click();
    await expect(page.locator("#assistantMessages .assistantBubble")).toHaveCount(0);
    await page.reload();
    await expect(page.locator("#assistantMessages .assistantBubble")).toHaveCount(0);
  }finally{await f.stop();}
});

test("assistant Enter sends and Shift+Enter inserts newline without posting",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    await expect(page.locator("#assistantMessages .assistantBubble")).toHaveCount(2);
    const composer=page.locator("#assistantInput");
    await composer.fill("How many meme evaluations?");
    await composer.press("Shift+Enter");
    await expect(composer).toHaveValue("How many meme evaluations?\n");
    expect(f.posts.filter(x=>x.path==="/api/assistant/chat")).toHaveLength(0);
    await composer.type("Show completed counts.");
    await composer.press("Enter");
    await expect(page.locator("#assistantMessages .assistantBubble")).toHaveCount(4);
    const chats=f.posts.filter(x=>x.path==="/api/assistant/chat");
    expect(chats).toHaveLength(1);
    expect(chats[0].body.message).toBe("How many meme evaluations?\nShow completed counts.");
    await expect(composer).toHaveValue("");
    await composer.fill("hello");
    await composer.press("Enter");
    await expect(page.locator("#assistantMessages .assistantBubble")).toHaveCount(6);
    expect(f.posts.filter(x=>x.path==="/api/assistant/chat")).toHaveLength(2);
  }finally{await f.stop();}
});

test("any one-source story can request framing exposure without publisher comparison",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    const card=page.locator("#stories .storyCard").first();
    await expect(card).toBeVisible();
    const experiment=card.locator('[data-command="MAKE_FRAMING_EXPOSURE"]');
    await expect(experiment).toBeVisible();
    await expect(experiment).toContainText("EXPOSE POLITICAL FRAMING");
    await expect(page.locator(".videoRow")).toContainText("STANDARD");
    page.on("dialog",dialog=>dialog.accept());
    await experiment.click();
    await expect.poll(()=>f.posts.filter(x=>x.body.action==="MAKE_FRAMING_EXPOSURE").length).toBe(1);
    await expect(card.locator(".statusTag").filter({hasText:"NARRATIVE REVIEW"})).toBeVisible();
    await expect(experiment).toBeDisabled();
    expect(f.posts.filter(x=>x.body.action==="MAKE_FRAMING_EXPOSURE")).toHaveLength(1);
    await expect(page.locator(".videoRow")).toContainText("STANDARD");
  }finally{await f.stop();}
});

test("paired normal and narrative review display as separate keyed board rows with independent version actions",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    const original=f.story.topic,now=new Date().toISOString();
    f.state.stories=[
      {...f.story,id:"story-1",boardEntryId:"story-1::STANDARD",
        sourceStoryId:"story-1",entryMode:"STANDARD",productionMode:"STANDARD",
        displayTitle:original,status:"COMPLETE",stage:"COMPLETE",progress:100,
        currentVideoVersionId:"story-1#v1",videoFilename:"original.mp4",
        videoVersion:1,entryHasVideo:true,entryMayRemake:true,uploaded:false},
      {...f.story,id:"story-1",boardEntryId:"story-1::FRAMING_EXPOSURE",
        sourceStoryId:"story-1",entryMode:"FRAMING_EXPOSURE",productionMode:"FRAMING_EXPOSURE",
        displayTitle:"Narrative Review: "+original,status:"COMPLETE",stage:"COMPLETE",progress:100,
        currentVideoVersionId:"story-1#v2",videoFilename:"review.mp4",
        videoVersion:2,entryHasVideo:true,entryMayRemake:true,uploaded:false}
    ];
    f.state.videos=[
      {...f.version,current:false,filename:"original.mp4",versionId:"story-1#v1",version:1,completedAt:now},
      {...f.version,current:true,filename:"review.mp4",versionId:"story-1#v2",
        version:2,productionMode:"FRAMING_EXPOSURE",completedAt:now}
    ];
    f.state.counts.videoVersions=2;
    await page.goto(url);
    const rows=page.locator("#stories .storyCard");
    await expect(rows).toHaveCount(2);
    const originalRow=rows.nth(0),reviewRow=rows.nth(1);
    await expect(originalRow.locator(".storyTitle")).toHaveText(original);
    await expect(reviewRow.locator(".storyTitle")).toHaveText("Narrative Review: "+original);
    await expect(originalRow.locator(".statusTag")).toContainText(["COMPLETE","ORIGINAL NEWS"]);
    await expect(reviewRow.locator(".statusTag")).toContainText(["COMPLETE","NARRATIVE REVIEW"]);
    const keys=await rows.evaluateAll(items=>items.map(x=>x.dataset.recordKey));
    expect(new Set(keys).size).toBe(2);
    await expect(originalRow.locator("a[href*='original.mp4']")).toBeVisible();
    await expect(reviewRow.locator("a[href*='review.mp4']")).toBeVisible();
    const archives=page.locator("#videos .videoRow");
    await expect(archives).toHaveCount(2);
    await expect(archives.filter({hasText:"Narrative Review:"})).toHaveCount(1);

    // Each board card uses the source story route but its own actual video version.
    page.on("dialog",dialog=>dialog.accept(dialog.type()==="prompt"?"TikTok":""));
    await originalRow.locator('[data-command="UPLOAD"]').click();
    await expect.poll(()=>f.posts.filter(p=>p.path.endsWith("/upload-status")
      &&p.body.versionId==="story-1#v1"&&p.body.uploaded).length).toBe(1);
    await expect(reviewRow.locator('[data-command="UPLOAD"]')).toBeEnabled();
    await reviewRow.locator('[data-command="UPLOAD"]').click();
    await expect.poll(()=>f.posts.filter(p=>p.path.endsWith("/upload-status")
      &&p.body.versionId==="story-1#v2"&&p.body.uploaded).length).toBe(1);
    await reviewRow.locator('[data-command="SCRAP"]').click();
    await expect.poll(()=>f.posts.filter(p=>p.path.endsWith("/scrap-status")
      &&p.body.versionId==="story-1#v2"&&p.body.scrapped).length).toBe(1);
    const stillTwo=await page.locator("#stories .storyCard").count();
    expect(stillTwo).toBe(2);
  }finally{await f.stop();}
});

test("narrative review card retry targets review action rather than normal MAKE",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    f.state.stories=[{...f.story,boardEntryId:"story-1::FRAMING_EXPOSURE",
      entryMode:"FRAMING_EXPOSURE",productionMode:"FRAMING_EXPOSURE",
      displayTitle:"Narrative Review: "+f.story.topic,
      status:"FAILED",pairedVideoState:"FRAMING_FAILED",stage:"SCRIPT",lastFailure:"Draft not valid"}];
    await page.goto(url);
    await expect(page.locator("#stories .storyCard")).toHaveCount(1);
    page.on("dialog",dialog=>dialog.accept());
    await page.locator('.storyCard [data-command="MAKE_FRAMING_EXPOSURE"]').click();
    await expect.poll(()=>f.posts.filter(p=>p.body.action==="MAKE_FRAMING_EXPOSURE").length).toBe(1);
    expect(f.posts.some(p=>p.body.action==="MAKE")).toBe(false);
  }finally{await f.stop();}
});

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
    await page.evaluate(()=>{
      window.__maxBoardAfterAction=0;
      const root=document.querySelector("#stories");
      window.__boardCapWatcher=new MutationObserver(()=>{
        window.__maxBoardAfterAction=Math.max(window.__maxBoardAfterAction,root.querySelectorAll(".storyCard").length);
      });
      window.__boardCapWatcher.observe(root,{childList:true,subtree:false});
    });
    await page.locator('.storyCard [data-command="MAKE"]').click();
    await expect(page.locator("#boardResultCount")).toContainText("Showing 1–25 of 25");
    await expect(page.locator(".storyCard")).toHaveCount(25);
    const maximum=await page.evaluate(()=>{window.__boardCapWatcher.disconnect();return window.__maxBoardAfterAction});
    expect(maximum).toBeLessThanOrEqual(25);
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


test("operator console uses real feed and worker instruments and retains expanded intelligence",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    await expect(page.locator("#feedMatrix .matrixCell")).toHaveCount(90);
    await expect(page.locator(".workerCard .telemetryMeter")).toHaveCount(20);
    await expect(page.locator(".workerCard .telemetryMeter.unavailable")).toHaveCount(10);
    await expect(page.locator("#statusDistribution")).toContainText("CURRENT SNAPSHOT");
    const details=page.locator(".storyCard .storyDetails").first();
    await expect(details).toBeVisible();
    await expect(details).not.toHaveAttribute("open","");
    await details.locator("summary").click();
    await expect(details).toHaveAttribute("open","");
    await expect(details.locator(".sources")).toContainText("Reuters");
    const card=page.locator(".storyCard").first();
    await page.evaluate(()=>{window.__ledgerCard=document.querySelector(".storyCard")});
    f.story.progress=24;f.event("story");
    await page.waitForTimeout(650);
    await expect(details).toHaveAttribute("open","");
    expect(await page.evaluate(()=>window.__ledgerCard===document.querySelector(".storyCard"))).toBe(true);
    await expect(card.locator('[data-command="MAKE"]')).toBeVisible();
  }finally{await f.stop()}
});
test("source matrix responds to live SSE without a full state read",async({page})=>{
  const f=fixture(),url=await f.start();
  try{
    await page.goto(url);
    await expect(page.locator("#feedMatrix .matrixCell.ok")).toHaveCount(90);
    const reads=f.getCount;
    f.event("feed",{...f.state.feeds[0],status:"FAILED"});
    await expect(page.locator("#feedMatrix .matrixCell.failed")).toHaveCount(1);
    expect(f.getCount).toBe(reads);
    await expect(page.locator("#feedMatrix")).toContainText("1 FAILED");
  }finally{await f.stop()}
});
test("terminal screenshots and horizontal overflow across reference viewports",async({page})=>{
  const f=fixture(),url=await f.start();
  const dir=path.join(ROOT,"test-results","terminal-screenshots");
  fs.mkdirSync(dir,{recursive:true});
  try{
    await page.goto(url);
    await expect(page.locator(".storyCard")).toHaveCount(1);
    for(const [w,h] of [[1440,900],[1024,768],[390,844]]){
      await page.setViewportSize({width:w,height:h});
      await expect(page.locator(".storyCard [data-command='MAKE']")).toBeVisible();
      await page.screenshot({path:path.join(dir,"console-"+w+"x"+h+".png"),fullPage:true,animations:"disabled"});
      const width=await page.evaluate(()=>({page:document.documentElement.scrollWidth,viewport:innerWidth}));
      expect(width.page).toBeLessThanOrEqual(width.viewport+2);
    }
    f.story.status="FAILED";f.story.lastFailure="Render stage exhausted its retries";
    f.story.productionAttempts=[{event:"FAILED",stage:"VISUALS",worker:"worker-2",at:new Date().toISOString()}];
    f.event("story");
    await expect(page.locator(".failurePanel")).toBeVisible();
    await page.screenshot({path:path.join(dir,"console-failed-390x844.png"),fullPage:true,animations:"disabled"});
  }finally{await f.stop()}
});


test("meme discovery shows an honest recent FOUND-to-analysis funnel",async({page})=>{
  const f=fixture();
  f.state.memeRecentLookbackHours=8;
  Object.assign(f.state.counts,{
    memeRecentFound:1100,memeRecentAnalyzed:43,memeRecentQueued:40,
    memeRecentUnscored:1015,memeRecentFailed:2,memeRecentWorthy:1
  });
  const url=await f.start();
  try{
    await page.goto(url);
    await page.locator('[data-filter="MEME_DISCOVERY"]').click();
    const funnel=page.locator("#memeRecentFunnel");
    await expect(funnel).toBeVisible();
    await expect(funnel).toContainText("8H FOUND: 1100");
    await expect(funnel).toContainText("ANALYZED: 43");
    await expect(funnel).toContainText("PENDING: 40");
    await expect(funnel).toContainText("UNSCORED: 1015");
    await expect(funnel).toContainText("FAILED: 2");
    await expect(funnel).toContainText("QUALIFIED: 1");
    await page.locator("#statusFilter").selectOption("WORTHY");
    await expect(page.locator("#memeRecentFunnel")).toBeHidden();
  }finally{await f.stop();}
});

test("meme discovery defaults to every completed evaluation and filters server-side across pages",async({page})=>{
  const f=fixture();
  const now=new Date().toISOString();
  const records=[];
  for(let i=0;i<43;i++){
    const score=i===42?null:i===0?93:i<=10?75:i<=30?62:30;
    records.push({...f.story,id:"discovery-"+String(i).padStart(2,"0"),
      topic:"Meme candidate "+i,latestPublishedAt:now,score:.10,worthy:false,
      memeAnalysisStatus:"COMPLETE",memeScore:score,memeConfidence:.7,
      memeClassification:score==null?"INSUFFICIENT_EVIDENCE":score>=85?"EXCELLENT":
        score>=72?"WORTHY":score>=55?"POSSIBLE":"LOW",
      memeManualDecision:i===0?"NOT_WORTH":"AUTO",
      memeAnalysis:{evidenceStatus:score==null?"INSUFFICIENT_EVIDENCE":"SUFFICIENT",
        dimensions:score==null?{}:{hook:8,absurdity:7,visual:6,relatability:5,originality:7,
          factualClarity:6,timeliness:4,engagement:5},
        reasons:["Source based explanation for "+i],suggestedAngles:[],
        editorialWarnings:[]},sourceUrls:["https://fixture.example/article"]});
  }
  f.state.stories=[...records,
    {...f.story,id:"queued-analysis",topic:"Meme waiting",memeAnalysisStatus:"QUEUED"},
    {...f.story,id:"failed-analysis",topic:"Meme failed",memeAnalysisStatus:"FAILED",memeAnalysisError:"Model failed"}];
  const url=await f.start();
  try{
    await page.goto(url);
    await page.locator('[data-filter="MEME_DISCOVERY"]').click();
    const cards=page.locator("#stories .storyCard");
    await expect(page.locator("#memeDiscoveryControls")).toBeVisible();
    await expect(page.locator("#memeDiscoveryFilter")).toHaveValue("ALL_ANALYZED");
    await expect(page.locator("#railMemeDiscovery")).toHaveText("43");
    await expect(cards).toHaveCount(25);
    await expect(page.locator("#boardResultCount")).toContainText("43");
    await expect(cards.first()).toContainText("Meme candidate 0");
    await expect(cards.first().locator(".memeScoreMeter")).toContainText("93");
    await expect(cards.first().locator(".memeDimensions")).toContainText("HOOK");
    await page.locator("#boardBottomPager button[aria-label='Next page']").click();
    await expect(cards).toHaveCount(18);
    await page.locator("#memeDiscoveryFilter").selectOption("EXCELLENT");
    await expect(cards).toHaveCount(1);
    await page.locator("#memeDiscoveryFilter").selectOption("WORTHY");
    await expect(cards).toHaveCount(11);
    await page.locator("#memeDiscoveryFilter").selectOption("POSSIBLE");
    await expect(cards).toHaveCount(20);
    await page.locator("#memeDiscoveryFilter").selectOption("LOW");
    await expect(cards).toHaveCount(11);
    await page.locator("#memeDiscoveryFilter").selectOption("INSUFFICIENT_EVIDENCE");
    await expect(cards).toHaveCount(1);
    await expect(cards.first()).toContainText("INSUFFICIENT EVIDENCE");
    await page.locator("#memeDiscoveryFilter").selectOption("PENDING");
    await expect(cards).toHaveCount(1);
    await expect(cards.first()).toContainText("Meme waiting");
    await page.locator("#memeDiscoveryFilter").selectOption("FAILED");
    await expect(cards).toHaveCount(1);
    await expect(cards.first()).toContainText("Model failed");
    await page.locator("#memeDiscoveryFilter").selectOption("ALL_ANALYZED");
    await page.locator("#search").fill("candidate 35");
    await expect(cards).toHaveCount(1);
    await expect(cards.first()).toContainText("Meme candidate 35");
    await page.locator("#statusFilter").selectOption("WORTHY");
    await expect(page.locator("#memeDiscoveryControls")).toBeHidden();
  }finally{await f.stop();}
});

test("meme discovery rail displays independent scores, safely escaped details and meme-only actions",async({page})=>{
  const f=fixture();
  f.story.worthy=false;f.story.score=.12;
  f.story.memeAnalysisStatus="COMPLETE";f.story.memeScore=93;f.story.memeConfidence=.91;
  f.story.memeClassification="EXCELLENT";f.story.memeManualDecision="AUTO";
  f.story.sourceUrls=["https://fixture.example/article"];
  f.story.memeAnalysis={model:"llama3.1:8b",analyzedAt:new Date().toISOString(),
    evidenceStatus:"SUFFICIENT",reasons:["Surprising shopping <script>window.__xss=1</script>"],
    suggestedAngles:[{angle:"The big potato",hook:"Potato panic!",visualConcept:"Giant potato beside checkout"}],
    editorialWarnings:["Verify the local source"]};
  const url=await f.start();
  try{
    await page.goto(url);
    await page.locator('[data-filter="MEME_DISCOVERY"]').click();
    const card=page.locator("#stories .storyCard");
    await expect(card).toHaveCount(1);
    await expect(card.locator(".memeScoreMeter")).toContainText("93");
    await expect(card.locator(".memePanel")).toContainText("Potato panic!");
    await expect(page.locator("#railMemeDiscovery")).toHaveText("1");
    await expect(card.locator('[data-command="WORTH"]')).toHaveCount(0);
    expect(await page.evaluate(()=>window.__xss||false)).toBe(false);
    await card.locator('[data-command="MEME_ACCEPT"]').click();
    await expect.poll(()=>f.posts.filter(p=>p.path.endsWith("/meme-decision")).length).toBe(1);
    expect(f.posts.find(p=>p.path.endsWith("/meme-decision")).body.decision).toBe("WORTH");
    await card.locator('[data-command="REANALYZE_MEME"]').click();
    await expect.poll(()=>f.posts.filter(p=>p.path.endsWith("/analyze-meme")).length).toBe(1);
  }finally{await f.stop();}
});
