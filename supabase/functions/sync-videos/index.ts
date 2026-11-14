const corsHeaders = {
  "Access-Control-Allow-Origin": "null",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Vary": "Origin",
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {status,headers:{...corsHeaders,"Content-Type":"application/json"}});
function decodeXml(v:string){return v.replace(/&amp;/g,"&").replace(/&quot;/g,'"').replace(/&#39;|&apos;/g,"'").replace(/&lt;/g,"<").replace(/&gt;/g,">");}
function tag(e:string,n:string){const m=e.match(new RegExp("<"+n+"(?:\\s[^>]*)?>([\\s\\S]*?)</"+n+">"));return m?decodeXml(m[1].replace(/<!\\[CDATA\\[|\\]\\]>/g,"").trim()):"";}
Deno.serve(async(req:Request)=>{
 if(req.method==="OPTIONS")return new Response("ok",{headers:corsHeaders});
 if(req.method!=="POST")return json({error:"Method not allowed"},405);
 try{
  const url=Deno.env.get("SUPABASE_URL"), raw=Deno.env.get("SUPABASE_SECRET_KEYS");
  const key=raw?JSON.parse(raw)["default"]:Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if(!url||!key)throw new Error("Credenciais internas do Supabase não configuradas.");
  const auth=req.headers.get("Authorization")||"";
  if(!auth.startsWith("Bearer "))return json({error:"Sessão necessária."},401);
  const me=await fetch(url+"/auth/v1/user",{headers:{apikey:key,Authorization:auth}});
  if(!me.ok)return json({error:"Sessão inválida ou expirada."},401);
  const user=await me.json();
  const h={apikey:key,Authorization:"Bearer "+key,"Content-Type":"application/json"};
  const ar=await fetch(url+"/rest/v1/admin_users?select=user_id&user_id=eq."+encodeURIComponent(user.id),{headers:h});
  if(!ar.ok)throw new Error("Não foi possível validar a permissão administrativa.");
  if(!(await ar.json()).length)return json({error:"Apenas administradores podem sincronizar o catálogo."},403);
  const settingsUrl=url+"/rest/v1/app_settings?key=eq.app_config&select=value";
  const sr=await fetch(settingsUrl,{headers:h});if(!sr.ok)throw new Error("Não foi possível ler as configurações.");
  const current=(await sr.json())?.[0]?.value||{};
  const last=Date.parse(current.catalog_synced_at||"");
  if(Number.isFinite(last)&&Date.now()-last<10*60*1000)return json({ok:true,synced:false,reason:"recent_sync",message:"Catálogo sincronizado há menos de 10 minutos."});
  const configured=Array.isArray(current.youtube_channels)?current.youtube_channels:["lucasdramascurtos"];
  const handle=String(configured[0]||"lucasdramascurtos").replace(/^@/,"");
  let channelId=/^UC[A-Za-z0-9_-]{20,}$/.test(handle)?handle:"";
  if(!channelId){
   const page=await fetch("https://www.youtube.com/@"+encodeURIComponent(handle),{headers:{"User-Agent":"Mozilla/5.0 (compatible; DORAMAXCatalog/1.0)","Accept-Language":"pt-BR,pt;q=0.9,en;q=0.8"}});
   if(!page.ok)throw new Error("Não foi possível localizar o canal no YouTube ("+page.status+").");
   const html=await page.text();
   const m=html.match(/<meta[^>]+itemprop=["']channelId["'][^>]+content=["'](UC[A-Za-z0-9_-]{20,})["']/i)||html.match(/"channelId":"(UC[A-Za-z0-9_-]{20,})"/)||html.match(/"externalId":"(UC[A-Za-z0-9_-]{20,})"/);
   if(!m)throw new Error("O YouTube não retornou o ID do canal @"+handle+".");channelId=m[1];
  }
  const fr=await fetch("https://www.youtube.com/feeds/videos.xml?channel_id="+encodeURIComponent(channelId),{headers:{"User-Agent":"Mozilla/5.0 (compatible; DORAMAXCatalog/1.0)"}});
  if(!fr.ok)throw new Error("Feed do YouTube indisponível ("+fr.status+").");
  const xml=await fr.text();
  const entries=[...xml.matchAll(/<entry>([\s\S]*?)<\/entry>/g)].map(m=>m[1]);
  const videos=entries.map((e,i)=>({id:tag(e,"yt:videoId"),title:tag(e,"title"),published:tag(e,"published"),source:handle,is_active:true,position:i,manual_override:false})).filter(v=>v.id&&v.title);
  if(!videos.length)throw new Error("Feed sem vídeos válidos; catálogo atual preservado.");
  const ids=videos.map(v=>v.id);
  const ov=await fetch(url+"/rest/v1/videos?select=id&manual_override=eq.true&id=in.("+ids.map(encodeURIComponent).join(",")+")",{headers:h});
  if(!ov.ok)throw new Error("Não foi possível verificar vídeos fixados manualmente.");
  const overrides=new Set((await ov.json()).map((v:{id:string})=>v.id));
  const rows=videos.filter(v=>!overrides.has(v.id));
  if(rows.length){
   const wr=await fetch(url+"/rest/v1/videos?on_conflict=id",{method:"POST",headers:{...h,Prefer:"resolution=merge-duplicates,return=minimal"},body:JSON.stringify(rows)});
   if(!wr.ok)throw new Error("Falha ao salvar vídeos: "+await wr.text());
  }
  const cr=await fetch(url+"/rest/v1/videos?select=id&is_active=eq.true",{headers:{...h,Prefer:"count=exact",Range:"0-0"}});
  const cm=(cr.headers.get("Content-Range")||"").match(/\/(\d+)$/);
  const count=cm?Number(cm[1]):Number(current.video_count||0), now=new Date().toISOString();
  const next={...current,catalog_synced_at:now,catalog_updated_at:now,youtube_channels:[handle],remote_catalog_source:"supabase",app_remote_content_enabled:true,video_count:count,last_verified_at:now};
  const ur=await fetch(settingsUrl,{method:"PATCH",headers:{...h,Prefer:"return=minimal"},body:JSON.stringify({value:next,updated_at:now})});
  if(!ur.ok)throw new Error("Vídeos salvos, mas falhou a atualização das configurações.");
  return json({ok:true,synced:true,received:videos.length,saved:rows.length,preservedManualOverrides:videos.length-rows.length,activeVideos:count,channel:handle,updatedAt:now});
 }catch(e){return json({ok:false,error:e instanceof Error?e.message:String(e)},500);}
});