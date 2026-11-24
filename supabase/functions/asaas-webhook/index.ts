import { createClient } from "npm:@supabase/supabase-js@2";

function serviceKey() {
  const legacy = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (legacy) return legacy;
  const keys = Deno.env.get("SUPABASE_SECRET_KEYS");
  if (keys) {
    const parsed = JSON.parse(keys);
    const value = parsed.default;
    if (value) return Deno.env.get(value) || value;
  }
  throw new Error("Chave administrativa do Supabase não configurada.");
}
const ASAAS_BASE = "https://api-sandbox.asaas.com/v3";

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405 });
  try {
    const expectedToken = Deno.env.get("ASAAS_WEBHOOK_TOKEN");
    const receivedToken = req.headers.get("asaas-access-token");
    if (!expectedToken || !receivedToken || receivedToken !== expectedToken) {
      return new Response("Unauthorized", { status: 401 });
    }
    const payload = await req.json();
    const event = String(payload?.event || "");
    const paymentId = String(payload?.payment?.id || "");
    if (!paymentId) return Response.json({ received: true, ignored: true });
    if (!["PAYMENT_CONFIRMED", "PAYMENT_RECEIVED"].includes(event)) {
      return Response.json({ received: true, ignored: true });
    }

    const apiKey = Deno.env.get("ASAAS_API_KEY");
    if (!apiKey) throw new Error("ASAAS_API_KEY não configurada.");
    const response = await fetch(ASAAS_BASE + "/payments/" + encodeURIComponent(paymentId), {
      headers: { "access_token": apiKey, "Accept": "application/json" },
    });
    const verified = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error("Não foi possível validar a cobrança no Asaas.");

    const admin = createClient(Deno.env.get("SUPABASE_URL")!, serviceKey(), {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: row, error: rowError } = await admin.from("doramax_payments")
      .select("id,user_id,amount,asaas_payment_id").eq("asaas_payment_id", paymentId).maybeSingle();
    if (rowError) throw rowError;
    if (!row) return Response.json({ received: true, ignored: true });
    if (verified.externalReference !== row.user_id || Number(verified.value) !== 5 ||
        verified.billingType !== "PIX" || !["CONFIRMED", "RECEIVED"].includes(String(verified.status))) {
      return new Response("Payment validation failed", { status: 400 });
    }

    const now = new Date().toISOString();
    const { error: paymentUpdateError } = await admin.from("doramax_payments")
      .update({ status: verified.status, paid_at: now, updated_at: now }).eq("id", row.id);
    if (paymentUpdateError) throw paymentUpdateError;
    const { error: grantError } = await admin.from("access_grants").upsert(
      { user_id: row.user_id, is_unlocked: true, updated_at: now },
      { onConflict: "user_id" },
    );
    if (grantError) throw grantError;
    return Response.json({ received: true, unlocked: true });
  } catch (error) {
    console.error("asaas-webhook:", error);
    return new Response("Webhook processing failed", { status: 500 });
  }
});
