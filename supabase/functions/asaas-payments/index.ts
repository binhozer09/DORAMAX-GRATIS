import { createClient } from "npm:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

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

const ASAAS_BASE = Deno.env.get("ASAAS_BASE_URL") || "https://api.asaas.com/v3";
async function asaas(path: string, apiKey: string, init: RequestInit = {}) {
  const response = await fetch(ASAAS_BASE + path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      "Accept": "application/json",
      "access_token": apiKey,
      "User-Agent": "DORAMAX-GRATIS/1.0",
      ...(init.headers || {}),
    },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    console.error("Asaas API error", response.status, data);
    throw new Error(data?.errors?.[0]?.description || data?.message || "Falha na comunicação com o Asaas.");
  }
  return data;
}

async function issueAccess(admin: any, userId: string) {
  const { error } = await admin.from("access_grants").upsert(
    { user_id: userId, is_unlocked: true, updated_at: new Date().toISOString() },
    { onConflict: "user_id" },
  );
  if (error) throw error;
}

async function ensureWebhook(apiKey: string, token: string, email: string) {
  if (token.length < 32 || token.length > 255 || /\\s/.test(token)) {
    throw new Error("ASAAS_WEBHOOK_TOKEN precisa ter entre 32 e 255 caracteres e não conter espaços.");
  }
  const webhookUrl = Deno.env.get("SUPABASE_URL") + "/functions/v1/asaas-webhook";
  const current = await asaas("/webhooks", apiKey);
  const rows = Array.isArray(current?.data) ? current.data : [];
  if (rows.some((item: any) => item.url === webhookUrl && item.enabled === true)) return;
  await asaas("/webhooks", apiKey, {
    method: "POST",
    body: JSON.stringify({
      name: "DORAMAX - Confirmação de pagamentos",
      url: webhookUrl,
      email,
      enabled: true,
      interrupted: false,
      apiVersion: 3,
      authToken: token,
      sendType: "SEQUENTIALLY",
      events: ["PAYMENT_RECEIVED"],
    }),
  });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "Método não permitido." }, 405);

  try {
    const auth = req.headers.get("Authorization") || "";
    const jwt = auth.startsWith("Bearer ") ? auth.slice(7) : "";
    if (!jwt) return json({ error: "Faça login para continuar." }, 401);

    const url = Deno.env.get("SUPABASE_URL");
    const admin = createClient(url!, serviceKey(), { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: authData, error: authError } = await admin.auth.getUser(jwt);
    const user = authData?.user;
    if (authError || !user) return json({ error: "Sessão inválida. Entre novamente." }, 401);

    const apiKey = Deno.env.get("ASAAS_API_KEY");
    if (!apiKey) return json({ error: "A chave ASAAS_API_KEY não está configurada no Supabase." }, 500);

    const body = await req.json().catch(() => ({}));
    const action = body?.action;

    const { data: grant, error: grantError } = await admin
      .from("access_grants").select("is_unlocked").eq("user_id", user.id).maybeSingle();
    if (grantError) throw grantError;
    if (grant?.is_unlocked === true) return json({ unlocked: true });

    if (action === "create") {
      const webhookToken = Deno.env.get("ASAAS_WEBHOOK_TOKEN");
      if (webhookToken) {
        try { await ensureWebhook(apiKey, webhookToken, user.email || ""); }
        catch (webhookError) { console.error("Não foi possível registrar o webhook Asaas; a consulta automática continuará ativa:", webhookError); }
      }
      const { data: pending, error: pendingError } = await admin
        .from("doramax_payments")
        .select("asaas_payment_id,pix_payload,pix_encoded_image,invoice_url,status")
        .eq("user_id", user.id).eq("status", "PENDING")
        .order("created_at", { ascending: false }).limit(1).maybeSingle();
      if (pendingError) throw pendingError;
      if (pending) {
        try {
          const paymentPath = "/payments/" + encodeURIComponent(pending.asaas_payment_id);
          const [qr, existingPayment] = await Promise.all([
            asaas(paymentPath + "/pixQrCode", apiKey),
            asaas(paymentPath, apiKey),
          ]);
          return json({
            paymentId: pending.asaas_payment_id,
            pixPayload: qr.payload || pending.pix_payload,
            encodedImage: qr.encodedImage || pending.pix_encoded_image,
            invoiceUrl: pending.invoice_url || existingPayment.invoiceUrl || null,
            unlocked: false,
          });
        } catch (pendingError) {
          // A pending row created in the old sandbox cannot be reused with a live key.
          console.warn("Cobrança pendente anterior não localizada no ambiente Asaas atual; será criada uma nova.", pendingError);
        }
      }

      const email = user.email || "";
      const metadata = user.user_metadata || {};
      const customerName = String(metadata.full_name || metadata.name || metadata.nome || email.split("@")[0] || "Cliente DORAMAX").slice(0, 100);
      const customer = await asaas("/customers", apiKey, {
        method: "POST",
        body: JSON.stringify({ name: customerName, email, notificationDisabled: true, externalReference: user.id }),
      });
      const dueDate = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
      const payment = await asaas("/payments", apiKey, {
        method: "POST",
        body: JSON.stringify({
          customer: customer.id,
          billingType: "PIX",
          value: 5.00,
          dueDate,
          description: "DORAMAX - Acesso completo (pagamento único)",
          externalReference: user.id,
        }),
      });
      const qr = await asaas("/payments/" + encodeURIComponent(payment.id) + "/pixQrCode", apiKey);
      const { error: insertError } = await admin.from("doramax_payments").insert({
        user_id: user.id,
        asaas_payment_id: payment.id,
        amount: 5.00,
        status: payment.status || "PENDING",
        pix_payload: qr.payload || null,
        pix_encoded_image: qr.encodedImage || null,
        invoice_url: payment.invoiceUrl || null,
      });
      if (insertError) throw insertError;
      return json({
        paymentId: payment.id,
        pixPayload: qr.payload,
        encodedImage: qr.encodedImage,
        invoiceUrl: payment.invoiceUrl || null,
        expirationDate: qr.expirationDate || null,
        unlocked: false,
      });
    }

    if (action === "status") {
      const paymentId = String(body?.paymentId || "");
      if (!paymentId || paymentId.length > 100) return json({ error: "Cobrança inválida." }, 400);
      const { data: row, error: rowError } = await admin.from("doramax_payments")
        .select("id,user_id,asaas_payment_id,amount,status")
        .eq("asaas_payment_id", paymentId).eq("user_id", user.id).maybeSingle();
      if (rowError) throw rowError;
      if (!row) return json({ error: "Cobrança não encontrada para esta conta." }, 404);

      const verified = await asaas("/payments/" + encodeURIComponent(paymentId), apiKey);
      if (verified.externalReference !== user.id || Number(verified.value) !== 5 ||
          verified.billingType !== "PIX") {
        return json({ error: "Os dados da cobrança não correspondem a esta conta." }, 403);
      }
      const paid = String(verified.status) === "RECEIVED";
      const nextStatus = String(verified.status || row.status);
      const { error: updateError } = await admin.from("doramax_payments")
        .update({ status: nextStatus, paid_at: paid ? new Date().toISOString() : null, updated_at: new Date().toISOString() })
        .eq("id", row.id);
      if (updateError) throw updateError;
      if (paid) await issueAccess(admin, user.id);
      return json({ unlocked: paid, status: nextStatus });
    }

    return json({ error: "Ação inválida." }, 400);
  } catch (error) {
    console.error("asaas-payments:", error);
    return json({ error: error instanceof Error ? error.message : "Erro interno ao processar pagamento." }, 500);
  }
});
