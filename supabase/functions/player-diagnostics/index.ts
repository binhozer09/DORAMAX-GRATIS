import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "https://appassets.androidplatform.net",
  "Access-Control-Allow-Headers": "content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Vary": "Origin",
};

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405, headers: corsHeaders });
  const origin = req.headers.get("origin");
  if (origin && origin !== "https://appassets.androidplatform.net") {
    return new Response("Origin not allowed", { status: 403, headers: corsHeaders });
  }
  try {
    const body = await req.json();
    const videoId = String(body?.video_id ?? "");
    const event = String(body?.event ?? "");
    const title = typeof body?.video_title === "string" ? body.video_title.slice(0, 200) : null;
    const errorCode = Number.isInteger(body?.error_code) ? body.error_code : null;
    if (!/^[A-Za-z0-9_-]{11}$/.test(videoId)) {
      return new Response(JSON.stringify({ error: "invalid_video_id" }), { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }
    if (!["youtube_player_error", "youtube_player_playing"].includes(event)) {
      return new Response(JSON.stringify({ error: "invalid_event" }), { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }
    if (event === "youtube_player_error" && errorCode === null) {
      return new Response(JSON.stringify({ error: "missing_error_code" }), { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }
    const url = Deno.env.get("SUPABASE_URL");
    const rawSecrets = Deno.env.get("SUPABASE_SECRET_KEYS");
    const serviceKey = rawSecrets
      ? JSON.parse(rawSecrets)["default"]
      : Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!url || !serviceKey) throw new Error("Supabase service credentials unavailable");
    const supabase = createClient(url, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { error } = await supabase.from("player_diagnostics").insert({
      video_id: videoId, video_title: title, event, error_code: errorCode,
    });
    if (error) throw error;
    return new Response(JSON.stringify({ ok: true }), { status: 202, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  } catch (_error) {
    return new Response(JSON.stringify({ error: "diagnostic_write_failed" }), { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  }
});
