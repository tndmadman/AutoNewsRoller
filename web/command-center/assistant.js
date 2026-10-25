/* Persistent read-only chat. All user/model/source content is textContent, never HTML. */
(()=>{
  "use strict";
  const $=id=>document.getElementById(id);
  const log=$("assistantMessages"),form=$("assistantForm"),input=$("assistantInput");
  if(!log||!form||!input)return;
  let busy=false;
  let records=[];
  function status(message,error=false){
    const node=$("assistantStatus");
    node.textContent=message;node.classList.toggle("assistantError",!!error);
  }
  function appendBubble(role,content,time){
    const article=document.createElement("article");
    article.className="assistantBubble "+(role==="user"?"assistantUser":"assistantBot");
    const head=document.createElement("div");head.className="assistantBubbleHead";
    head.textContent=(role==="user"?"YOU":"LOCAL OLLAMA")+" · "+(
      time?new Date(time).toLocaleString():"NOW");
    const body=document.createElement("div");body.className="assistantBubbleBody";
    body.textContent=content;
    article.append(head,body);log.appendChild(article);
    log.scrollTop=log.scrollHeight;
  }
  function draw(){
    log.replaceChildren();
    if(!records.length){
      const empty=document.createElement("p");
      empty.className="assistantEmpty";
      empty.textContent="Chat is saved on the Command Center computer. Ask about live jobs, past videos or generation prompts.";
      log.appendChild(empty);
      return;
    }
    for(const row of records)appendBubble(row.role,row.content,row.at);
  }
  async function loadHistory(){
    try{
      const response=await api("/api/assistant");
      records=Array.isArray(response.messages)?response.messages:[];
      $("assistantModel").textContent=(response.model||"LOCAL OLLAMA")+
        " · READ-ONLY · SAVED HISTORY";
      draw();status("READY / CONVERSATION RESTORED");
    }catch(error){status(error.message==="AUTH_REQUIRED"?"KEY REQUIRED":error.message,true);}
  }
  function setBusy(value){
    busy=value;$("assistantSend").disabled=value;$("assistantClear").disabled=value;
    input.disabled=value;
  }
  form.addEventListener("submit",async event=>{
    event.preventDefault();
    if(busy)return;
    const question=input.value.trim();
    if(!question)return;
    setBusy(true);input.value="";
    records.push({role:"user",content:question,at:new Date().toISOString()});
    draw();status("ASKING OLLAMA / REFRESHING LIVE SYSTEM CONTEXT…");
    try{
      const response=await api("/api/assistant/chat",{
        method:"POST",
        headers:{"Content-Type":"application/json"},
        body:JSON.stringify({message:question})
      });
      records.push({role:"assistant",content:response.answer||"",at:response.at||new Date().toISOString()});
      draw();status("REPLY SAVED · "+(response.model||"LOCAL OLLAMA"));
    }catch(error){
      // Failed requests are not persisted server-side. Fetch canonical history
      // rather than showing a local-only message as if it was saved.
      await loadHistory();
      status("CHAT FAILED: "+(error.message||"local Ollama unreachable"),true);
      input.value=question;
    }finally{setBusy(false);input.focus();}
  });
  input.addEventListener("keydown",event=>{
    if(event.key!=="Enter"||event.shiftKey||event.isComposing||event.keyCode===229)return;
    event.preventDefault();
    if(!busy&&typeof form.requestSubmit==="function")form.requestSubmit();
  });
  $("assistantClear").addEventListener("click",async()=>{
    if(busy||!confirm("Delete saved assistant conversation from the controller? This does not affect any news jobs or videos."))return;
    setBusy(true);
    try{
      await api("/api/assistant/history",{method:"DELETE"});
      records=[];draw();status("SAVED CONVERSATION CLEARED");
    }catch(error){status("CLEAR FAILED: "+error.message,true);}
    finally{setBusy(false);}
  });
  const select=$("assistantPromptSelect"),source=$("assistantPromptSource");
  async function loadPrompts(){
    try{
      const response=await api("/api/assistant/prompts");
      select.replaceChildren();
      const empty=document.createElement("option");
      empty.value="";empty.textContent="Select a source…";select.append(empty);
      for(const p of response.prompts||[]){
        const option=document.createElement("option");
        option.value=p.id;option.textContent=p.label+" · "+p.path;
        select.append(option);
      }
    }catch(error){
      $("assistantPromptNote").textContent="Unable to load prompt library: "+error.message;
    }
  }
  select.addEventListener("change",async()=>{
    const id=select.value;if(!id){source.textContent="Select a prompt source.";return;}
    source.textContent="Loading trusted repository source…";
    try{
      const result=await api("/api/assistant/prompt?id="+encodeURIComponent(id));
      source.textContent=result.text||"Source empty.";
      $("assistantPromptNote").textContent=result.path+" — "+result.note+
        (result.truncated?" (display truncated)":"");
    }catch(error){source.textContent="Could not load source: "+error.message;}
  });
  loadHistory();
  loadPrompts();
})();